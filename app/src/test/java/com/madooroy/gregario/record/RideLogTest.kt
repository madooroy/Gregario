package com.madooroy.gregario.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class RideLogTest {
    /** Reads what is there, as gzip -dc does on a file still being written (no end marker yet). */
    private fun read(f: File): List<String> {
        val bytes = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        val z = GZIPInputStream(f.inputStream())
        try { while (true) { val n = z.read(buf); if (n < 0) break; bytes.write(buf, 0, n) } } catch (_: java.io.EOFException) {}
        return bytes.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
    }

    @Test fun `writes a readable compressed log and keeps the newest 20`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ridelog-test-${System.nanoTime()}")
        var now = 1_795_270_000_000L
        val log = RideLog { now }
        repeat(23) { i ->
            now += 60_000
            log.start(dir, "VO2 27x30")
            log.line("Bike", "target from=165 to=362 ride=$i")
        }
        val f = log.current!!
        now += 6_000; log.line("Bike", "after flush")       // past FLUSH_MS: readable before stop
        val early = read(f)
        assertTrue(early.any { "after flush" in it })
        log.stop(keep = true)
        val lines = read(f)
        assertEquals("start VO2 27x30", lines[0].substringAfter("RideLog: "))
        assertTrue(lines[1].endsWith("Bike: target from=165 to=362 ride=22"))
        assertTrue(f.name.endsWith("-VO2_27x30.log.gz"))
        assertEquals(RideLog.KEEP, dir.listFiles()!!.size)        // the 3 oldest pruned
        dir.deleteRecursively()
    }

    @Test fun `a ride never started leaves no log`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ridelog-test2-${System.nanoTime()}")
        val log = RideLog()
        log.start(dir, "QuickTest"); log.line("Bike", "x"); log.stop(keep = false)
        assertEquals(0, dir.listFiles()!!.size)
        log.line("Bike", "ignored when closed")
        dir.deleteRecursively()
    }
}
