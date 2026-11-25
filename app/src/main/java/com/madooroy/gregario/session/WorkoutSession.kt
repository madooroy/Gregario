package com.madooroy.gregario.session

import com.madooroy.gregario.record.RideRecorder
import com.madooroy.gregario.workout.FtpTest
import com.madooroy.gregario.workout.TestResult
import com.madooroy.gregario.workout.Workout
import com.madooroy.gregario.workout.WorkoutPlayer
import kotlin.math.pow

/** Shown when the bike can't hold ERG: the workout goes on as targets to follow. */
const val NO_ERG_NOTICE = "No ERG on this bike: follow the target with the resistance knob"

/**
 * What the bike reports about itself: its ERG target, and why not.
 * [knob]: ERG stopped because the rider turned the knob (not lost data, a failed write or a controller leaving).
 * [noErg]: the bike can't hold ERG at all (a Peloton Bike without a SmartSpin2K, or Bike+ ERG off in Grupetto).
 */
data class BikeStatus(val erg: Boolean, val targetWatts: Int, val externalControl: String?, val message: String, val knob: Boolean = false,
                      val noErg: Boolean = false)

/** What the session needs from the bike: hold a target, hand it back, and report how it stands. */
interface ErgSink {
    /** Ask for ERG at [watts]; false if the bike refused (stale data or out of range). */
    fun setErg(watts: Int): Boolean
    /** Hand control back to the rider; false if it could not be done yet (retried next tick). */
    fun release(): Boolean
    /** Current controller state, or null if there is no controller. */
    fun status(): BikeStatus?
    /**
     * The bike copes with the rider stopping by itself (a SmartSpin2K drops to its minimum and restores the target when
     * pedalling resumes), so an auto-pause keeps the target instead of handing the bike back. Handing an SS2K back moves
     * its knob to neutral, and taking it back made it hunt for the target (Nov 25 ride: 334 W and 281 W spikes).
     */
    val holdsThroughStops: Boolean get() = false
}

/**
 * One second of the ride for the panel's charts: workout time, 3-second average power (% FTP, the whole-ride strip),
 * heart rate, cadence, and that second's power unaveraged (% FTP, the zoomed chart, so step edges show where they are).
 */
data class TracePoint(val t: Int, val pct3s: Float, val hr: Float?, val cadence: Float = 0f, val pct1s: Float = pct3s)

/** Ride numbers for the panel's second page. */
data class RideStats(
    val avgPower: Int, val np: Int, val kj: Int, val tss: Int,
    val intervalPower: Int?, val intervalHr: Int?, val intervalCadence: Int?,
)

/**
 * One workout ride: drives the player once a second and sends ERG targets to the bike only when they change.
 *
 * It never takes control back within a step. A knob turn, in any step, means "I'll set it" (a knee,
 * a towel or a dropped bottle can bump the knob, and only the panel's Pause should pause): the workout runs on, ERG
 * stays off for the rest of that step (or until "ERG on" on the panel) and comes back for the next one. Anything
 * else that stops ERG (data lost, a failed write, an FTMS app took over) pauses the workout and waits for a tap. A shift in ERG is the rider's choice: it is kept as an offset
 * for the rest of that step. If the bike keeps refusing a target for [failMs], the workout pauses and lets go.
 */
/** Seconds of countdown after Start: 5, so the rider can start Peloton's Just Ride or Scenic ride on the same beat. */
const val START_COUNTDOWN = 5

/** Ramp test: the ramp is over when the cadence stays under this… */
const val RAMP_FAIL_RPM = 55f
/** …for this many seconds (or the rider stops pedaling). */
const val RAMP_FAIL_SECS = 3.0

class WorkoutSession(workout: Workout, ftp: Int, private val bike: ErgSink, private val failMs: Long = 2_000) {
    val player = WorkoutPlayer(workout, ftp)
    /** Every pedaling second of the running workout, for the FIT file. */
    val recorder = RideRecorder(workout, ftp)
    /** A message for the panel (why the workout paused); cleared on resume. */
    var alert: String? = null; private set
    /** What the charts draw over the workout: one point per second of riding (yellow power and red heart-rate lines). */
    val trace = ArrayList<TracePoint>()
    private val last3 = ArrayDeque<Pair<Double, Float>>()   // (seconds, watts) covering the last 3 s

    /** Information for the panel that is not a problem (e.g. "Ramp test over · cool down"). */
    var notice: String? = null; private set
    private var rampLow = 0.0            // seconds the cadence has been too low in the ramp

