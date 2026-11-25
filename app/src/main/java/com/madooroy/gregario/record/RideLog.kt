package com.madooroy.gregario.record

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Gregario's own ride log, so no logcat commands are needed before a ride: from picking a workout to leaving it,
 * every ERG trace line (and warnings) goes to `<dir>/<yyyyMMdd-HHmmss>-<workout>.log.gz`. Compressed (~1 MB an hour),
 * flushed every few seconds so a crash still leaves a readable file, newest [KEEP] rides kept.
 * On the tablet: /sdcard/Android/data/com.madooroy.gregario/files/ride-logs/
 */
class RideLog(private val clock: () -> Long = System::currentTimeMillis) {
    private var out: BufferedWriter? = null
    private var file: File? = null
    private var lines = 0
    private var flushedAt = 0L
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val current: File? @Synchronized get() = file

    /** Starts a new log for [workout] in [dir] (ending any open one, kept). */
    @Synchronized fun start(dir: File, workout: String) {
        stop(keep = true)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        val name = workout.replace(Regex("[^A-Za-z0-9+-]+"), "_").trim('_').take(40).ifEmpty { "ride" }
        val f = File(dir, "$stamp-$name.log.gz")
        out = BufferedWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(f), true), Charsets.UTF_8))
        file = f; lines = 0; flushedAt = clock()
        line("RideLog", "start $workout")
        prune(dir)
    }

    /** One line, when a log is open. */
    @Synchronized fun line(tag: String, message: String) {
        val w = out ?: return
        runCatching {
            val now = clock()
            w.write(time.format(Date(now))); w.write(" "); w.write(tag); w.write(": "); w.write(message); w.newLine()
            lines++
            if (now - flushedAt >= FLUSH_MS) { w.flush(); flushedAt = now }
        }.onFailure { runCatching { w.close() }; out = null }
    }

    /** Ends the log; [keep] false deletes it (nothing was ridden). */
    @Synchronized fun stop(keep: Boolean) {
        out?.let { runCatching { it.close() } }
        out = null
        if (!keep) file?.delete()
        file = null
    }

    private fun prune(dir: File) {
        val logs = dir.listFiles { f -> f.name.endsWith(".log.gz") }?.sortedByDescending { it.name } ?: return
        logs.drop(KEEP).forEach { it.delete() }
    }

    companion object {
        const val KEEP = 20
        const val FLUSH_MS = 5000L
    }
}
