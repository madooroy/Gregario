package com.madooroy.gregario.upload

import com.madooroy.gregario.icu.IcuApi
import com.madooroy.gregario.icu.IcuJson
import com.madooroy.gregario.workout.MiniJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class HttpReply(val code: Int, val text: String)

/** One HTTP call. Plain JVM so it can be faked in tests; call from a background thread. */
fun interface Http {
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpReply

    companion object {
        val JVM = Http { method, url, headers, body, contentType ->
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000; c.requestMethod = method
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("Accept", "application/json")
            try {
                if (body != null) {
                    c.doOutput = true
                    contentType?.let { c.setRequestProperty("Content-Type", it) }
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                HttpReply(code, text)
            } finally { c.disconnect() }
        }
    }
}

/**
 * [retry] false = a refusal that won't change by itself. [signIn] = the key or sign-in was refused (401/403): the ride
 * waits, and goes up once the key or the Strava connection is fixed (review U3), instead of failing for good.
 */
class UploadException(message: String, val retry: Boolean = true, val signIn: Boolean = false) : Exception(message)

fun multipart(fields: Map<String, String>, fileField: String, fileName: String, data: ByteArray): Pair<ByteArray, String> {
    val boundary = "----Gregario" + System.nanoTime()
    val out = ByteArrayOutputStream()
    fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
    for ((k, v) in fields) { line("--$boundary"); line("Content-Disposition: form-data; name=\"$k\""); line(""); line(v) }
    line("--$boundary"); line("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"")
    line("Content-Type: application/octet-stream"); line("")
    out.write(data); line(""); line("--$boundary--")
    return out.toByteArray() to "multipart/form-data; boundary=$boundary"
}

private fun parse(text: String): Any? = runCatching { MiniJson.parse(text) }.getOrNull()
private fun json(text: String): Map<*, *> = (runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *>) ?: emptyMap<Any, Any>()
private fun idText(v: Any?): String? = when (v) { null -> null; is Double -> v.toLong().toString(); else -> v.toString() }

/**
 * Uploads a ride's FIT file to intervals.icu as an activity, paired with the planned workout,
 * then sets its name, description and RPE.
 */
class IcuUploader(apiKey: String, private val http: Http = Http.JVM, private val base: String = "https://intervals.icu/api/v1") {
    private val auth = mapOf("Authorization" to "Basic " + IcuApi.base64("API_KEY:$apiKey"))

    /** [id]: the activity (null if intervals.icu already has this file). [warning]: uploaded, but something after it failed. */
    data class Result(val id: String?, val warning: String? = null)

    /**
     * Upload the FIT as an activity: name, description and the planned event to pair go on the query string (as
     * intervals.icu documents them); RPE is set with a PUT afterwards. A refusal that may come from a stale planned
     * event is retried once without pairing, so a ride is never blocked by its calendar entry.
     */
    fun upload(fit: ByteArray, fileName: String, name: String, description: String, pairedEventId: String?, rpe: Int?,
               intervals: List<Map<String, Any?>> = emptyList()): Result {
        val paired = pairedEventId?.toLongOrNull()
        var r = post(fit, fileName, name, description, paired)
        var warning: String? = null
        if (paired != null && r.code in 400..499 && r.code != 401 && r.code != 403 && r.code != 429 && !duplicate(r)) {
            r = post(fit, fileName, name, description, null)
            warning = "not paired with the planned workout"
        }
        if (duplicate(r)) return Result(null)
        check(r, "intervals.icu")
        val a = parse(r.text)
        val first = (a as? List<*>)?.firstOrNull() as? Map<*, *>          // an array of { icu_athlete_id, id }
        val m = a as? Map<*, *>
        val id = idText(m?.get("id")) ?: idText(((m?.get("activities") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("id"))
            ?: idText(first?.get("id"))
            ?: throw UploadException("intervals.icu accepted the file but returned no activity id", retry = false)
        if (rpe != null) setRpe(id, rpe)?.let { warning = listOfNotNull(warning, it).joinToString(" · ") }
        if (intervals.isNotEmpty()) setIntervals(id, intervals)?.let { warning = listOfNotNull(warning, it).joinToString(" · ") }
        return Result(id, warning)
    }

    /**
     * Replace the activity's intervals with the workout's own ([RideRecorder.icuIntervals]): intervals.icu doesn't use
     * the FIT's laps by itself even with "Use laps for intervals" on (Nov 25). Returns a warning, or null when it worked.
     */
    fun setIntervals(id: String, intervals: List<Map<String, Any?>>): String? =
        putIntervals(id, intervals).let { if (it in 200..299) null else intervalsWarning(it) }

    /** The PUT behind [setIntervals]: its HTTP code, or null with no answer. */
    fun putIntervals(id: String, intervals: List<Map<String, Any?>>): Int? = runCatching {
        http.send("PUT", "$base/activity/$id/intervals?all=true", auth, IcuJson.toJson(intervals).toByteArray(), "application/json").code
    }.getOrNull()

    /** Set the RPE on an activity already there; returns a warning, or null when it worked. */
    fun setRpe(id: String, rpe: Int): String? {
        val r = runCatching { http.send("PUT", "$base/activity/$id", auth, IcuJson.toJson(mapOf("icu_rpe" to rpe.toDouble())).toByteArray(),
            "application/json") }.getOrNull()
        return if (r != null && r.code in 200..299) null else "RPE not set on intervals.icu (${r?.code ?: "no answer"})"
    }

    private fun post(fit: ByteArray, fileName: String, name: String, description: String, paired: Long?): HttpReply {
        val q = "?name=" + enc(name) + "&description=" + enc(description) + (paired?.let { "&paired_event_id=$it" } ?: "")
        val (body, type) = multipart(emptyMap(), "file", fileName, fit)
        return http.send("POST", "$base/athlete/0/activities$q", auth, body, type)
    }
    private fun duplicate(r: HttpReply) = r.code !in 200..299 && (r.code == 409 || r.text.take(300).contains("uplicate"))
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Where the Strava sign-in is kept (the app's private settings). */
interface StravaStore {
    var clientId: String; var clientSecret: String
    var accessToken: String; var expiresAt: Long; var refreshToken: String
}

/**
 * Strava through the rider's own Strava API application: authorize once by pasting
 * the "localhost" address Strava redirects to, then uploads renew the six-hour access token themselves.
 */
class StravaAccount(private val store: StravaStore, private val http: Http = Http.JVM,
                    private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    val configured get() = store.clientId.isNotBlank() && store.clientSecret.isNotBlank() && store.refreshToken.isNotBlank()

    fun authorizeUrl(clientId: String) = AUTHORIZE + "?client_id=" + enc(clientId) + "&response_type=code" +
        "&redirect_uri=" + enc(REDIRECT) + "&approval_prompt=force&scope=" + enc(SCOPE)

    /** Exchange the code in the pasted address (or a bare code) for tokens, and save them. */
    fun connect(clientId: String, clientSecret: String, pasted: String) {
        val code = codeFrom(pasted) ?: throw UploadException("No code found: paste the whole localhost address", retry = false)
        if ("scope=" in pasted && "activity:write" !in URLDecoder.decode(pasted, "UTF-8"))
            throw UploadException("Uploads weren't allowed: authorize again and leave \"Upload your activities\" ticked", retry = false)
        val r = http.send("POST", TOKEN, emptyMap(), form("client_id" to clientId.trim(), "client_secret" to clientSecret.trim(),
            "code" to code, "grant_type" to "authorization_code"), FORM)
        if (r.code !in 200..299) throw UploadException("Strava refused the code (HTTP ${r.code}): ${r.text.take(150)}", retry = false)
        val a = json(r.text)
        store.clientId = clientId.trim(); store.clientSecret = clientSecret.trim()
        save(a)
    }

    fun forget() { store.accessToken = ""; store.refreshToken = ""; store.expiresAt = 0 }

    @Synchronized fun token(): String {
        if (!configured) throw UploadException("Strava is not connected", retry = false)
        if (store.accessToken.isBlank() || store.expiresAt - 300 <= now()) {
            val r = http.send("POST", TOKEN, emptyMap(), form("client_id" to store.clientId, "client_secret" to store.clientSecret,
                "grant_type" to "refresh_token", "refresh_token" to store.refreshToken), FORM)
            if (r.code in listOf(400, 401, 403)) throw UploadException("Strava sign-in no longer valid: connect again in settings", retry = false, signIn = true)
            check(r, "Strava")
            save(json(r.text))
        }
        return store.accessToken
    }

    fun athleteName(): String {
        val r = http.send("GET", "$API/athlete", bearer(), null, null); check(r, "Strava")
        val a = json(r.text)
        return listOfNotNull(a["firstname"] as? String, a["lastname"] as? String).joinToString(" ").ifBlank { "connected" }
    }

    /** Upload and wait for Strava to process it. Returns the activity id (null for a duplicate Strava already has). */
    fun upload(fit: ByteArray, externalId: String, name: String, description: String,
               pollMs: Long = 2_000, tries: Int = 20, sleep: (Long) -> Unit = { Thread.sleep(it) }): String? {
        val (body, type) = multipart(linkedMapOf("data_type" to "fit", "external_id" to externalId, "name" to name,
            "description" to description, "trainer" to "1"), "file", "$externalId.fit", fit)
        var r = http.send("POST", "$API/uploads", bearer(), body, type)
        if (r.code == 401) { store.expiresAt = 0; r = http.send("POST", "$API/uploads", bearer(), body, type) }  // token revoked early
        check(r, "Strava")
        repeat(tries) {
            val s = json(r.text)
            idText(s["activity_id"])?.let { return it }
            val err = s["error"] as? String
            if (!err.isNullOrBlank()) {
                if (err.contains("duplicate", ignoreCase = true)) return null
                throw UploadException("Strava: $err", retry = false)
            }
            val uploadId = idText(s["id_str"]) ?: idText(s["id"]) ?: throw UploadException("Strava returned no upload id")
            sleep(pollMs)
            r = http.send("GET", "$API/uploads/$uploadId", bearer(), null, null)
            check(r, "Strava")
        }
        throw UploadException("Strava is still processing the upload")
    }

    private fun bearer() = mapOf("Authorization" to "Bearer ${token()}")
    private fun save(a: Map<*, *>) {
        (a["access_token"] as? String)?.let { store.accessToken = it }
        (a["expires_at"] as? Double)?.let { store.expiresAt = it.toLong() }
        (a["refresh_token"] as? String)?.let { store.refreshToken = it }   // saved before it is ever used
    }

    companion object {
        const val AUTHORIZE = "https://www.strava.com/oauth/authorize"
        const val TOKEN = "https://www.strava.com/oauth/token"
        const val API = "https://www.strava.com/api/v3"
        const val REDIRECT = "http://localhost/exchange_token"
        const val SCOPE = "read,activity:read_all,activity:write"
        private const val FORM = "application/x-www-form-urlencoded"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }.toByteArray()
        fun codeFrom(pasted: String): String? {
            val p = pasted.trim()
            if ("code=" !in p) return p.ifBlank { null }?.takeIf { ' ' !in it && '/' !in it }
            return p.substringAfter("?").split("&").map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "code" }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
        }
    }
}

internal fun intervalsWarning(code: Int?) = "Steps not set on intervals.icu (${code ?: "no answer"}): use Laps there"

private fun check(r: HttpReply, who: String) {
    if (r.code in 200..299) return
    val retry = r.code == 429 || r.code >= 500
    val signIn = r.code == 401 || r.code == 403
    throw UploadException((if (signIn) "$who refused the sign-in (HTTP ${r.code}): check the key / connection in settings"
        else "$who answered HTTP ${r.code}" + r.text.take(150).let { if (it.isBlank()) "" else ": $it" }), retry, signIn)
}

/**
 * Rides waiting to upload, kept as files in the app's private folder ([dir]): <id>.fit and <id>.json.
 * intervals.icu goes first, then Strava (seconds apart, so neither copies the other).
 * A permanent failure (a refusal, not a network problem) stops retrying that destination and is shown.
 * Rides finished while Strava wasn't connected are not sent there later. The newest 30 are kept.
 *
 * Drafts (draft-<id>.*) are the ride in progress, written every minute and at the finish, so a killed app loses
 * at most a minute; any draft left at the next start is queued as it is (no RPE).
 */
class UploadQueue(private val dir: File) {
    data class Ride(val id: String, val name: String, val description: String, val eventId: String?, val rpe: Int?,
                    val icu: String? = null, val strava: String? = null, val error: String? = null,
                    val icuFailed: Boolean = false, val stravaFailed: Boolean = false,
                    /** The workout's steps for intervals.icu ([IcuUploader.setIntervals]). */
                    val intervals: List<Map<String, Any?>> = emptyList(),
                    /** The steps still have to be set on the uploaded activity (retried, [INTERVAL_TRIES] times at most). */
                    val intervalsPending: Boolean = intervals.isNotEmpty(),
                    val intervalTries: Int = 0) {
        val icuDone get() = icu != null
        val stravaDone get() = strava != null
        /** The intervals.icu activity id, when there is one to set steps on. */
        val icuId get() = icu?.takeIf { it != "duplicate" && it != NO_ICU }
        val waiting get() = (!icuDone && !icuFailed) || (icuDone && !stravaDone && !stravaFailed) || (icuId != null && intervalsPending)
    }

    init { dir.mkdirs() }

    fun add(id: String, fit: ByteArray, name: String, description: String, eventId: String?, rpe: Int?,
            intervals: List<Map<String, Any?>> = emptyList()): Ride {
        File(dir, "$id.fit").writeBytes(fit)
        deleteDraft(id)
        return Ride(id, name, description, eventId, rpe, intervals = intervals).also { save(it); prune() }
    }

    fun saveDraft(id: String, fit: ByteArray, name: String, description: String, eventId: String?,
                  intervals: List<Map<String, Any?>> = emptyList()) {
        val f = File(dir, "draft-$id.fit.tmp"); f.writeBytes(fit); f.renameTo(File(dir, "draft-$id.fit"))
        val j = File(dir, "draft-$id.meta.tmp")
        j.writeText(IcuJson.toJson(linkedMapOf("name" to name, "description" to description, "eventId" to eventId, "intervals" to intervals)))
        j.renameTo(File(dir, "draft-$id.meta"))
    }

    fun deleteDraft(id: String) { File(dir, "draft-$id.fit").delete(); File(dir, "draft-$id.meta").delete() }

    /** Queue the drafts left by a ride that never reached Save (app killed, tablet restarted). */
    fun promoteDrafts(exceptId: String? = null): Int = dir.listFiles { f -> f.name.startsWith("draft-") && f.name.endsWith(".meta") }
        .orEmpty().count { meta ->
            val id = meta.name.removePrefix("draft-").removeSuffix(".meta")
            if (id == exceptId) return@count false
            val fit = File(dir, "draft-$id.fit")
            if (!fit.exists()) { meta.delete(); return@count false }
            val m = json(meta.readText())
            add(id, fit.readBytes(), m["name"] as? String ?: "Ride", m["description"] as? String ?: "", m["eventId"] as? String, null, intervalsOf(m["intervals"]))
            true
        }

    fun rides(): List<Ride> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
        val m = json(f.readText())
        Ride(f.name.removeSuffix(".json"), m["name"] as? String ?: "Ride", m["description"] as? String ?: "",
            m["eventId"] as? String, (m["rpe"] as? Double)?.toInt(), m["icu"] as? String, m["strava"] as? String, m["error"] as? String,
            m["icuFailed"] == true, m["stravaFailed"] == true, intervalsOf(m["intervals"]),
            m["intervalsPending"] == true, (m["intervalTries"] as? Double)?.toInt() ?: 0)
    }

    private fun intervalsOf(v: Any?) =
        (v as? List<*>).orEmpty().mapNotNull { e -> (e as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }

    fun ride(id: String) = rides().firstOrNull { it.id == id }
    fun waiting() = rides().filter { it.waiting }
    fun fit(id: String) = File(dir, "$id.fit").readBytes()

    fun save(r: Ride) {
        val tmp = File(dir, "${r.id}.json.tmp")
        tmp.writeText(IcuJson.toJson(linkedMapOf("name" to r.name, "description" to r.description, "eventId" to r.eventId,
            "rpe" to r.rpe?.toDouble(), "icu" to r.icu, "strava" to r.strava, "error" to r.error,
            "icuFailed" to r.icuFailed, "stravaFailed" to r.stravaFailed, "intervals" to r.intervals,
            "intervalsPending" to r.intervalsPending, "intervalTries" to r.intervalTries.toDouble())))
        tmp.renameTo(File(dir, "${r.id}.json"))
    }

    /** Upload whatever is waiting. [strava] is null when Strava is not connected. Returns every ride's new state. */
    fun process(icu: IcuUploader?, strava: StravaAccount?): List<Ride> = rides().map { start ->
        var r = start
        if (!r.waiting) return@map r
        try {
            if (icu != null && !r.icuDone && !r.icuFailed) {
                val res = icu.upload(fit(r.id), "${r.id}.fit", r.name, r.description, r.eventId, r.rpe)
                // A duplicate (the first upload's answer was lost) has no id here to set steps on.
                r = r.copy(icu = res.id ?: "duplicate", error = res.warning, intervalsPending = res.id != null && r.intervals.isNotEmpty())
                save(r)
            } else if (icu == null && !r.icuDone && !r.icuFailed && strava != null && strava.configured) {
                // No intervals.icu for this rider, but Strava: go straight to Strava (review U1: it waited for ever).
                r = r.copy(icu = NO_ICU); save(r)
            }
            // The steps: retried on the next runs after no answer, a rate limit or a server error; a refusal is final.
            val icuId = r.icuId
            if (icu != null && icuId != null && r.intervalsPending) {
                val code = icu.putIntervals(icuId, r.intervals)
                val tries = r.intervalTries + 1
                val transient = code == null || code == 429 || code >= 500
                r = when {
                    code in 200..299 -> r.copy(intervalsPending = false, intervalTries = tries)
                    transient && tries < INTERVAL_TRIES -> r.copy(intervalTries = tries)
                    else -> r.copy(intervalsPending = false, intervalTries = tries,
                        error = listOfNotNull(r.error, intervalsWarning(code)).joinToString(" · "))
                }
                save(r)
            }
            if (r.icuDone && !r.stravaDone && !r.stravaFailed) {
                r = if (strava == null || !strava.configured)
                    r.copy(stravaFailed = true, error = listOfNotNull(r.error, "Strava not connected").joinToString(" · "))
                else r.copy(strava = strava.upload(fit(r.id), "gregario-${r.id}", r.name, r.description) ?: "duplicate")
                save(r)
            }
        } catch (e: Exception) {
            // A refused sign-in waits for the key or connection to be fixed instead of failing for good (review U3).
            val permanent = e is UploadException && !e.retry && !e.signIn
            r = r.copy(error = e.message ?: e.javaClass.simpleName,
                icuFailed = r.icuFailed || (permanent && !r.icuDone), stravaFailed = r.stravaFailed || (permanent && r.icuDone))
            save(r)
        }
        r
    }

    companion object {
        /** [Ride.icu] for a rider with no intervals.icu key whose ride went to Strava only. */
        const val NO_ICU = "none"
        /** Tries at setting a ride's steps on intervals.icu before giving up with a warning. */
        const val INTERVAL_TRIES = 5
    }

    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.dropLast(30).forEach { f ->
            f.delete(); File(dir, f.name.removeSuffix(".json") + ".fit").delete()
        }
    }
}
