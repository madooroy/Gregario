package com.madooroy.gregario.workout

import org.junit.Assert
import org.junit.Test

// kotlin.test-style helpers on top of JUnit 4 (message last, as in kotlin.test).
private fun <T> assertEquals(expected: T, actual: T, message: String? = null) = Assert.assertEquals(message, expected, actual)
private fun assertEquals(expected: Double, actual: Double, tolerance: Double) = Assert.assertEquals(expected, actual, tolerance)
private fun assertNull(actual: Any?) = Assert.assertNull(actual)
private fun assertTrue(actual: Boolean) = Assert.assertTrue(actual)

class WorkoutTest {
    private fun zwo(name: String) = ZwoParser.parse(javaClass.getResource("/workouts/$name.zwo")!!.readText())

    @Test fun `test workout files load with the right lengths`() {
        val minutes = mapOf("Ramp1x & Drops" to 15, "VO2 6x2" to 60, "Threshold 3x10" to 60, "VO2 15x30" to 30,
            "PowerTable" to 10, "Endurance 30" to 30)
        for ((name, min) in minutes) {
            val w = zwo(name)
            assertEquals(name, w.name)
            assertEquals(min * 60, w.totalSeconds, name)
            println("$name: ${w.steps.size} steps, ${w.label()} | ${w.title()}")
        }
    }

    @Test fun `targets round to whole watts at FTP 329`() {
        val w = zwo("Ramp1x & Drops")
        assertEquals(165, watts(w.pctAt(0.0), 329))     // Warmup 165W
        assertEquals(201, watts(w.pctAt(100.0), 329))   // Endurance 201W (61%)
        assertEquals(299, watts(w.pctAt(140.0), 329))   // 299W (91%)
        assertEquals(165, watts(w.pctAt(9 * 60 + 15.0), 329)) // 50% between drops
        assertEquals(362, watts(w.pctAt(10 * 60 + 15.0), 329)) // 110%
    }

    @Test fun `library labels`() {
        assertEquals("VO2 · 125% · 2-min efforts", zwo("VO2 6x2").label())
        assertEquals("VO2 · 115% · 30-s efforts", zwo("VO2 15x30").label())
        assertEquals("Endurance · 72%", zwo("Endurance 30").label())
        assertEquals("VO2 · 110% · 30-s efforts", zwo("Ramp1x & Drops").label())   // 90 s at 110% counts now
        // Ride titles: what the workout is, not its file name.
        assertEquals("VO2 Max · 27×30 s at 110%", zwo("VO2 27x30").title())
        assertEquals("VO2 Max · 6×2 min at 125%", zwo("VO2 6x2").title())
        assertEquals("Endurance · 30 min at 72%", zwo("Endurance 30").title())
        // Sprints in an endurance ride don't make it VO2; one long block reads as one effort.
        val sprints = Workout("E", listOf(Step.Steady(2400, 65.0), Step.Steady(20, 180.0), Step.Steady(160, 50.0),
            Step.Steady(20, 180.0), Step.Steady(160, 50.0), Step.Steady(20, 180.0), Step.Steady(2620, 65.0)))
        assertEquals("Endurance · 90 min at 65%", sprints.title())
        val block = Workout("T", listOf(Step.Steady(600, 60.0), Step.Steady(2400, 95.0), Step.Steady(600, 50.0)))
        assertEquals("Threshold · 1×40 min at 95%", block.title())
    }

    @Test fun `ramps interpolate`() {
        val w = zwo("VO2 6x2")
        val cool = w.steps.last() as Step.Ramp
        assertEquals(cool.startPct, w.pctAt(w.totalSeconds - cool.seconds + 0.0), 1e-9)
        assertTrue(w.pctAt(w.totalSeconds - 1.0) < cool.startPct)
    }

