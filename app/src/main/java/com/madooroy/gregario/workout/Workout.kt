package com.madooroy.gregario.workout

import kotlin.math.roundToInt

/** One step of a workout, in % of FTP. Repeats are already unrolled. */
sealed interface Step {
    val seconds: Int
    /** Optional on-screen note shown during this step (e.g. "G: chip Ramp 10/s"). */
    val text: String?

    /** Target in % FTP at [t] seconds into this step. */
    fun pctAt(t: Double): Double

    /**
     * A steady target. [top] set = a range ("5m 100-115%"): ERG brings the rider to [pct], the bottom of the
     * range, and the rider may take the knob for the rest of the step to settle anywhere in it.
     */
    data class Steady(override val seconds: Int, val pct: Double, override val text: String? = null,
                      val top: Double? = null) : Step {
        override fun pctAt(t: Double) = pct
    }

    data class Ramp(override val seconds: Int, val startPct: Double, val endPct: Double,
                    override val text: String? = null) : Step {
        override fun pctAt(t: Double) = startPct + (endPct - startPct) * (t / seconds).coerceIn(0.0, 1.0)
    }

    /** Ride as you like: no ERG target. */
    data class Free(override val seconds: Int, override val text: String? = null) : Step {
        override fun pctAt(t: Double) = Double.NaN
    }
}

