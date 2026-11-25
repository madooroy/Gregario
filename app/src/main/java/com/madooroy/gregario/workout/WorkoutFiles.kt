package com.madooroy.gregario.workout

import java.io.File

/**
 * Workout files the rider opened with Gregario (from an email, Drive, a download), kept in app storage and offered
 * under "Your workouts". The file is kept as it came and read again each time, so an .erg file in watts follows the
 * rider's current FTP. The newest [keep] are kept.
 */
class WorkoutFiles(private val dir: File, private val keep: Int = 30) {
    data class Entry(val id: String, val workout: Workout?, val problem: String?)

    /** Checks the file can be read and ridden, then keeps it; returns the workout, or throws with the reason. */
    fun add(fileName: String, text: String, riderFtp: Int): Workout {
        require(text.length <= MAX_CHARS) { "File too large for a workout" }
        val w = WorkoutFile.parse(fileName, text, riderFtp)
        w.problem()?.let { throw IllegalArgumentException(it) }
        dir.mkdirs()
        val safe = safeName(fileName)
        // Written to a temporary name, then renamed: a list read at the same moment never sees half a file (review N7).
        val tmp = File(dir, ".$safe.tmp"); tmp.writeText(text)
        if (!tmp.renameTo(File(dir, safe))) { File(dir, safe).delete(); check(tmp.renameTo(File(dir, safe))) { "Couldn't save the file" } }
        File(dir, safe).setLastModified(System.currentTimeMillis())
        prune()
        return w
    }

    /** A file of this name is kept already (adding it again replaces it). */
    fun has(fileName: String) = File(dir, safeName(fileName)).isFile

    private fun safeName(fileName: String) =
        fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9 ._()&+-]"), "_").take(80).ifBlank { "workout.zwo" }

    /** Newest first. A file that no longer reads (e.g. watts with no FTP yet) is listed with its problem. */
    fun list(riderFtp: Int): List<Entry> = files().map { f ->
        runCatching { WorkoutFile.parse(f.name, f.readText(), riderFtp) }.fold(
            { w -> Entry(f.name, w, w.problem()) }, { e -> Entry(f.name, null, e.message ?: "Can't read this file") })
    }

    fun remove(id: String) { File(dir, id).takeIf { it.parentFile == dir }?.delete() }

    private fun files() = (dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()).sortedByDescending { it.lastModified() }

    private fun prune() = files().drop(keep).forEach { it.delete() }

    companion object { const val MAX_CHARS = 1_000_000 }
}

/**
 * Reads a workout file from any source, at most [WorkoutFiles.MAX_CHARS] bytes + 1: a large file (a video shared by
 * mistake) must not run the app, and the ride in it, out of memory (review I1). Throws if it is larger.
 */
fun readWorkoutText(input: java.io.InputStream): String {
    val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
    while (buf.size() <= WorkoutFiles.MAX_CHARS) {
        val n = input.read(chunk, 0, minOf(chunk.size, WorkoutFiles.MAX_CHARS + 1 - buf.size()))
        if (n < 0) break
        buf.write(chunk, 0, n)
    }
    require(buf.size() <= WorkoutFiles.MAX_CHARS) { "File too large for a workout" }
    return String(buf.toByteArray(), Charsets.UTF_8)
}