    @Test fun `player counts down, runs, auto-pauses and resumes on pedaling`() {
        val p = WorkoutPlayer(zwo("Ramp1x & Drops"), 329)
        var now = 0L
        fun tick(s: Int, pedal: Boolean = true) = (1..s).map { now += 1000; p.tick(now, pedal) }.last()
        p.tick(now, true); p.start()
        assertEquals(3, p.snapshot().countdown)
        assertEquals(165, p.snapshot().ergWatts)                // ERG on during countdown
        var s = tick(3); assertEquals(WorkoutPlayer.State.Running, s.state); assertEquals(0, s.elapsed)
        s = tick(10); assertEquals(10, s.elapsed); assertEquals(80, s.stepRemaining); assertEquals(201, s.nextWatts)
        s = tick(1, pedal = false); assertEquals(WorkoutPlayer.State.Running, s.state)
        assertNull(s.ergWatts)                                  // lets go at once, before the auto-pause
        s = tick(2, pedal = false); assertEquals(WorkoutPlayer.State.Paused, s.state); assertTrue(s.autoPaused)
        assertEquals(10, s.elapsed)                             // the clock stopped with the pedals
        assertTrue(s.canAutoResume)
        s = tick(5, pedal = false); assertEquals(WorkoutPlayer.State.Paused, s.state)
        s = tick(1); assertEquals(WorkoutPlayer.State.Countdown, s.state)   // resumes through a countdown
        s = tick(3); assertEquals(WorkoutPlayer.State.Running, s.state)
        s = tick(1); assertEquals(11, s.elapsed)
    }

    @Test fun `before Start, pedaling holds the first target without starting the clock`() {
        val p = WorkoutPlayer(zwo("Ramp1x & Drops"), 329)
        var now = 0L
        p.preload = true
        repeat(30) { now += 1000; p.tick(now, true) }
        var s = p.snapshot()
        assertEquals(WorkoutPlayer.State.Ready, s.state); assertEquals(0, s.elapsed); assertEquals(165, s.ergWatts)
        now += 1000; s = p.tick(now, false); assertNull(s.ergWatts)              // stop pedaling: let go
        p.preload = false; now += 1000; s = p.tick(now, true); assertNull(s.ergWatts)
    }

    @Test fun `3-2-1 before each step change`() {
        val p = WorkoutPlayer(zwo("Ramp1x & Drops"), 329)
        var now = 0L
        p.tick(now, true); p.start(0)
        val seen = (1..92).map { now += 1000; p.tick(now, true).changeIn }
        assertEquals(listOf(3, 2, 1, null, null), seen.subList(86, 91))          // step 1 is 90 s
        assertNull(seen[80])
    }

    @Test fun `no auto-resume after 30 s off the bike`() {
        val p = WorkoutPlayer(zwo("VO2 6x2"), 329)
        var now = 0L
        fun tick(s: Int, pedal: Boolean) = (1..s).map { now += 1000; p.tick(now, pedal) }.last()
        p.tick(now, true); p.start(0); tick(60, true)
        var s = tick(3, false); assertEquals(WorkoutPlayer.State.Paused, s.state)
        s = tick(40, false); assertTrue(!s.canAutoResume)
        s = tick(5, true); assertEquals(WorkoutPlayer.State.Paused, s.state); assertNull(s.ergWatts)
        p.resume(); s = tick(3, true); assertEquals(WorkoutPlayer.State.Running, s.state)
    }

    @Test fun `extending a warm-up ramp keeps its slope`() {
        val w = Workout("R", listOf(Step.Ramp(300, 40.0, 65.0), Step.Steady(300, 110.0)))
        val p = WorkoutPlayer(w, 300); var now = 0L
        p.tick(now, true); p.start(0)
        repeat(150) { now += 1000; p.tick(now, true) }
        val before = p.snapshot().targetWatts!!
        p.extendWarmup(60)
        assertEquals(before, p.snapshot().targetWatts)
        assertEquals(Step.Steady(60, 65.0), p.workout.steps[1])
    }

    @Test fun `an endurance ride only extends its first step`() {
        val p = WorkoutPlayer(zwo("Endurance 30"), 329); var now = 0L
        p.tick(now, true); p.start(0)
        p.skip(); assertTrue(!p.inWarmup())
        val total = p.workout.totalSeconds; p.extendWarmup(60); assertEquals(total, p.workout.totalSeconds)
    }

