package com.madooroy.gregario.workout

/**
 * Runs a workout: keeps the clock, knows the current and next step, and says which
 * ERG target to send. No Android code here; the app calls [tick] about once a second
 * and sends the result's [Snapshot.ergWatts] to the bike.
 */
class WorkoutPlayer(
    workout: Workout,
    val ftp: Int,
    /** Seconds without pedaling before the workout pauses itself. */
    private val autoPauseSeconds: Double = 3.0,
    /**
     * Look-ahead for climbs: when the next step is harder, send its target this many seconds before the step
     * (1 s = as the 3-2-1 shows "1"), so the motor is already moving when the effort starts. Drops are never led,
     * so an effort is not cut short. 0 = off. The clock, the countdown, laps and stats stay on the real boundaries.
     */
    var climbLeadSeconds: Double = 1.0,
    /** After an auto-pause, pedaling again resumes (with a countdown) only within this many seconds. */
    private val autoResumeSeconds: Double = 30.0,
) {
    enum class State { Ready, Countdown, Running, Paused, Finished }

    data class Snapshot(
        val state: State,
        val elapsed: Int,
        val stepIndex: Int,
        val stepRemaining: Int,
        val totalRemaining: Int,
        val targetWatts: Int?,
        val nextWatts: Int?,
        val nextStepSeconds: Int?,
        val ergWatts: Int?,
        val countdown: Int?,
        val note: String?,
        val autoPaused: Boolean,
        val intensityPct: Int,
        /** Paused by itself and pedaling will resume it (false once [autoResumeSeconds] have passed). */
        val canAutoResume: Boolean = false,
        /** 3, 2, 1 in the last seconds before the next step (for the panel's countdown flash), else null. */
        val changeIn: Int? = null,
    )

    var workout: Workout = workout; private set
    var intensityPct: Int = 100; private set
    var state: State = State.Ready; private set

    private var t = 0.0               // workout seconds done
    private var lastMs: Long? = null
    private var countdownLeft = 0.0
    private var idle = 0.0
    private var autoPaused = false
    private var pausedFor = 0.0
    /**
     * Before Start: while pedaling, hold ERG at the first step's target so the bike is ready when Start is tapped.
     * The clock does not run. The controller turns this on only for a while after a workout is chosen.
     */
    var preload = false
    private var pedaling = false

    fun start(countdownSeconds: Int = 3) {
        if (state != State.Ready) return
        countdownLeft = countdownSeconds.toDouble()
        state = if (countdownSeconds > 0) State.Countdown else State.Running
    }

    fun pause() { if (state == State.Running || state == State.Countdown) { state = State.Paused; autoPaused = false; pausedFor = 0.0 } }
    /** Resume after a pause, always through a short countdown so the target does not hit at once. */
    fun resume(countdownSeconds: Int = 3) {
        if (state != State.Paused) return
        autoPaused = false; idle = 0.0
        countdownLeft = countdownSeconds.toDouble()
        state = if (countdownSeconds > 0) State.Countdown else State.Running
    }
    fun end() { state = State.Finished }

    fun changeIntensity(deltaPct: Int) { intensityPct = (intensityPct + deltaPct).coerceIn(50, 150) }

    private var testOf: Workout? = null
    private var testCache: FtpTest? = null
    /** The fitness test this workout is, if any (worked out again when the workout changes, e.g. a longer warm-up). */
    val test: FtpTest? get() {
        if (testOf !== workout) { testOf = workout; testCache = FtpTest.of(workout) }
        return testCache
    }

    /** True while riding the open-ended part of a ramp test. */
    fun inRamp(): Boolean = state != State.Ready && state != State.Finished &&
        test?.ramp?.contains(workout.stepIndexAt(t)) == true

    /**
     * End a ramp test's ramp (the rider tapped Done, or could no longer turn the pedals): jump to the step after
     * the ramp, its cooldown, or finish if there is none. False if not in the ramp.
     */
    fun finishRamp(): Boolean {
        val r = test?.ramp ?: return false
        if (state == State.Ready || state == State.Finished || workout.stepIndexAt(t) !in r) return false
        idle = 0.0                                     // the stop that ended the ramp must not also pause the cooldown
        if (r.last + 1 < workout.steps.size) t = workout.starts[r.last + 1].toDouble() else state = State.Finished
        return true
    }

    /** Jump to the start of the next step. */
    fun skip() {
        val i = workout.stepIndexAt(t)
        if (i + 1 < workout.steps.size) t = workout.starts[i + 1].toDouble() else state = State.Finished
    }

    /** True while still in the warm-up: before the first step at or above 76% FTP (only the first step if there is none). */
    fun inWarmup(): Boolean {
        val firstHard = workout.steps.indexOfFirst { it is Step.Steady && it.pct >= 76 || it is Step.Ramp && maxOf(it.startPct, it.endPct) >= 76 }
        // A ramp test's ramp starts easy, but it is the test: its 1-minute steps must stay 1 minute.
        val end = listOfNotNull(if (firstHard < 0) 1 else firstHard, test?.ramp?.first).min()
        return workout.stepIndexAt(t) < end
    }

    /**
     * Add [seconds] to the warm-up. A steady or free step just gets longer; a ramp keeps its slope and is
     * followed by an extra steady step at its end target, so the target never steps back.
     */
    fun extendWarmup(seconds: Int = 60) {
        if (!inWarmup()) return
        val i = workout.stepIndexAt(t)
        val steps = workout.steps.toMutableList()
        when (val s = steps[i]) {
            is Step.Steady -> steps[i] = s.copy(seconds = s.seconds + seconds)
            is Step.Free -> steps[i] = s.copy(seconds = s.seconds + seconds)
            is Step.Ramp -> steps.add(i + 1, Step.Steady(seconds, s.endPct, s.text))
        }
        workout = workout.copy(steps = steps)
    }

    /** Advance the clock. [pedaling] = fresh cadence from the bike. */
    fun tick(nowMs: Long, pedaling: Boolean): Snapshot {
        val dt = lastMs?.let { ((nowMs - it) / 1000.0).coerceIn(0.0, 5.0) } ?: 0.0
        lastMs = nowMs
        this.pedaling = pedaling
        when (state) {
            State.Countdown -> { countdownLeft -= dt; if (countdownLeft <= 0) { state = State.Running; t += -countdownLeft; countdownLeft = 0.0 } }
            State.Running -> {
                // The clock only runs while pedaling; after a few idle seconds the workout pauses itself.
                idle = if (pedaling) 0.0 else idle + dt
                if (idle >= autoPauseSeconds) { state = State.Paused; autoPaused = true; pausedFor = 0.0 }
                else if (pedaling) { t += dt; if (t >= workout.totalSeconds) { t = workout.totalSeconds.toDouble(); state = State.Finished } }
            }
            State.Paused -> {
                pausedFor += dt
                if (autoPaused && pedaling && pausedFor <= autoResumeSeconds) resume()
            }
            else -> {}
        }
        return snapshot()
    }

    /**
     * Milliseconds of riding until the next moment the ERG target can change (a step boundary, or a climb's
     * lead before it), or null when not running. The controller ticks at that moment rather than up to a second late.
     */
    /** Workout seconds done (exact). */
    val elapsedExact: Double get() = t

    /** The step the middle of the last [secs] of riding fell in (a second that straddles a step change goes to the older one). */
    fun stepOfLast(secs: Double): Int = workout.stepIndexAt((t - secs / 2).coerceAtLeast(0.0))

    fun msToNextChange(): Long? {
        // Not pedaling: no target is sent, so there is nothing to be on time for (and no fast ticking until auto-pause).
        if (state != State.Running || !pedaling) return null
        val i = workout.stepIndexAt(t)
        val step = workout.steps.getOrNull(i) ?: return null
        val stepEnd = (workout.starts[i] + step.seconds).toDouble()
        val next = workout.steps.getOrNull(i + 1)
        val now = watts(workout.pctAt(t), ftp, intensityPct)
        val nextW = next?.let { watts(it.pctAt(0.0), ftp, intensityPct) }
        val leadAt = stepEnd - climbLeadSeconds
        val at = if (nextW != null && now != null && nextW > now && t < leadAt) leadAt else stepEnd
        return Math.round((at - t) * 1000).coerceAtLeast(0)
    }

    fun snapshot(): Snapshot {
        val i = workout.stepIndexAt(t)
        val step = workout.steps.getOrNull(i)
        val stepEnd = if (step == null) 0 else workout.starts[i] + step.seconds
        val next = workout.steps.getOrNull(i + 1)
        val target = watts(workout.pctAt(t), ftp, intensityPct)
        // No ERG while not pedaling: the bike would wind resistance up chasing the target.
        val running = (state == State.Running || state == State.Countdown) && pedaling
        val erg = when {
            running -> {
                val nextW = next?.let { watts(it.pctAt(0.0), ftp, intensityPct) }
                if (state == State.Running && nextW != null && target != null && nextW > target &&
                    stepEnd - t <= climbLeadSeconds) nextW else target
            }
            state == State.Ready && preload && pedaling -> target
            else -> null
        }
        val left = (stepEnd - t).coerceAtLeast(0.0).let { Math.ceil(it).toInt() }
        return Snapshot(
            state = state, elapsed = t.toInt(), stepIndex = i,
            stepRemaining = left,
            totalRemaining = (workout.totalSeconds - t).coerceAtLeast(0.0).let { Math.ceil(it).toInt() },
            targetWatts = target,
            nextWatts = next?.let { watts(it.pctAt(0.0), ftp, intensityPct) },
            nextStepSeconds = next?.seconds,
            ergWatts = erg,
            countdown = if (state == State.Countdown) Math.ceil(countdownLeft).toInt() else null,
            note = step?.text, autoPaused = autoPaused, intensityPct = intensityPct,
            canAutoResume = state == State.Paused && autoPaused && pausedFor <= autoResumeSeconds,
            changeIn = if (state == State.Running && next != null && left in 1..3) left else null,
        )
    }
}
