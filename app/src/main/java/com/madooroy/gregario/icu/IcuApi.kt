package com.madooroy.gregario.icu

import com.madooroy.gregario.workout.IcuParser
import com.madooroy.gregario.workout.MiniJson
import com.madooroy.gregario.workout.Workout
import java.net.HttpURLConnection
import java.net.URL

/** A workout planned on the intervals.icu calendar. [json] is the raw event, kept so it can be cached. */
data class PlannedWorkout(val id: String, val name: String, val date: String, val workout: Workout, val json: String,
    /** Why it cannot be ridden here (shown in the list instead of a Start), or null. */
    val problem: String? = null,
    /** intervals.icu has already paired a ride with it (done today): not offered again. */
    val done: Boolean = false)

/** Fetches from intervals.icu. Plain JVM networking so it can be tested off the bike; call from a background thread. */
class IcuApi(
    private val apiKey: String,
    private val baseUrl: String = "https://intervals.icu/api/v1",
    private val http: (url: String, auth: String) -> String = ::get,
    private val put: (url: String, auth: String, json: String) -> String = ::putJson,
) {
    private val auth = "Basic " + base64("API_KEY:$apiKey")

    /**
     * Planned rides on [date] (yyyy-MM-dd, the athlete's local date). A workout that cannot be ridden here
     * is still listed, with its [PlannedWorkout.problem], so one bad step does not hide the rest of the day.
     */
    fun plannedWorkouts(date: String): List<PlannedWorkout> {
        val list = MiniJson.parse(http("$baseUrl/athlete/0/events?oldest=$date&newest=$date&category=WORKOUT", auth))
        return IcuJson.events(list).mapNotNull { (id, ev) ->
            // Already ridden: intervals.icu pairs the ride with the event (one card on the calendar, event still there).
            if ((MiniJson.parse(ev) as? Map<*, *>)?.get("paired_activity_id") != null) {
                val name = (MiniJson.parse(ev) as? Map<*, *>)?.get("name") as? String ?: "Workout"
                return@mapNotNull PlannedWorkout(id, name, date, Workout(name, emptyList()), ev, done = true)
            }
            // The list may leave out workout_doc; fetch the single event when it does.
            val full = if (ev.contains("\"workout_doc\"")) ev else http("$baseUrl/athlete/0/events/$id", auth)
            try {
                val w = IcuParser.parseEvent(full)
                when {
                    w.steps.isNotEmpty() -> PlannedWorkout(id, w.name, date, w, full, w.problem())
                    // Workouts synced in from other apps can have a workout_doc with no steps: say so rather than hide them.
                    (MiniJson.parse(full) as? Map<*, *>)?.containsKey("workout_doc") == true -> PlannedWorkout(id, w.name, date, w, full,
                        "No steps in intervals.icu (synced from another app?) · put the library version on the calendar")
                    else -> null                                 // unexpected reply: skip
                }
            } catch (e: IllegalArgumentException) {
                val name = (MiniJson.parse(full) as? Map<*, *>)?.get("name") as? String ?: "Workout"
                PlannedWorkout(id, name, date, Workout(name, emptyList()), full, e.message ?: "Can't read this workout")
            }
        }
    }

    /** Indoor ride FTP from the athlete's sport settings (VirtualRide, else Ride; indoor FTP first). */
    fun rideFtp(): Int? = IcuJson.rideFtp(MiniJson.parse(http("$baseUrl/athlete/0", auth)))

    /**
     * Set the indoor FTP in the same sport settings [rideFtp] reads (VirtualRide, else Ride), after a test,
     * when the rider taps Set FTP. Returns the indoor FTP intervals.icu now holds.
     */
    fun setIndoorFtp(watts: Int): Int {
        require(watts in 1..2000) { "FTP $watts W" }
        val id = IcuJson.rideSettingsId(MiniJson.parse(http("$baseUrl/athlete/0", auth)))
            ?: throw IllegalStateException("No cycling sport settings in intervals.icu")
        val reply = MiniJson.parse(put("$baseUrl/athlete/0/sport-settings/$id", auth, "{\"indoor_ftp\":$watts}"))
        // The reply should be the updated settings; if it isn't, read them back.
        val now = ((reply as? Map<*, *>)?.get("indoor_ftp") as? Double)?.toInt()
            ?: IcuJson.rideIndoorFtp(MiniJson.parse(http("$baseUrl/athlete/0", auth)))
        if (now != watts) throw IllegalStateException("intervals.icu kept indoor FTP ${now ?: "?"} W")
        return now
    }

    companion object {
        fun get(url: String, auth: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Authorization", auth); c.setRequestProperty("Accept", "application/json")
            try {
                val code = c.responseCode
                if (code !in 200..299) throw IcuException(code, c.errorStream?.bufferedReader()?.readText().orEmpty().take(200))
                return c.inputStream.bufferedReader().readText()
            } finally { c.disconnect() }
        }

        fun putJson(url: String, auth: String, json: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000; c.requestMethod = "PUT"; c.doOutput = true
            c.setRequestProperty("Authorization", auth); c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) throw IcuException(code, c.errorStream?.bufferedReader()?.readText().orEmpty().take(200))
                return c.inputStream.bufferedReader().readText()
            } finally { c.disconnect() }
        }

        /** Base64 without java.util.Base64 (needs API 26) or android.util (not on the JVM). */
        fun base64(s: String): String {
            val t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val b = s.toByteArray(Charsets.UTF_8); val out = StringBuilder()
            var i = 0
            while (i < b.size) {
                val n = (b[i].toInt() and 255 shl 16) or ((if (i + 1 < b.size) b[i + 1].toInt() and 255 else 0) shl 8) or
                        (if (i + 2 < b.size) b[i + 2].toInt() and 255 else 0)
                out.append(t[n shr 18 and 63]).append(t[n shr 12 and 63])
                out.append(if (i + 1 < b.size) t[n shr 6 and 63] else '=').append(if (i + 2 < b.size) t[n and 63] else '=')
                i += 3
            }
            return out.toString()
        }
    }
}