    /** The rider tapped Done in a ramp test, or the cadence gave out: go to the cooldown. */
    fun endRamp(why: String = "Ramp test over · cool down"): Boolean {
        if (!player.finishRamp()) return false
        rampLow = 0.0
        notice = why + (testResult()?.ftpFromRamp?.let { " · FTP $it W" } ?: "")
        return true
    }

    /** The test's numbers so far (a ramp's best minute, a 20-minute test's best 20 minutes), or null if not a test. */
    fun testResult(): TestResult? {
        val test = player.test ?: return null
        val ramp = test.ramp
        val best1 = ramp?.let { r -> recorder.bestAverage(60) { it in r } }
        val best20 = if (test.twenty) recorder.bestAverage(1200) else null
        if (best1 == null && best20 == null) return null
        return TestResult(best1, best1?.let { TestResult.ftp(it, FtpTest.RAMP_FACTOR) },
            best20, best20?.let { TestResult.ftp(it, FtpTest.TWENTY_FACTOR) },
            ramp?.let { r -> recorder.stepsRidden { it in r } } ?: 0)
    }

    /** The step the rider took over with the knob (-1: none): no ERG until the next step or "ERG on". */
    var riderStep = -1; private set
    private var riderNotice = false
    private var shiftNotice = false

    /** Watts added by the rider's shifts, for step [offsetStep] (the current step, or the next in a climb's lead second). */
    private var offset = 0
    private var currentStep = -1

    /** Watts the rider's shifts added to the current step's target (for the panel and the ride file). */
    val shiftOffset: Int get() = if (offsetStep >= 0 && offsetStep == currentStep) offset else 0

    private var sentWatts: Int? = null   // what the bike is holding for us, when we hold ERG

    /** Step index [sentWatts] was sent for (the next step when it was a climb lead), or -1. */

    private var sentStep = -1
    private var holding = false          // we believe the bike is in ERG for us
    /** The bike can't hold ERG (see [BikeStatus.noErg]): targets are shown to follow, never sent. */
    var noErgBike = false; private set
    private var needsRelease = false     // we held ERG and have not yet handed it back
    private var failSince: Long? = null
    private var offsetStep = -1
    /** Before Start: someone else took the bike (knob, data loss, an FTMS app), so pedaling no longer preloads ERG. */
    private var preloadBlocked = false

    // stats
    private var lastMs: Long? = null
    private var lastT = 0.0          // workout clock at the last tick: recorded time = time the workout clock advanced
    private var seconds = 0.0; private var joules = 0.0
    private val roll = ArrayDeque<Double>(); private var rollSum = 0.0
    private var np4Sum = 0.0; private var np4N = 0
    private var stepIndex = -1; private var stepSec = 0.0
    private var stepP = 0.0; private var stepHr = 0.0; private var stepHrSec = 0.0; private var stepCad = 0.0

    /** Start the countdown, unless another app controls the bike. Returns false (and sets [alert]) if refused. */
    fun start(): Boolean {
        bike.status()?.externalControl?.let { alert = "$it is controlling the bike · disconnect it first"; return false }
        alert = null; preloadBlocked = false
        offset = 0; offsetStep = -1; if (shiftNotice) { notice = null; shiftNotice = false }   // a shift before Start is not step 1's
        player.start(START_COUNTDOWN); return true
    }

    fun resume(): Boolean {
        bike.status()?.externalControl?.let { alert = "$it is controlling the bike · disconnect it first"; return false }
        alert = null
        player.resume(); return true
    }

    /** The panel's "ERG on": give the step back to ERG now instead of at the next step. */
    fun ergBack() {
        if (riderStep < 0) return
        riderStep = -1; if (riderNotice) { notice = null; riderNotice = false }
    }

