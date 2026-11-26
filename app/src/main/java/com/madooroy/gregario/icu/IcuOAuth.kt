package com.madooroy.gregario.icu

import com.madooroy.gregario.upload.Http
import com.madooroy.gregario.workout.MiniJson
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How Gregario talks to intervals.icu for a rider: an OAuth sign-in (stored as [OAUTH] + token) or a pasted API key.
 * Plain JVM so it can be tested off the bike.
 */
object IcuAuth {
    const val OAUTH = "oauth:"
    fun header(credential: String): String =
        if (credential.startsWith(OAUTH)) "Bearer " + credential.removePrefix(OAUTH)
        else "Basic " + IcuApi.base64("API_KEY:$credential")
}

class IcuSignInException(message: String) : Exception(message)

/**
 * "Connect intervals.icu": Gregario's own intervals.icu app (2036), a public client with PKCE, so there is no
 * secret in the app. The rider signs in on intervals.icu's page (shown inside Gregario, since the Peloton has no
 * browser), approves, and intervals.icu redirects to [REDIRECT] with a code that is exchanged here for a token.
 * intervals.icu tokens don't expire and have no refresh token; signing in again replaces the old one.
 */
class IcuOAuth(private val http: Http = Http.JVM, private val random: SecureRandom = SecureRandom()) {
    /** One sign-in attempt: the PKCE verifier and the state that must come back unchanged. */
    data class Pending(val verifier: String, val state: String)
    /** What intervals.icu granted: the token, whose account it is, and the scopes. */
    data class Grant(val token: String, val athlete: String, val scope: String)

    fun start() = Pending(randomText(64), randomText(24))

    fun authorizeUrl(p: Pending) = AUTHORIZE + "?client_id=" + CLIENT_ID + "&redirect_uri=" + enc(REDIRECT) +
        "&response_type=code&scope=" + enc(SCOPE) + "&state=" + enc(p.state) +
        "&code_challenge=" + challenge(p.verifier) + "&code_challenge_method=S256"

    /** Exchange the redirect for a token. Throws [IcuSignInException] with a message for the rider. */
    fun finish(redirectUrl: String, p: Pending): Grant {
        val q = query(redirectUrl)
        q["error"]?.let { throw IcuSignInException(if (it == "access_denied") "Not connected: you didn't approve Gregario" else "intervals.icu said: $it") }
        if (q["state"] != p.state) throw IcuSignInException("That sign-in didn't come from this attempt: tap Connect again")
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: throw IcuSignInException("intervals.icu sent no code: tap Connect again")
        val r = http.send("POST", TOKEN, emptyMap(), form("client_id" to CLIENT_ID, "code" to code, "code_verifier" to p.verifier,
            "grant_type" to "authorization_code", "redirect_uri" to REDIRECT), "application/x-www-form-urlencoded")
        if (r.code !in 200..299) throw IcuSignInException("intervals.icu refused the sign-in (HTTP ${r.code}): ${r.text.take(150)}")
        val a = runCatching { MiniJson.parse(r.text) as? Map<*, *> }.getOrNull() ?: emptyMap<Any, Any>()
        val token = (a["access_token"] as? String)?.takeIf { it.isNotBlank() } ?: throw IcuSignInException("intervals.icu sent no token")
        val athlete = (a["athlete"] as? Map<*, *>)?.get("name") as? String
        return Grant(token, athlete?.takeIf { it.isNotBlank() } ?: "your account", a["scope"] as? String ?: "")
    }

    private fun randomText(n: Int) = String(CharArray(n) { VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)] })

    companion object {
        const val CLIENT_ID = "2036"
        const val AUTHORIZE = "https://intervals.icu/oauth/authorize"
        const val TOKEN = "https://intervals.icu/api/oauth/token"
        const val REDIRECT = "http://localhost/gregario/intervals"
        /** Today's workouts and pairing the ride (CALENDAR), uploads and intervals (ACTIVITY), FTP read and Set FTP (SETTINGS). */
        const val SCOPE = "ACTIVITY:WRITE,CALENDAR:WRITE,SETTINGS:WRITE"
        private const val VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** Is this where intervals.icu sends the rider back (any port, localhost or 127.0.0.1)? */
        fun isRedirect(url: String): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            return u.scheme == "http" && (u.host == "localhost" || u.host == "127.0.0.1") && u.path == "/gregario/intervals"
        }

        /** BASE64URL(SHA-256(verifier)), no padding. */
        fun challenge(verifier: String) = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun base64Url(b: ByteArray): String {
            val t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder()
            var i = 0
            while (i < b.size) {
                val n = (b[i].toInt() and 0xff shl 16) or ((if (i + 1 < b.size) b[i + 1].toInt() and 0xff else 0) shl 8) or
                    (if (i + 2 < b.size) b[i + 2].toInt() and 0xff else 0)
                sb.append(t[n shr 18 and 63]).append(t[n shr 12 and 63])
                if (i + 1 < b.size) sb.append(t[n shr 6 and 63])
                if (i + 2 < b.size) sb.append(t[n and 63])
                i += 3
            }
            return sb.toString()
        }

        private fun query(url: String): Map<String, String> {
            val raw = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyMap()
            return raw.split("&").mapNotNull { kv ->
                val i = kv.indexOf('='); if (i <= 0) null
                else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
        }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }.toByteArray()
    }
}