    @Test fun `bad targets are refused at load`() {
        assertNull(zwo("VO2 6x2").problem())
        assertTrue(Workout("x", listOf(Step.Steady(60, 0.0))).problem() != null)       // .zwo without Power
        assertTrue(Workout("x", listOf(Step.Steady(60, 250.0))).problem() != null)     // watts read as %
        assertTrue(Workout("x", listOf(Step.Ramp(60, 50.0, 260.0))).problem() != null)
    }

    @Test fun `skip, intensity, extend warmup, finish`() {
        val p = WorkoutPlayer(zwo("Ramp1x & Drops"), 329)
        var now = 0L
        fun tick(s: Int) = (1..s).map { now += 1000; p.tick(now, true) }.last()
        p.tick(now, true); p.start(countdownSeconds = 0)
        tick(10)
        p.extendWarmup(60)
        assertEquals(16 * 60, p.workout.totalSeconds)
        assertEquals(140, p.snapshot().stepRemaining)
        p.skip(); assertEquals(150, p.snapshot().elapsed); assertEquals(201, p.snapshot().targetWatts)
        p.changeIntensity(+5); assertEquals(211, p.snapshot().targetWatts)   // 61% x 105% x 329
        assertTrue(p.inWarmup())            // 61% step is still before the first hard step
        p.skip(); assertTrue(!p.inWarmup())  // 91% step
        val s = tick(20 * 60)
        assertEquals(WorkoutPlayer.State.Finished, s.state); assertNull(s.ergWatts)
    }

    @Test fun `climbs are sent on the 1 of the countdown, drops on 0`() {
        // Ramp1x & Drops: 90 s at 165, then 45 s at 201 / 30 s at 299 ...
        val p = WorkoutPlayer(zwo("Ramp1x & Drops"), 329)
        var now = 0L
        p.tick(now, true); p.start(countdownSeconds = 0)
        fun at(sec: Double) { val end = (sec * 1000).toLong(); while (now < end) { now = minOf(end, now + 1000); p.tick(now, true) } }
        at(88.0); assertEquals(165, p.snapshot().ergWatts); assertEquals(1000L, p.msToNextChange())   // "2": the climb is due at 89 s
        at(89.0); var s = p.snapshot()
        assertEquals(1, s.changeIn); assertEquals(165, s.targetWatts); assertEquals(201, s.ergWatts)  // "1": climb sent
        assertEquals(1000L, p.msToNextChange())
        at(90.0); assertEquals(201, p.snapshot().ergWatts); assertEquals(44000L, p.msToNextChange())   // 135-1 s
        at(134.0); assertEquals(299, p.snapshot().ergWatts)                                            // next climb at "1"
        at(165.0 - 1.0); s = p.snapshot(); assertEquals(299, s.ergWatts)                               // drop: not led
        assertEquals(1000L, p.msToNextChange())
        at(165.0); assertEquals(201, p.snapshot().ergWatts)
        now += 1000; p.tick(now, false); assertNull(p.msToNextChange())                              // coasting: no fast ticks
    }

    @Test fun `no lead when off, none into a pause or past the end`() {
        val p = WorkoutPlayer(zwo("Ramp1x & Drops"), 329).apply { climbLeadSeconds = 0.0 }
        var now = 0L
        p.tick(now, true); p.start(countdownSeconds = 0)
        while (now < 89_500) { now = minOf(89_500, now + 1000); p.tick(now, true) }; assertEquals(165, p.snapshot().ergWatts)
        val q = WorkoutPlayer(zwo("Ramp1x & Drops"), 329); now = 0
        q.tick(now, true); q.start(countdownSeconds = 0); while (now < 89_000) { now += 1000; q.tick(now, true) }; q.pause()
        assertNull(q.snapshot().ergWatts); assertNull(q.msToNextChange())
        val r = WorkoutPlayer(zwo("Ramp1x & Drops"), 329); now = 0
        r.tick(now, true); r.start(countdownSeconds = 0)
        while (now < (r.workout.totalSeconds - 1) * 1000L) { now += 1000; r.tick(now, true) }
        assertEquals(165, r.snapshot().ergWatts)                                                      // last step: nothing next
    }

