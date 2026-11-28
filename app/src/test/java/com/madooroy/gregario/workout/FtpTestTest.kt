package com.madooroy.gregario.workout

import com.madooroy.gregario.icu.IcuApi
import com.madooroy.gregario.record.RideRecorder
import com.madooroy.gregario.session.BikeStatus
import com.madooroy.gregario.session.ErgSink
import com.madooroy.gregario.session.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpTestTest {
    /** A "Zwift Ramp Test" as an intervals.icu library shows it: 5 min free, 1-min steps 29%→188%, 10 min at 22%. */
    private val zwiftRamp = Workout("Zwift Ramp Test",
        listOf(Step.Free(300)) +
        listOf(29, 35, 41, 47, 53, 59, 65, 71, 76, 82, 88, 94, 100, 106, 112, 118, 124, 129, 135, 141, 147, 153, 159, 165,
            171, 176, 182, 188).map { Step.Steady(60, it.toDouble()) } +
        Step.Steady(600, 22.0))

    /** Zwift's 20-minute FTP test, shortened warm-up: the effort is a 20-minute free ride. */
    private val zwiftFtp = Workout("Zwift FTP", listOf(Step.Ramp(1200, 30.0, 69.8), Step.Steady(300, 60.0), Step.Steady(300, 110.0),
        Step.Steady(600, 60.0), Step.Free(1200), Step.Ramp(600, 69.8, 30.0)))

    @Test fun `finds the ramp in a ramp test and the 20-minute test`() {
        assertEquals(FtpTest(1..28, false), FtpTest.of(zwiftRamp))
        assertEquals(FtpTest(null, true), FtpTest.of(zwiftFtp))
        // An ERG 20-min test (no free ride) is found by its name.
        assertEquals(FtpTest(null, true), FtpTest.of(Workout("My 20-min test", listOf(Step.Steady(600, 55.0), Step.Steady(1200, 93.0)))))
    }

    @Test fun `ordinary workouts and a test's warm-up are not tests`() {
        // review D2: the word FTP alone doesn't make a test; "FTP Test Ride" (library) still is one.
        assertNull(FtpTest.of(Workout("Sweet Spot 2x20 at 90% FTP", listOf(Step.Steady(600, 55.0), Step.Steady(1200, 90.0), Step.Steady(600, 50.0)))))
        assertEquals(FtpTest(null, true), FtpTest.of(Workout("FTP Test Ride", listOf(Step.Steady(600, 55.0), Step.Steady(1200, 95.0)))))
        assertNull(FtpTest.of(Workout("Sweet spot 3x12", listOf(Step.Steady(600, 55.0), Step.Steady(1200, 88.0), Step.Steady(600, 50.0)))))
        assertNull(FtpTest.of(Workout("Zwift FTP Warmup", listOf(Step.Ramp(1200, 30.0, 70.0), Step.Steady(300, 50.0)))))
        // A long free ride, or a 20-min free ride with nothing before it, is no test.
        assertNull(FtpTest.of(Workout("Free ride", listOf(Step.Free(3600)))))
        assertNull(FtpTest.of(Workout("Endurance + free", listOf(Step.Steady(600, 60.0), Step.Free(2400)))))
        assertNull(FtpTest.of(Workout("Free 20", listOf(Step.Free(1200), Step.Steady(300, 50.0)))))
        // A short ladder of 1-minute steps is not a ramp test.
        assertNull(FtpTest.of(Workout("Ladder", (1..5).map { Step.Steady(60, 80.0 + it * 5) })))
    }

    @Test fun `best 1 minute and best 20 minutes`() {
        val r = RideRecorder(zwiftRamp, 300)
        var ms = 1_000_000L
        repeat(60) { r.add(ms, 1.0, 200f, 90f, null, 0); ms += 1000 }       // warm-up
        repeat(60) { r.add(ms, 1.0, 400f, 90f, null, 5); ms += 1000 }       // a hard minute in the ramp
        repeat(30) { r.add(ms, 1.0, 500f, 90f, null, 6); ms += 1000 }       // half a minute harder
        assertEquals(450, r.bestAverage(60) { it in 1..28 })                // last 30 s of 400 + 30 s of 500
        assertEquals(500, r.bestAverage(30))
        assertNull(r.bestAverage(1200))                                     // not 20 minutes of riding
        assertEquals(2, r.stepsRidden { it in 1..28 })
    }

    private class Bike : ErgSink {
        override fun setErg(watts: Int) = true
        override fun release() = true
        override fun status() = BikeStatus(true, 0, null, "ok")
    }

    @Test fun `ramp ends when the cadence gives out, goes to the cooldown, and gives FTP from the best minute`() {
        val s = WorkoutSession(zwiftRamp, 300, Bike())
        var t = 0L
        fun go(n: Int, cad: Float, watts: (Int) -> Float) = repeat(n) {
            t += 1000; s.tick(t, cad > 20, watts(s.player.snapshot().targetWatts ?: 0), cad, 140f, wallMs = 1_000_000_000L + t)
        }
        s.tick(0, true, 0f, 90f, null); s.start()
        go(5, 90f) { 0f }                                   // countdown
        go(300 + 10 * 60, 90f) { it.toFloat() }             // free warm-up, then 10 ramp steps at target
        assertTrue(s.player.inRamp())
        assertEquals(11, s.player.snapshot().stepIndex)     // 10 minutes into the ramp: its 11th step
        go(2, 45f) { 200f }                                 // cadence collapsing: not yet
        assertTrue(s.player.inRamp())
        go(2, 45f) { 200f }                                 // 3 s under 55 rpm: ramp over
        assertEquals(29, s.player.snapshot().stepIndex)     // the cooldown
        assertEquals(WorkoutPlayer.State.Running, s.player.state)
        val r = s.testResult()!!
        // Best minute: the last full step, 82% (246 W); FTP = 75% of it. 11 ramp steps ridden (the 11th in part).
        assertEquals(246, r.best1)
        assertEquals(185, r.ftpFromRamp)
        assertEquals(11, r.rampStepsDone)
        assertNotNull(s.notice)
        assertTrue(r.lines().first().startsWith("Ramp test: best 1 min 246 W → FTP 185 W"))
        // The jump to the cooldown is not recorded as riding: 5 min + 10 min + 4 s ridden.
        assertEquals(904.0, s.recorder.seconds, 1.5)
    }

    @Test fun `Done ends the ramp at once, and not outside it`() {
        val s = WorkoutSession(zwiftRamp, 300, Bike())
        var t = 0L
        s.tick(0, true, 0f, 90f, null); s.start()
        repeat(5 + 100) { t += 1000; s.tick(t, true, 150f, 90f, null) }
        assertEquals(false, s.endRamp())                    // still in the free warm-up
        repeat(300) { t += 1000; s.tick(t, true, 250f, 90f, null) }
        assertTrue(s.endRamp())
        assertEquals(29, s.player.snapshot().stepIndex)
        assertEquals(false, s.endRamp())                    // already in the cooldown
    }

    @Test fun `no warm-up extension inside the ramp`() {
        val p = WorkoutPlayer(zwiftRamp, 300)
        p.start(0); p.tick(0, true)
        for (i in 1..310) p.tick(i * 1000L, true)            // into the ramp's first step
        assertEquals(false, p.inWarmup())
        p.extendWarmup(60)
        assertEquals(60, p.workout.steps[1].seconds)
    }

    @Test fun `Set FTP writes indoor_ftp to the ride sport settings`() {
        val athlete = """{"sportSettings":[{"id":11,"types":["Run"],"ftp":250},{"id":1646371,"types":["Ride","VirtualRide"],"ftp":305,"indoor_ftp":329}]}"""
        var putUrl = ""; var putBody = ""
        val api = IcuApi("k", http = { _, _ -> athlete }, put = { url, _, json ->
            putUrl = url; putBody = json; """{"id":1646371,"types":["Ride","VirtualRide"],"ftp":305,"indoor_ftp":312}""" })
        assertEquals(312, api.setIndoorFtp(312))
        assertEquals("https://intervals.icu/api/v1/athlete/0/sport-settings/1646371", putUrl)
        assertEquals("""{"indoor_ftp":312}""", putBody)
    }

    @Test fun `Set FTP reads back when the reply has no FTP, and reports a mismatch`() {
        var stored = 329
        val api = IcuApi("k", http = { _, _ -> """{"sportSettings":[{"id":7,"types":["VirtualRide"],"ftp":300,"indoor_ftp":$stored}]}""" },
            put = { _, _, _ -> stored = 312; "{}" })
        assertEquals(312, api.setIndoorFtp(312))
        val stuck = IcuApi("k", http = { _, _ -> """{"sportSettings":[{"id":7,"types":["VirtualRide"],"indoor_ftp":329}]}""" },
            put = { _, _, _ -> "{}" })
        assertTrue(runCatching { stuck.setIndoorFtp(312) }.exceptionOrNull()!!.message!!.contains("329"))
    }
}