data class Workout(val name: String, val steps: List<Step>, val description: String = "") {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }

    /** Start time (s) of each step. */
    val starts: IntArray by lazy {
        IntArray(steps.size).also { var s = 0; steps.forEachIndexed { i, st -> it[i] = s; s += st.seconds } }
    }

    /** Index of the step running at [t] seconds (last step if past the end). */
    fun stepIndexAt(t: Double): Int {
        var i = 0
        while (i < steps.size - 1 && t >= starts[i + 1]) i++
        return i
    }

    /** Target % FTP at [t] seconds; NaN for free-ride steps or past the end. */
    fun pctAt(t: Double): Double {
        if (steps.isEmpty() || t < 0 || t >= totalSeconds) return Double.NaN
        val i = stepIndexAt(t)
        return steps[i].pctAt(t - starts[i])
    }

    /** Why this workout should not be ridden, or null if it is fine: every target must be 20–200% FTP. */
    fun problem(): String? {
        if (steps.isEmpty()) return "No steps"
        if (steps.size > MAX_STEPS) return "More than $MAX_STEPS steps"
        // Times too (review D4): a step under 1 s or over 4 h, or a ride over 12 h, is a broken file.
        steps.firstOrNull { it.seconds < 1 || it.seconds > 4 * 3600 }?.let { return "A step of ${it.seconds} s is out of range" }
        if (steps.sumOf { it.seconds.toLong() } > 12 * 3600) return "Longer than 12 hours"
        for (st in steps) {
            val pcts = when (st) { is Step.Steady -> listOfNotNull(st.pct, st.top); is Step.Ramp -> listOf(st.startPct, st.endPct); is Step.Free -> emptyList() }
            pcts.firstOrNull { !it.isFinite() || it < MIN_PCT || it > MAX_PCT }
                ?.let { return "A step at ${if (it.isFinite()) it.roundToInt() else "?"}% FTP is outside ${MIN_PCT.toInt()}–${MAX_PCT.toInt()}%" }
        }
        return null
    }

    /**
     * A short label for the library, e.g. "VO2 · 125% · 2-min efforts". The peak is the highest target held
     * for at least 1 minute in all, so a single 30-s build step does not make a threshold ride "VO2".
     */
    fun label(): String {
        val steady = steps.filterIsInstance<Step.Steady>()
        if (steady.isEmpty()) return "Free ride"
        val byPct = steady.groupBy { Math.round(it.pct) }.mapValues { (_, l) -> l.sumOf { it.seconds } }
        val peak = byPct.filter { it.value >= 60 }.keys.maxOrNull()?.toDouble() ?: steady.maxOf { it.pct }
        val hard = steady.filter { kotlin.math.abs(it.pct - peak) <= 2 && it.pct >= 76 }
        val kind = when {
            peak >= 106 -> "VO2"
            peak >= 95 -> "Threshold"
            peak >= 88 -> "Sweet Spot"
            peak >= 76 -> "Tempo"
            else -> "Endurance"
        }
        val effort = hard.map { it.seconds }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return listOfNotNull(kind, peakText(peak, steady), effort?.let { "${fmt(it)} efforts" }.takeIf { hard.size > 1 })
            .joinToString(" · ")
    }

    /**
     * The ride's title on intervals.icu and Strava, from what the workout is rather than its file or library name:
     * "VO2 Max · 27×30 s at 110%", "Endurance · 60 min at 66%", "Ramp Test", "FTP Test · 20 min".
     */
    fun title(): String {
        FtpTest.of(this)?.let { return if (it.ramp != null) "Ramp Test" else "FTP Test · 20 min" }
        val steady = steps.filterIsInstance<Step.Steady>()
        if (steady.isEmpty()) return "Free Ride · ${totalSeconds / 60} min"
        val byPct = steady.groupBy { Math.round(it.pct) }.mapValues { (_, l) -> l.sumOf { it.seconds } }
        // The hardest % held for at least a minute in all, for 5% of the ride, and for a quarter of the time of the
        // biggest hard block: openers above the main work and sprints in an endurance ride don't name it
        // (a ride with 3x30 s at 115% then 27x30 s at 110% is "27×30 s at 110%"; endurance with a few short sprints stays Endurance).
        val hardPcts = byPct.filter { it.key >= 76 && it.value * 20 >= totalSeconds }
        val most = hardPcts.values.maxOrNull() ?: 0
        val peak = byPct.filter { it.value >= 60 && (it.key < 76 || (it.key in hardPcts && it.value * 4 >= most)) }
            .keys.maxOrNull()?.toDouble() ?: steady.maxOf { it.pct }
        val kind = when {
            peak >= 106 -> "VO2 Max"
            peak >= 95 -> "Threshold"
            peak >= 88 -> "Sweet Spot"
            peak >= 76 -> "Tempo"
            else -> "Endurance"
        }
        val hard = steady.filter { kotlin.math.abs(it.pct - peak) <= 2 && it.pct >= 76 }
        val at = "at ${peakText(peak, steady)}"
        return when {
            kind == "Endurance" || hard.isEmpty() -> "$kind · ${totalSeconds / 60} min $at"
            hard.size == 1 -> "$kind · 1×${span(hard[0].seconds)} $at"
            hard.all { it.seconds == hard[0].seconds } -> "$kind · ${hard.size}×${span(hard[0].seconds)} $at"
            else -> "$kind · ${hard.size} efforts $at"
        }
    }

    /** "50%", or "50–70%" when the step(s) at that % are a range (the range, not just its bottom). */
    private fun peakText(peak: Double, steady: List<Step.Steady>): String {
        val p = peak.roundToInt()
        val top = steady.filter { Math.round(it.pct).toInt() == p }.mapNotNull { it.top }.maxOrNull()?.roundToInt()
        return if (top != null && top > p) "$p–$top%" else "$p%"
    }

    private fun span(s: Int) = when {
        s % 60 == 0 -> "${s / 60} min"
        s < 60 -> "$s s"
        else -> "${s / 60}:${"%02d".format(s % 60)}"
    }

    companion object {
        const val MIN_PCT = 20.0
        const val MAX_PCT = 200.0
        const val MAX_STEPS = 1000
        const val MAX_REPS = 100

        fun fmt(s: Int): String = when {
            s % 60 == 0 -> "${s / 60}-min"
            s < 60 -> "${s}-s"
            else -> "${s / 60}:${"%02d".format(s % 60)}"
        }
    }
}

/** Watts for a % FTP, rounded half up, as most training apps show them (164.5 -> 165). */
fun watts(pct: Double, ftp: Int, intensityPct: Int = 100): Int? =
    if (pct.isNaN()) null else Math.round(pct / 100.0 * ftp * intensityPct / 100.0).toInt()
