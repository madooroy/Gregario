package com.madooroy.gregario.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * "New version available": asks GitHub for Gregario's newest release (test builds included) and compares its tag
 * with this build's version. Sends nothing about the rider; GitHub sees only that a tablet asked.
 */
object UpdateCheck {
    const val RELEASES = "https://github.com/madooroy/Gregario/releases"
    private const val API = "https://api.github.com/repos/madooroy/Gregario/releases?per_page=10"

    data class Release(val version: String, val url: String, val test: Boolean)

    /** True when [tag] (v0.1.2, 0.1.2-test…) is a later version than [current] (0.1.0). */
    fun newer(current: String, tag: String): Boolean {
        val a = numbers(current); val b = numbers(tag)
        if (b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
            .split('.').mapNotNull { it.toIntOrNull() }

    /** The newest published (non-draft) release in GitHub's JSON list, or null. */
    fun parse(json: String): Release? = runCatching {
        val list = org.json.JSONArray(json)
        (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { !it.optBoolean("draft") }?.let {
            Release(it.getString("tag_name").removePrefix("v"), it.optString("html_url", RELEASES), it.optBoolean("prerelease"))
        }
    }.getOrNull()

    /** Blocking: call off the main thread. Null when offline or GitHub doesn't answer. */
    fun fetch(): Release? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try { if (c.responseCode == 200) parse(c.inputStream.bufferedReader().readText()) else null } finally { c.disconnect() }
    }.getOrNull()
}