    @Test fun `intervals_icu planned workout with repeats`() {
        val json = """{"name":"Test VO2","workout_doc":{"steps":[
            {"duration":600,"power":{"units":"%ftp","start":40,"end":60},"ramp":true,"text":"Warm up"},
            {"reps":3,"steps":[{"duration":120,"power":{"units":"%ftp","value":120}},
                               {"duration":120,"power":{"units":"%ftp","value":40}}]},
            {"duration":300,"power":{"units":"%ftp","value":50}}]}}"""
        val w = IcuParser.parseEvent(json)
        assertEquals(600 + 3 * 240 + 300, w.totalSeconds)
        assertEquals(8, w.steps.size)
        assertEquals(395, watts(w.pctAt(700.0), 329))
        assertEquals("Warm up", w.steps[0].text)
    }

    @Test fun `intervals_icu ranges, units, missing times and huge repeats`() {
        val range = """{"name":"R","workout_doc":{"steps":[{"duration":600,"power":{"units":"%ftp","start":88,"end":94}}]}}"""
        // A range: ERG to the bottom (88%), the top kept for the knob and the panel.
        assertEquals(Step.Steady(600, 88.0, top = 94.0), IcuParser.parseEvent(range).steps[0])
        fun fails(json: String) = try { IcuParser.parseEvent(json); false } catch (e: IllegalArgumentException) { true }
        assertTrue(fails("""{"workout_doc":{"steps":[{"duration":600,"power":{"value":250}}]}}"""))                  // no units
        assertTrue(fails("""{"workout_doc":{"steps":[{"duration":600,"power":{"units":"w","value":250}}]}}"""))      // watts
        assertTrue(fails("""{"workout_doc":{"duration":1200,"steps":[{"duration":600,"power":{"units":"%ftp","value":60}},
            {"distance":5000,"power":{"units":"%ftp","value":60}}]}}"""))                                          // distance step
        assertTrue(fails("""{"workout_doc":{"steps":[{"reps":100000,"steps":[{"duration":1,"power":{"units":"%ftp","value":60}}]}]}}"""))
    }
}

class ZoneTest {
    @Test fun `zone limits match intervals_icu`() {
        Assert.assertEquals(Zone.Z1, Zone.of(55.0)); Assert.assertEquals(Zone.Z2, Zone.of(56.0))
        Assert.assertEquals(Zone.Z2, Zone.of(61.0)); Assert.assertEquals(Zone.Z4, Zone.of(91.0))
        Assert.assertEquals(Zone.Z5, Zone.of(110.0)); Assert.assertEquals(Zone.Z6, Zone.of(125.0))
        Assert.assertEquals(Zone.Z7, Zone.of(151.0))
    }

    @Test fun `hill-climb ranges and a cadence range`() {
        val json = """{"name":"Hills","workout_doc":{"steps":[
            {"duration":600,"ramp":true,"power":{"units":"%ftp","start":45,"end":60}},
            {"duration":300,"power":{"units":"%ftp","start":99,"end":110},"cadence":{"units":"rpm","start":75,"end":90}},
            {"duration":180,"power":{"units":"%ftp","start":75,"end":55}},
            {"duration":300,"power":{"units":"%ftp","value":100},"cadence":{"units":"rpm","value":85}}]}}"""
        val w = IcuParser.parseEvent(json)
        assertEquals(Step.Ramp(600, 45.0, 60.0), w.steps[0])
        assertEquals(Step.Steady(300, 99.0, "75–90 rpm", top = 110.0), w.steps[1])
        assertEquals(Step.Steady(180, 55.0, top = 75.0), w.steps[2])          // written high-low: still the bottom
        assertEquals(Step.Steady(300, 100.0, "85 rpm"), w.steps[3])
    }

    @Test fun rangeStepsShowTheirRangeInLabelAndTitle() {
        val w = Workout("Z1", listOf(Step.Ramp(600, 45.0, 60.0), Step.Steady(1800, 50.0, top = 70.0), Step.Ramp(300, 60.0, 45.0)))
        Assert.assertEquals("Endurance · 50–70%", w.label())
        Assert.assertEquals("Endurance · 45 min at 50–70%", w.title())
    }
}
