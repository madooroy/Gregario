package com.madooroy.gregario.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkoutFilesTest {
    private val mrc = """
        [COURSE HEADER]
        VERSION = 2
        UNITS = ENGLISH
        DESCRIPTION = Over-unders
        MINUTES PERCENT
        [END COURSE HEADER]
        [COURSE DATA]
        0.00	50
        5.00	75
        5.00	95
        7.00	95
        7.00	105
        8.00	105
        [END COURSE DATA]
    """.trimIndent()

    private val erg = """
        [COURSE HEADER]
        FTP = 250
        MINUTES WATTS
        [END COURSE HEADER]
        [COURSE DATA]
        0	125
        10	125
        10	250
        15	250
        [END COURSE DATA]
    """.trimIndent()

    @Test fun mrcPointsBecomeRampsAndSteps() {
        val w = WorkoutFile.parse("over-unders.mrc", mrc, 300)
        assertEquals("Over-unders", w.name)
        assertEquals(8 * 60, w.totalSeconds)
        assertEquals(3, w.steps.size)
        assertTrue(w.steps[0] is Step.Ramp)
        assertEquals(62.5, w.pctAt(150.0), .01)     // halfway up the 50→75 ramp
        assertEquals(105.0, w.pctAt(7.5 * 60), .01)
    }

    @Test fun ergWattsUseTheFilesFtpOrTheRiders() {
        assertEquals(100.0, WorkoutFile.parse("x.erg", erg, 300).pctAt(12 * 60.0), .01)   // 250 W of FTP 250
        val noFtp = erg.replace("FTP = 250\n", "")
        assertEquals(125.0, WorkoutFile.parse("x.erg", noFtp, 200).pctAt(12 * 60.0), .01)
        assertEquals("x", WorkoutFile.parse("x.erg", noFtp, 200).name)
    }

    @Test fun zwoRangesMaxEffortAndUnknownSteps() {
        val zwo = """<workout_file><name>Mixed</name><workout>
            <SteadyState Duration="300" PowerLow="0.95" PowerHigh="1.05"/>
            <MaxEffort Duration="20"/>
            <Freeride Duration="60"/>
            </workout></workout_file>"""
        val w = WorkoutFile.parse("mixed.zwo", zwo, 300)
        assertEquals(95.0, (w.steps[0] as Step.Steady).pct, .01)
        assertEquals(105.0, (w.steps[0] as Step.Steady).top!!, .01)
        assertTrue(w.steps[1] is Step.Free)
        assertEquals(380, w.totalSeconds)
        val bad = zwo.replace("<MaxEffort Duration=\"20\"/>", "<SolidState Duration=\"20\"/>")
        assertTrue(runCatching { WorkoutFile.parse("bad.zwo", bad, 300) }.exceptionOrNull()!!.message!!.contains("SolidState"))
        val dtd = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"boom\">]>" + zwo
        assertTrue(runCatching { WorkoutFile.parse("dtd.zwo", dtd, 300) }.isFailure)
    }

    @Test fun keptFilesAreListedNewestFirstAndRemovable() {
        val dir = File.createTempFile("wfiles", "").also { it.delete(); it.mkdirs() }
        val files = WorkoutFiles(dir, keep = 2)
        files.add("a.mrc", mrc, 300)
        Thread.sleep(20)
        files.add("b.erg", erg, 300)
        Thread.sleep(20)
        files.add("c.mrc", mrc, 300)
        val l = files.list(300)
        assertEquals(listOf("c.mrc", "b.erg"), l.map { it.id })
        assertNull(l[0].problem)
        files.remove("c.mrc")
        assertEquals(listOf("b.erg"), files.list(300).map { it.id })
        assertTrue(runCatching { files.add("n.txt", "hello", 300) }.isFailure)
        dir.deleteRecursively()
    }

    @Test fun `a file of huge repeats is refused while it is read, not after (review N1)`() {
        val line = "<IntervalsT Repeat=\"100\" OnDuration=\"30\" OffDuration=\"30\" OnPower=\"1.1\" OffPower=\"0.5\"/>\n"
        val zwo = "<workout_file><name>x</name><workout>\n" + line.repeat(9000) + "</workout></workout_file>"
        val t0 = System.currentTimeMillis()
        val e = runCatching { WorkoutFile.parse("x.zwo", zwo, 300) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("More than"))
        assertTrue(System.currentTimeMillis() - t0 < 2000)
    }

    @Test fun `durations out of range are refused (review D4)`() {
        assertTrue(Workout("x", listOf(Step.Steady(5 * 3600, 50.0))).problem()!!.contains("out of range"))
        assertTrue(Workout("x", List(4) { Step.Steady(4 * 3600, 50.0) }).problem()!!.contains("12 hours"))
    }
}