    /** Call about once a second. [pedaling] = fresh cadence above a few rpm. */
    fun tick(nowMs: Long, pedaling: Boolean, power: Float, cadence: Float, heartRate: Float?,
             wallMs: Long = System.currentTimeMillis(), speedMph: Float? = null, resistance: Int? = null,
             erg: RideRecorder.Erg? = null): WorkoutPlayer.Snapshot {
        // Did someone else take the bike since the last tick?
        if (holding) {
            val st = bike.status()
            // The step the held target belongs to: a target sent early (climb lead) belongs to the next step, so a knob
            // turn in that lead second is a knob turn in the next step (review E1: it was undone at once).
            val here = if (sentStep >= 0) sentStep else player.workout.stepIndexAt(player.elapsedExact)
            // Only a real knob turn hands the step to the rider; lost data or a failed write pauses (review E2).
            if (st != null && st.noErg) {
                // No ERG on this bike: the ride goes on, the rider follows the targets with the knob. No pause.
                holding = false; needsRelease = false; sentWatts = null; sentStep = -1
                noErgBike = true; notice = NO_ERG_NOTICE
            } else if (st != null && !st.erg && st.knob && st.externalControl == null && player.state == WorkoutPlayer.State.Running) {
                // The knob: the rider's resistance until the step ends. No pause.
                holding = false; needsRelease = false; sentWatts = null; sentStep = -1
                riderStep = here; riderNotice = true
                notice = "Knob: your resistance until this step ends · ERG back for the next, or tap ERG on"
            } else if (st != null && (!st.erg || st.externalControl != null)) {
                holding = false; needsRelease = false; sentWatts = null; sentStep = -1   // theirs now: do not touch it
                if (player.state == WorkoutPlayer.State.Ready) {
                    preloadBlocked = true                                   // no ERG again until Start
                    alert = "Control taken (${st.externalControl ?: st.message}) · tap Start to ride the workout"
                } else {
                    player.pause()
                    alert = "Control taken (${st.externalControl ?: st.message}) · tap Resume"
                }
            } else if (st != null && sentWatts != null && st.targetWatts != sentWatts) {
                // A shift on the bike (SmartSpin2K's shifters): like the knob, the rider's target until the step ends,
                // then ERG goes on with the next step's target.
                // It belongs to the step the held target was sent for: in a climb's lead second, the coming step.
                if (sentStep != offsetStep) { offsetStep = sentStep; offset = 0 }
                offset += st.targetWatts - sentWatts!!; sentWatts = st.targetWatts
                notice = "Shift: your target until this step ends · ERG goes on with the next step"; shiftNotice = true
            }
        }

        val dt = lastMs?.let { ((nowMs - it) / 1000.0).coerceIn(0.0, 5.0) } ?: 0.0
        lastMs = nowMs
        // Ramp test: the ramp ends when the rider can't turn the pedals any more (cadence under RAMP_FAIL_RPM, or
        // stopped) for RAMP_FAIL_SECS. Checked before the player's tick, so the stop ends the ramp instead of pausing it.
        val ramp = player.test?.ramp
        if (ramp != null && player.state == WorkoutPlayer.State.Running && player.inRamp() &&
            player.workout.stepIndexAt(player.elapsedExact) >= ramp.first + 2) {
            rampLow = if (!pedaling || cadence < RAMP_FAIL_RPM) rampLow + dt else 0.0
            if (rampLow >= RAMP_FAIL_SECS) endRamp("Ramp test over (cadence dropped) · cool down")
        } else rampLow = 0.0

        var snap = player.tick(nowMs, pedaling)
        recorder.workout = player.workout
        // Workout-clock advance, but never more than the time that passed: a jump (Skip step, the end of a ramp test)
        // moves the clock without being ridden, and must not be recorded as one long sample.
        val adv = minOf(player.elapsedExact - lastT, dt)
        lastT = player.elapsedExact
        // Finished counts too: the tick that ends the workout carries its last second.
        if ((snap.state == WorkoutPlayer.State.Running || snap.state == WorkoutPlayer.State.Finished) && pedaling && adv > 0) {
            val step = player.stepOfLast(adv)
            record(adv, step, power.toDouble(), cadence.toDouble(), heartRate?.toDouble())
            recorder.add(wallMs, adv, power, cadence, heartRate, step, speedMph, resistance, erg)
            last3.addLast(adv to power)
            while (last3.size > 1 && last3.sumOf { it.first } - last3.first().first >= 3.0) last3.removeFirst()
            val secs = last3.sumOf { it.first }
            val avg = if (secs > 0) (last3.sumOf { it.first * it.second } / secs).toFloat() else power
            val pt = TracePoint(snap.elapsed, avg / player.ftp * 100f, heartRate?.takeIf { it > 0 }, cadence, power / player.ftp * 100f)
            if (trace.isNotEmpty() && trace.last().t == pt.t) trace[trace.lastIndex] = pt else trace += pt
        }

        currentStep = snap.stepIndex
        // A shift lasts until its step ends (it may be the coming step, shifted in a climb's lead second).
        if (offsetStep >= 0 && (snap.stepIndex > offsetStep || snap.stepIndex + 1 < offsetStep)) {
            offsetStep = -1; offset = 0
            if (shiftNotice) { notice = null; shiftNotice = false }
        }
        // Under 25 W the bike cannot hold ERG: treat it as no target. A shift offset goes with the step it was made in,
        // so a climb's target sent early (look-ahead) carries a shift made in that lead second, not the step before's.
        val led = snap.ergWatts != null && snap.targetWatts != null && snap.ergWatts != snap.targetWatts
        val offsetFor = if (led) snap.stepIndex + 1 else snap.stepIndex
        var want = snap.ergWatts?.let { it + if (offsetFor == offsetStep) offset else 0 }?.takeIf { it >= 25 }
        // The rider has the knob for this step; ERG comes back with the next step (or its early start). A knob turn in
        // a climb's lead second is a knob turn in that climb (riderStep is the next step until it starts).
        if (riderStep >= 0 && snap.stepIndex > riderStep) {
            riderStep = -1; if (riderNotice) { notice = null; riderNotice = false }
        }
        val wantStep = if (led) snap.stepIndex + 1 else snap.stepIndex
        if (riderStep == wantStep) want = null
        // The same watts in the next step aren't sent again, but the held target now belongs to that step: a knob turn
        // there is a knob turn in that step (review K1: it was taken as the step before, and undone at once).
        if (holding && want != null && want == sentWatts) sentStep = wantStep
        if (want != null && snap.state == WorkoutPlayer.State.Ready && preloadBlocked) want = null
        if (want != null && (noErgBike || bike.status()?.noErg == true)) {
            if (!noErgBike) { noErgBike = true; notice = NO_ERG_NOTICE }
            want = null
        }

        // Never take the bike from another app: whenever we are about to (re)engage ERG, check for one first.
        if (want != null && !holding) {
            bike.status()?.externalControl?.let { owner ->
                if (snap.state == WorkoutPlayer.State.Ready) preloadBlocked = true
                else { player.pause(); snap = player.snapshot() }
                alert = "$owner is controlling the bike · disconnect it first"
                want = null
            }
        }

        if (want != null) {
            if (want != sentWatts || !holding) {
                if (bike.setErg(want!!)) { sentWatts = want; sentStep = if (led) snap.stepIndex + 1 else snap.stepIndex
                    holding = true; needsRelease = true; failSince = null }
                else {
                    val since = failSince ?: nowMs.also { failSince = it }
                    if (nowMs - since >= failMs) {
                        player.pause(); failSince = null
                        alert = "Resistance didn't take $want W (${bike.status()?.message ?: "no controller"}) · tap Resume"
                        snap = player.snapshot()
                    }
                }
            }
        } else failSince = null

        // A bike that copes with a stopped rider keeps the target while the rider stops (also during a countdown or the
        // Ready preload) and through the auto-pause that follows, as long as pedalling on would resume into it. Pause,
        // End, the step's own end and an auto-pause past its resume window still hand it back.
        val stopped = !pedaling && (snap.state == WorkoutPlayer.State.Running || snap.state == WorkoutPlayer.State.Countdown ||
            snap.state == WorkoutPlayer.State.Ready)
        val keep = holding && bike.holdsThroughStops && (stopped || (snap.state == WorkoutPlayer.State.Paused && snap.canAutoResume))
        if ((want == null || snap.ergWatts == null) && !keep) {
            holding = false; sentWatts = null; sentStep = -1
            if (needsRelease && bike.release()) needsRelease = false
        }
        return snap
    }

