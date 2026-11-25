package com.madooroy.gregario.session

import com.madooroy.gregario.workout.Step
import com.madooroy.gregario.workout.Workout
import com.madooroy.gregario.workout.WorkoutPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSessionTest {
    /** A stand-in bike: ERG target, a mode, refuses stale data or < 25 W. */
    private class FakeBike : ErgSink {
        val sent = mutableListOf<Int>(); var releases = 0; var fresh = true
        var erg = false; var target = 0; var external: String? = null; var message = "Ready"; var knobbed = false
        override fun setErg(watts: Int): Boolean {
            if (!fresh || watts !in 25..1000) return false
            sent += watts; erg = true; target = watts; external = null; knobbed = false; return true
        }
        override fun release(): Boolean { if (!fresh) return false; releases++; erg = false; return true }
        override fun status() = BikeStatus(erg, target, external, message, knobbed)
        fun knob() { erg = false; message = "Resistance changed manually"; knobbed = true }
        fun lostData() { erg = false; message = "Bike+ data is stale"; knobbed = false }
        fun shift(d: Int) { target += d }
    }
    private val w = Workout("T", listOf(Step.Steady(10, 50.0), Step.Steady(10, 110.0), Step.Steady(10, 50.0)))

    private class Ride(val s: WorkoutSession) {
        var t = 0L
        fun go(n: Int, pedal: Boolean = true) = (1..n).map { t += 1000; s.tick(t, pedal, if (pedal) 200f else 0f, if (pedal) 90f else 0f, 120f) }.last()
    }

    @Test fun `sends each target once, lets go when the pedals stop and at the end`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.start()
        assertEquals(5, s.player.snapshot().countdown)                // 5 s to start Peloton on the same beat
        r.go(5)                                                       // countdown
        r.go(10)
        assertEquals(listOf(165, 362), bike.sent)                     // no re-sends of the same target
        r.go(1, pedal = false)
        assertEquals(1, bike.releases)                                // let go at once
        r.go(2, pedal = false); assertEquals(WorkoutPlayer.State.Paused, s.player.state)
        r.go(1); r.go(3)                                              // pedal -> countdown -> running
        assertEquals(362, bike.sent.last())
        r.go(40)
        assertEquals(WorkoutPlayer.State.Finished, s.player.state); assertEquals(2, bike.releases)
    }

    @Test fun `another app taking over pauses the workout and is never undone`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0); r.go(12)
        assertTrue(bike.erg)
        bike.external = "Zwift"; val n = bike.sent.size
        r.go(10)
        assertEquals(WorkoutPlayer.State.Paused, s.player.state)
        assertEquals(n, bike.sent.size); assertEquals(0, bike.releases)          // did not touch the bike
        assertNotNull(s.alert)
        r.go(10); assertEquals(WorkoutPlayer.State.Paused, s.player.state)       // pedaling does not resume it

        assertTrue(!s.resume()); assertTrue(s.alert!!.contains("Zwift"))
        bike.external = null
        assertTrue(s.resume()); r.go(4)
        assertEquals(WorkoutPlayer.State.Running, s.player.state); assertTrue(bike.erg)
    }

    @Test fun `a shift in ERG is kept for the rest of the step`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0); r.go(11)
        assertEquals(362, bike.target)
        bike.shift(+10); r.go(3)
        assertEquals(372, bike.target); assertEquals(10, s.shiftOffset)
        assertEquals(listOf(165, 362), bike.sent)                                 // not fought back to 362
        r.go(7)
        assertEquals(165, bike.target); assertEquals(0, s.shiftOffset)            // next step starts clean
    }

    @Test fun `a release that fails is retried until it works`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0); r.go(5)
        bike.fresh = false
        r.go(3, pedal = false)
        assertEquals(0, bike.releases); assertTrue(s.engaged)
        bike.fresh = true; r.go(1, pedal = false)
        assertEquals(1, bike.releases); assertTrue(!s.engaged)
    }

    @Test fun `a target the bike keeps refusing pauses and lets go`() {
        val bike = FakeBike(); val s = WorkoutSession(Workout("Z", listOf(Step.Steady(10, 60.0), Step.Steady(10, 5.0), Step.Steady(10, 60.0))), 329, bike)
        val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0); r.go(11)
        // 5% = 16 W: under 25 W counts as no target, so the bike is let go rather than left at 197 W.
        assertTrue(!bike.erg)
        bike.fresh = false; r.go(9)                                                // into the 60% step with stale data
        assertEquals(WorkoutPlayer.State.Running, s.player.state)                 // refused for under 2 s: still trying
        r.go(2)
        assertEquals(WorkoutPlayer.State.Paused, s.player.state); assertNotNull(s.alert)
    }

    @Test fun `will not start while an FTMS app controls the bike`() {
        val bike = FakeBike().apply { external = "Zwift" }; val s = WorkoutSession(w, 329, bike)
        assertTrue(!s.start()); assertEquals(WorkoutPlayer.State.Ready, s.player.state)
    }

    @Test fun `after a knob turn before Start, pedaling does not take the bike back (review C1)`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.player.preload = true
        r.go(3); assertTrue(bike.erg); assertEquals(listOf(165), bike.sent)                       // preload holds 165
        bike.knob(); r.go(10)
        assertEquals(listOf(165), bike.sent); assertTrue(!bike.erg); assertNotNull(s.alert)       // never re-engaged
        assertEquals(WorkoutPlayer.State.Ready, s.player.state)
        assertTrue(s.start()); r.go(4); assertTrue(bike.erg)                                    // Start clears it
    }

    @Test fun `preload never takes the bike from an FTMS app`() {
        val bike = FakeBike().apply { external = "Zwift" }; val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.player.preload = true; r.go(5)
        assertEquals(0, bike.sent.size); assertTrue(s.alert!!.contains("Zwift"))
    }

    @Test fun `an FTMS app taking the bike while we coast is not overridden (review I1)`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0); r.go(5)
        r.go(1, pedal = false); assertTrue(!bike.erg)                                           // released while coasting
        bike.external = "Zwift"; val n = bike.sent.size
        r.go(3)
        assertEquals(n, bike.sent.size); assertEquals(WorkoutPlayer.State.Paused, s.player.state); assertTrue(s.alert!!.contains("Zwift"))
    }

    @Test fun `a climb sent early carries no shift offset`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0); r.go(3)
        bike.shift(+10); r.go(3); assertEquals(175, bike.target)
        r.go(3)                                                                                 // t = 9: the "1" before 110%
        assertEquals(362, bike.target); assertEquals(listOf(165, 362), bike.sent)               // 362, not 372, and only once
    }

    @Test fun `stats`() {
        val bike = FakeBike(); val s = WorkoutSession(Workout("S", listOf(Step.Steady(120, 100.0))), 300, bike)
        s.tick(0, true, 0f, 0f, null); s.player.start(0)
        var t = 0L
        repeat(60) { t += 1000; s.tick(t, true, 300f, 90f, 150f) }
        val st = s.stats()
        assertEquals(300, st.avgPower); assertEquals(300, st.np); assertEquals(18, st.kj)
        assertEquals(2, st.tss)       // 60 s at FTP = 1.67 TSS
        assertEquals(150, st.intervalHr); assertEquals(90, st.intervalCadence)
    }

    @Test fun `trace has one point per second with 3-s and 1-s power and heart rate`() {
        val bike = FakeBike(); val s = WorkoutSession(Workout("S", listOf(Step.Steady(120, 100.0))), 300, bike)
        s.tick(0, true, 0f, 0f, null); s.player.start(0)
        var t = 0L
        repeat(3) { t += 1000; s.tick(t, true, 300f, 90f, 150f) }
        repeat(3) { t += 1000; s.tick(t, true, 150f, 90f, 151f) }
        assertEquals(6, s.trace.size)
        assertEquals(50.0, s.trace.last().pct3s.toDouble(), 0.01)       // the last 3 s were all 150 W = 50%
        assertEquals(83.33, s.trace[3].pct3s.toDouble(), 0.01)          // (300 + 300 + 150) / 3 = 250 W = 83%
        assertEquals(50.0, s.trace[3].pct1s.toDouble(), 0.01)           // the zoom's line: that second alone, 150 W = 50%
        assertEquals(100.0, s.trace[2].pct1s.toDouble(), 0.01)          // the last 300 W second, unaveraged
        assertEquals(151.0, s.trace.last().hr!!.toDouble(), 0.01)
    }

    @Test fun `Skip step is not recorded as riding`() {
        val s = WorkoutSession(w, 329, FakeBike()); var t = 0L
        s.tick(0, true, 0f, 90f, null, wallMs = 0); s.start()
        repeat(5 + 4) { t += 1000; s.tick(t, true, 200f, 90f, 120f, wallMs = t) }   // countdown, 4 s of step 1
        s.player.skip()
        repeat(3) { t += 1000; s.tick(t, true, 300f, 90f, 120f, wallMs = t) }       // 3 s of step 2
        assertEquals(7.0, s.recorder.seconds, 0.01)
        assertEquals(13, s.player.snapshot().elapsed)                                // clock jumped to 10 s, plus 3
    }

    @Test fun `knob in a range step - no pause, no ERG for the rest of it, ERG back for the next step`() {
        val hills = Workout("H", listOf(Step.Steady(10, 50.0), Step.Steady(10, 100.0, top = 115.0), Step.Steady(10, 50.0)))
        val bike = FakeBike(); val s = WorkoutSession(hills, 300, bike, failMs = 60_000); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.start()
        r.go(5 + 3)                                                   // countdown, 3 s into step 1 (150 W)
        r.go(8)                                                       // into the climb: ERG to its bottom, 300 W
        assertEquals(300, bike.sent.last())
        bike.knob()                                                   // rider settles where they want
        r.go(1)
        assertEquals(WorkoutPlayer.State.Running, s.player.state)    // no pause
        assertEquals(1, s.riderStep)
        assertNotNull(s.notice)
        val sentBefore = bike.sent.size
        r.go(6)                                                       // rest of the climb: hands off
        assertEquals(sentBefore, bike.sent.size)
        r.go(3)                                                       // next step (or its early start): ERG back
        assertEquals(150, bike.sent.last())
        assertTrue(bike.erg)
        assertEquals(-1, s.riderStep)
        assertEquals(null, s.notice)
    }

    @Test fun `knob in a single-target step - no pause, the rider's until the step ends`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.start()
        r.go(5 + 12)                                                  // in the 110% step
        bike.knob(); val n = bike.sent.size; r.go(5)
        assertEquals(WorkoutPlayer.State.Running, s.player.state)     // a bumped knob doesn't pause
        assertEquals(1, s.riderStep); assertEquals(n, bike.sent.size) // no ERG for the rest of the step
        r.go(5); assertEquals(165, bike.target); assertEquals(-1, s.riderStep)   // ERG back for the next step
    }

    @Test fun `ERG on gives the step back at once`() {
        val bike = FakeBike(); val s = WorkoutSession(w, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.start()
        r.go(5 + 12); bike.knob(); r.go(2)
        assertTrue(!bike.erg)
        s.ergBack(); r.go(1)
        assertTrue(bike.erg); assertEquals(362, bike.target); assertNull(s.notice)
    }

    @Test fun `knob in the climb-lead second belongs to the climb, not undone at once (review E1)`() {
        val rw = Workout("R", listOf(Step.Steady(10, 55.0, top = 70.0), Step.Steady(10, 137.0), Step.Steady(10, 50.0)))
        val bike = FakeBike(); val s = WorkoutSession(rw, 329, bike).also { it.player.climbLeadSeconds = 1.0 }; val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0)
        r.go(9)                                                       // 9 s into the range step: the lead sends 451 W
        assertEquals(451, bike.sent.last())
        bike.knob(); val n = bike.sent.size
        r.go(3)
        assertEquals(WorkoutPlayer.State.Running, s.player.state)
        assertEquals(n, bike.sent.size)                                // 451 W not sent again for the climb
        assertEquals(1, s.riderStep)
        r.go(8); assertEquals(165, bike.target)                         // ERG back for the step after it
    }

    @Test fun `lost data in a range step pauses, it is not a knob turn (review E2)`() {
        val rw = Workout("R", listOf(Step.Steady(60, 55.0, top = 70.0), Step.Steady(10, 50.0)))
        val bike = FakeBike(); val s = WorkoutSession(rw, 329, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0)
        r.go(10); assertTrue(bike.erg)
        bike.lostData(); r.go(1)
        assertEquals(WorkoutPlayer.State.Paused, s.player.state)
        assertNotNull(s.alert)
    }

    @Test fun `knob in the second of two equal steps keeps the knob (review K1)`() {
        val eq = Workout("E", listOf(Step.Steady(30, 65.0), Step.Steady(30, 65.0), Step.Steady(30, 50.0)))
        val bike = FakeBike(); val s = WorkoutSession(eq, 300, bike); val r = Ride(s)
        s.tick(0, true, 0f, 90f, null); s.player.start(0)
        r.go(45)                                                      // 15 s into the second 65% step
        val n = bike.sent.size
        bike.knob(); r.go(5)
        assertEquals(1, s.riderStep); assertEquals(n, bike.sent.size); assertTrue(!bike.erg)
        r.go(15); assertEquals(150, bike.target)                       // ERG back for the 50% step
    }
}
