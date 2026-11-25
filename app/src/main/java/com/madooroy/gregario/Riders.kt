package com.madooroy.gregario

/** One person who rides this bike. Their intervals.icu key, FTP, Strava and workout files are theirs alone. */
data class Rider(val id: String, val name: String)

/**
 * Riders sharing one bike. The first rider has id "" and keeps the original setting keys, folders and
 * files, so a one-rider tablet is exactly as before. Every other rider's settings are stored under "<key>@<id>" and
 * their rides and workout files in "<folder>-<id>".
 */
object Riders {
    const val FIRST = ""
    private const val MAX_NAME = 24

    fun key(base: String, rider: String) = if (rider == FIRST) base else "$base@$rider"
    fun dirName(base: String, rider: String) = if (rider == FIRST) base else "$base-$rider"

    fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME)

    /** The other riders (not the first), one per line: id, tab, name. */
    fun encode(riders: List<Rider>) = riders.filter { it.id != FIRST }.joinToString("\n") { "${it.id}\t${clean(it.name)}" }

    fun decode(saved: String?, firstName: String): List<Rider> =
        listOf(Rider(FIRST, clean(firstName).ifBlank { "Rider 1" })) + saved.orEmpty().lines().mapNotNull { line ->
            val p = line.split('\t', limit = 2)
            if (p.size == 2 && p[0].isNotBlank() && p[0].all { it.isLetterOrDigit() }) Rider(p[0], clean(p[1]).ifBlank { "Rider" }) else null
        }.distinctBy { it.id }

    /** A new id: letters and digits only (it goes into setting keys and folder names). */
    fun newId(existing: Collection<String>, now: Long): String {
        var n = now
        while (true) { val id = "r" + java.lang.Long.toString(n, 36); if (id !in existing) return id; n++ }
    }

    /** How long a "who's riding?" answer lasts without a ride. */
    const val CONFIRM_MS = 2 * 60 * 60 * 1000L

    /**
     * Ask "who's riding?" first: only with two or more riders set up, and then when nobody has said yet, a ride has
     * finished since they did, or it was over two hours ago (one person after another the same day).
     */
    fun needsConfirm(count: Int, confirmedAt: Long, lastRideEndAt: Long, now: Long) =
        count > 1 && (confirmedAt <= 0 || lastRideEndAt >= confirmedAt || now - confirmedAt > CONFIRM_MS)
}
