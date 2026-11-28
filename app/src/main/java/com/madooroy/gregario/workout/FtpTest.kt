package com.madooroy.gregario.workout

import kotlin.math.roundToInt

/**
 * A fitness test workout, found from its steps (and name), so ramp and 20-minute tests from other apps in an
 * intervals.icu library work as they are:
 *  - a ramp: [ramp] = the run of rising 1-minute steps. It has no real end: the rider goes until they can't,
 *    then the workout jumps to the step after the ramp (the cooldown). FTP = 75% of the best minute in the ramp.
 *  - a 20-minute test: FTP = 95% of the best 20 minutes. Its effort is a free-ride step (ERG off, resistance
 *    left where it is), as in Zwift's FTP test.
 */
data class FtpTest(val ramp: IntRange?, val twenty: Boolean) {
    companion object {
        const val RAMP_FACTOR = 0.75
        const val TWENTY_FACTOR = 0.95
        /** A ramp is at least this many rising steps of 45-90 s in a row. */
        const val MIN_RAMP_STEPS = 8
        // "FTP test", "test FTP" or "20-min test", not the word FTP alone: "Sweet Spot 2x20 at 90% FTP" is no test (review D2).
        private val TWENTY_NAME = Regex("""\bftp\s*-?\s*test|\btest\s+(your\s+)?ftp\b|20\s*-?\s*min\w*\s+(ftp\s+)?test""", RegexOption.IGNORE_CASE)
        private val WARMUP_NAME = Regex("""warm\s*-?\s*up""", RegexOption.IGNORE_CASE)

        /** The test in [w], or null for an ordinary workout. */
        fun of(w: Workout): FtpTest? {
            val ramp = longestRamp(w.steps)
            // A ramp test also has "FTP" in its name, but its result is the ramp's, not a 20-minute one.
            // "Zwift FTP Warmup" is only the warm-up for a test, ridden on its own.
            // Found by its steps: a 20-minute free-ride effort after a warm-up. A longer free ride, or one with nothing
            // before it, is an ordinary free ride, not a test (Codex review: a 60-min free ride was labelled a test).
            val twentyEffort = w.steps.withIndex().any { (i, s) -> i > 0 && s is Step.Free && s.seconds in 1200..1260 }
            val twenty = ramp == null && !WARMUP_NAME.containsMatchIn(w.name) && w.totalSeconds >= 1200 &&
                (twentyEffort || TWENTY_NAME.containsMatchIn(w.name))
            return if (ramp != null || twenty) FtpTest(ramp, twenty) else null
        }

        private fun longestRamp(steps: List<Step>): IntRange? {
            fun minute(s: Step) = s is Step.Steady && s.seconds in 45..90
            var best: IntRange? = null
            var i = 0
            while (i < steps.size) {
                if (!minute(steps[i])) { i++; continue }
                var j = i
                while (j + 1 < steps.size && minute(steps[j + 1]) &&
                    (steps[j + 1] as Step.Steady).pct > (steps[j] as Step.Steady).pct) j++
                if (j - i + 1 >= MIN_RAMP_STEPS && (best == null || j - i + 1 > best.count())) best = i..j
                i = j + 1
            }
            return best
        }
    }
}

/** What a test showed: best minute in the ramp and best 20 minutes, with the FTP each one gives. */
data class TestResult(val best1: Int?, val ftpFromRamp: Int?, val best20: Int?, val ftpFrom20: Int?, val rampStepsDone: Int = 0) {
    /** Lines for the ride description. */
    fun lines(): List<String> = listOfNotNull(
        best1?.let { "Ramp test: best 1 min $it W → FTP $ftpFromRamp W (75%) · $rampStepsDone ramp steps" },
        best20?.let { "20-min test: average $it W → FTP $ftpFrom20 W (95%)" })

    companion object {
        fun ftp(watts: Int, factor: Double) = (watts * factor).roundToInt()
    }
}
