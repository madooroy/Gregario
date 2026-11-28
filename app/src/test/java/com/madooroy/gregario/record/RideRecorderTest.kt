package com.madooroy.gregario.record

import com.madooroy.gregario.session.BikeStatus
import com.madooroy.gregario.session.ErgSink
import com.madooroy.gregario.session.WorkoutSession
import com.madooroy.gregario.workout.Step
import com.madooroy.gregario.workout.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RideRecorderTest {
    private val bike = object : ErgSink {
        override fun setErg(watts: Int) = true
        override fun release() = true
        override fun status() = BikeStatus(true, 0, null, "")
    }

    /** A 3-step ride through the real session, with a 20-s pause; written to /tmp for the Python FIT check. */
    @Test fun `ride to FIT file`() {
        val w = Workout("Test Ride", listOf(Step.Steady(60, 50.0), Step.Steady(60, 110.0), Step.Steady(60, 50.0)))
        val s = WorkoutSession(w, 300, bike)
        var t = 0L; val wall0 = 1_795_150_000_000L
        s.tick(t, true, 0f, 90f, null, wall0); s.player.start(0)
        fun ride(n: Int, watts: Float, pedal: Boolean = true) = repeat(n) {
            t += 1000
            s.tick(t, pedal, if (pedal) watts else 0f, if (pedal) 90f else 0f, 140f, wall0 + t, if (pedal) 20f else 0f, if (watts > 200f) 55 else 44,
                RideRecorder.Erg(if (watts > 200f) 330 else 150, if (watts > 200f) 55 else 44, 3, if (watts > 200f) 2 else 0))
        }
        ride(60, 150f); ride(30, 330f); ride(20, 0f, pedal = false); s.resume(); ride(2, 330f); ride(31, 330f); ride(60, 150f)
        val r = s.recorder
        assertEquals(180.0, r.seconds, 0.01)
        val tot = r.totals()
        assertTrue(tot.avgPower in 205..215); assertEquals(330, tot.maxPower); assertEquals(140, tot.avgHr)
        val fit = r.fit()
        assertEquals(".FIT", String(fit, 8, 4))
        assertEquals(0, FitWriter.crc(fit))          // the file's CRC checks out over the whole file
        File("/tmp/gregario-test-ride.fit").writeBytes(fit)
        assertTrue(fit.size > 180 * 10)
        val sum = r.intervalSummary()
        println(sum); assertTrue(sum.startsWith("Intervals:\n1. "))
        assertTrue(sum.contains("@ 330 W → 3")); assertTrue(sum.contains("140 bpm")); assertEquals(4, sum.lines().size)
        println(sum)
    }

    @Test fun `two ticks in one second make one record`() {
        val r = RideRecorder(Workout("x", listOf(Step.Steady(60, 50.0))), 300)
        r.add(10_000, 1.0, 200f, 90f, null, 0); r.add(11_000, 0.7, 300f, 90f, null, 0); r.add(11_700, 0.3, 100f, 90f, null, 0)
        assertEquals(2, r.size); assertEquals(2.0, r.seconds, 0.001)
        assertEquals(240, r.totals().maxPower)          // 0.7 s at 300 + 0.3 s at 100 = 240 W for that second
    }

    /** Nov 25: the steps as intervals.icu intervals, the way Marc set them by hand on TrainerRoad rides. */
    @Test fun `work steps become intervals, ramps and Z1 left out, similar Z2 steps merged`() {
        val w = Workout("T", listOf(
            Step.Ramp(60, 45.0, 60.0),                              // warm-up ramp: out
            Step.Steady(60, 65.0), Step.Steady(60, 68.0, "Hold"),   // Z2, 3% apart: one interval
            Step.Steady(30, 110.0, "On"), Step.Steady(30, 50.0, "Off"), Step.Steady(30, 110.0, "On"),   // 50%: out
            Step.Ramp(30, 60.0, 80.0),                              // ramp between efforts: out
            Step.Steady(60, 80.0), Step.Steady(60, 72.0),          // Z3 then Z2: two
            Step.Steady(5, 100.0),                                  // too short: out
            Step.Ramp(60, 60.0, 45.0)))                             // cool-down: out
        val r = RideRecorder(w, 300)
        var t = 1_000_000L; var step = 0
        for (st in w.steps) { repeat(st.seconds) { t += 1000; r.add(t, 1.0, 200f, 90f, null, step) }; step++ }
        val iv = r.icuIntervals()
        fun row(i: Int) = Triple(iv[i]["start_index"], iv[i]["end_index"], iv[i]["label"])
        assertEquals(5, iv.size)
        assertEquals(Triple(60, 180, "Z2 · 65–68%"), row(0))
        assertEquals(Triple(180, 210, "On"), row(1))
        assertEquals(Triple(240, 270, "On"), row(2))
        assertEquals(Triple(300, 360, "Z3 · 80%"), row(3))
        assertEquals(Triple(360, 420, "Z2 · 72%"), row(4))
        assertEquals("[{\"start_index\":60,\"end_index\":180,\"label\":\"Z2 · 65–68%\"}]",
            com.madooroy.gregario.icu.IcuJson.toJson(iv.take(1)))
    }

    @Test fun `a pause doesn't move the steps - records are seconds ridden`() {
        val w = Workout("T", listOf(Step.Steady(60, 50.0), Step.Steady(60, 90.0), Step.Steady(60, 50.0)))
        val r = RideRecorder(w, 300)
        var t = 1_000_000L
        repeat(60) { t += 1000; r.add(t, 1.0, 150f, 90f, null, 0) }
        repeat(30) { t += 1000; r.add(t, 1.0, 270f, 90f, null, 1) }
        t += 45_000                                                  // paused 45 s mid-step
        repeat(30) { t += 1000; r.add(t, 1.0, 270f, 90f, null, 1) }
        repeat(60) { t += 1000; r.add(t, 1.0, 150f, 90f, null, 2) }
        val iv = r.icuIntervals().single()
        assertEquals(60, iv["start_index"]); assertEquals(120, iv["end_index"])
    }

    /** Review: merging is for Z2 only, spans at most 5%, and never across a step that wasn't ridden. */
    @Test fun `Z2 merges don't chain, and a skipped step splits them`() {
        val w = Workout("T", listOf(Step.Steady(60, 60.0), Step.Steady(60, 64.0), Step.Steady(60, 68.0), Step.Steady(60, 72.0),
            Step.Steady(60, 95.0), Step.Steady(60, 98.0),           // Z4 neighbours 3% apart: still two
            Step.Steady(60, 65.0), Step.Steady(60, 66.0), Step.Steady(60, 67.0)))   // the middle one skipped
        val r = RideRecorder(w, 300)
        var t = 1_000_000L
        for ((i, st) in w.steps.withIndex()) if (i != 7) repeat(st.seconds) { t += 1000; r.add(t, 1.0, 200f, 90f, null, i) }
        val iv = r.icuIntervals().map { it["start_index"] to it["end_index"] }
        assertEquals(listOf(0 to 120, 120 to 240, 240 to 300, 300 to 360, 360 to 420, 420 to 480), iv)
    }

    /** NP counts seconds, not samples: a late tick standing for 5 s weighs 5 s in the 30-s window (Codex review). */
    @Test fun `normalized power counts seconds when a tick is late`() {
        val w = Workout("NP", listOf(Step.Steady(600, 60.0)))
        fun np(samples: List<Pair<Double, Float>>): Int {
            val r = RideRecorder(w, 300); var ms = 1_795_150_000_000L
            samples.forEach { (secs, watts) -> ms += (secs * 1000).toLong(); r.add(ms, secs, watts, 90f, null, 0) }
            return r.totals().np
        }
        // 60 s at 300 W then 60 s at 100 W, once in 1-s ticks and once with the 300 W part in 5-s ticks.
        val even = List(60) { 1.0 to 300f } + List(60) { 1.0 to 100f }
        val late = List(12) { 5.0 to 300f } + List(60) { 1.0 to 100f }
        assertEquals(np(even), np(late))
        // Steady power: NP is that power, however the ticks fall.
        assertEquals(200, np(List(40) { 1.0 to 200f } + List(10) { 3.0 to 200f } + List(20) { 1.0 to 200f }))
    }
}