    /** True while ERG may still be engaged for this workout. */
    val engaged: Boolean get() = needsRelease

    /** Hand the bike back now (used when the panel goes away). Retries are the caller's job. */
    fun releaseNow(): Boolean {
        player.end(); holding = false; sentWatts = null; sentStep = -1
        if (needsRelease && bike.release()) needsRelease = false
        return !needsRelease
    }

    private fun record(dt: Double, step: Int, p: Double, cad: Double, hr: Double?) {
        seconds += dt; joules += p * dt
        roll.addLast(p); rollSum += p
        if (roll.size > 30) rollSum -= roll.removeFirst()
        if (roll.size == 30) { np4Sum += (rollSum / 30).pow(4); np4N++ }
        if (step != stepIndex) { stepIndex = step; stepSec = 0.0; stepP = 0.0; stepHr = 0.0; stepHrSec = 0.0; stepCad = 0.0 }
        stepSec += dt; stepP += p * dt; stepCad += cad * dt
        if (hr != null && hr > 0) { stepHr += hr * dt; stepHrSec += dt }
    }

    fun stats(): RideStats {
        // Ride totals from the recorder: one sample per second, so NP's 30-s window is 30 seconds, not 30 ticks.
        val t = recorder.totals()
        return RideStats(t.avgPower, t.np, t.kj, Math.round(t.tss).toInt(),
            if (stepSec > 0) Math.round(stepP / stepSec).toInt() else null,
            if (stepHrSec > 0) Math.round(stepHr / stepHrSec).toInt() else null,
            if (stepSec > 0) Math.round(stepCad / stepSec).toInt() else null)
    }
}