class IcuException(val code: Int, body: String) : Exception(
    when (code) { 401, 403 -> "intervals.icu refused the API key ($code)"; else -> "intervals.icu error $code: $body" })

/** JSON helpers kept separate so they can be tested on saved responses. */
object IcuJson {
    @Suppress("UNCHECKED_CAST")
    fun events(list: Any?): List<Pair<String, String>> =
        (list as? List<Any?>).orEmpty().mapNotNull { e ->
            val m = e as? Map<String, Any?> ?: return@mapNotNull null
            if (m["category"] != null && m["category"] != "WORKOUT") return@mapNotNull null
            // Rides only: a planned row or run with %FTP steps would otherwise run at bike FTP. VERIFY the field name.
            if (m["type"] != null && m["type"] !in RIDE_TYPES) return@mapNotNull null
            val id = (m["id"] as? Double)?.toLong()?.toString() ?: m["id"]?.toString() ?: return@mapNotNull null
            id to toJson(m)
        }

    @Suppress("UNCHECKED_CAST")
    private fun rideSettings(athlete: Any?): Map<String, Any?>? {
        val settings = ((athlete as? Map<String, Any?>)?.get("sportSettings") as? List<Any?>).orEmpty()
            .mapNotNull { it as? Map<String, Any?> }
        fun types(m: Map<String, Any?>) = (m["types"] as? List<Any?>).orEmpty().map { it.toString() }
        return settings.firstOrNull { "VirtualRide" in types(it) } ?: settings.firstOrNull { "Ride" in types(it) }
    }

    /** The id of the sport settings [rideFtp] reads, for updating them. */
    fun rideSettingsId(athlete: Any?): String? =
        rideSettings(athlete)?.get("id")?.let { (it as? Double)?.toLong()?.toString() ?: it.toString() }

    fun rideIndoorFtp(athlete: Any?): Int? = (rideSettings(athlete)?.get("indoor_ftp") as? Double)?.toInt()

    fun rideFtp(athlete: Any?): Int? {
        val s = rideSettings(athlete) ?: return null
        return ((s["indoor_ftp"] as? Double)?.takeIf { it > 0 } ?: (s["ftp"] as? Double))?.toInt()?.takeIf { it > 0 }
    }

    val RIDE_TYPES = setOf("Ride", "VirtualRide", "GravelRide", "MountainBikeRide")

    /** Re-serialise a parsed value (used to cache a single event). */
    fun toJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in v) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Int, is Long -> v.toString()
        is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
        else -> toJson(v.toString())
    }
}
