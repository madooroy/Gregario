package com.madooroy.gregario.record

import com.madooroy.gregario.record.FitWriter.F
import com.madooroy.gregario.record.FitWriter.Str
import com.madooroy.gregario.record.FitWriter.T
import com.madooroy.gregario.workout.Step
import com.madooroy.gregario.workout.Workout
import java.util.TimeZone
import kotlin.math.pow

/**
 * Records a workout ride, one sample per pedaling second while the workout runs, and turns it into
 * a FIT activity file: one record per sample, one lap per workout step (so intervals.icu and Strava
 * show each interval, with intervals.icu matching laps to the planned steps), and the session totals
 * (average and normalized power, TSS, IF at the ride's FTP, work).
 */
class RideRecorder(workout: Workout, val ftp: Int) {
    /** The workout as ridden (kept in step with the player, so an extended warm-up labels the laps right). */
    var workout: Workout = workout
    /** [secs]: the time this sample stands for (normally 1 s). [step]: the workout step it fell in. */
    /** [speed]: Peloton's speed (from power, as the Peloton screen shows it), m/s. [resistance]: the Bike+ level, 0-100. */
    data class Sample(val wallMs: Long, val secs: Double, val power: Int, val cadence: Int, val heartRate: Int?, val step: Int,
                      val speed: Double = 0.0, val resistance: Int? = null, val erg: Erg? = null)

    /**
     * ERG in that second: the target (what the workout asked, shift included, also while the rider has the knob:
     * the bottom of a range step), the bike's level, phase (1: the bike held the target, 0: it didn't, i.e. the knob,
     * or ERG not yet engaged) and how many target writes went out. Developer fields, so intervals.icu can chart them.
     */
    data class Erg(val target: Int, val level: Int, val phase: Int, val writes: Int)

    private val samples = ArrayList<Sample>()
    val size: Int get() = samples.size
    val seconds: Double get() = samples.sumOf { it.secs }
    val startedMs: Long? get() = samples.firstOrNull()?.let { it.wallMs - (it.secs * 1000).toLong() }

    fun add(wallMs: Long, secs: Double, power: Float, cadence: Float, heartRate: Float?, step: Int, speedMph: Float? = null,
            resistance: Int? = null, erg: Erg? = null) {
        val mps = speedMph?.takeIf { it.isFinite() && it > 0 }?.let { it * MPH_TO_MPS }?.coerceAtMost(30.0) ?: 0.0
        if (secs <= 0) return
        // The controller sometimes ticks twice in a second (on time for a step change): one FIT record per second,
        // so a sample in the same second as the last one is merged into it (power and cadence weighted by time).
        val last = samples.lastOrNull()
        if (last != null && last.wallMs / 1000 == wallMs / 1000) {
            val tot = last.secs + secs
            samples[samples.lastIndex] = last.copy(wallMs = wallMs, secs = tot,
                power = Math.round((last.power * last.secs + power.coerceIn(0f, 3000f) * secs) / tot).toInt(),
                cadence = Math.round((last.cadence * last.secs + cadence.coerceIn(0f, 254f) * secs) / tot).toInt(),
                heartRate = heartRate?.let { Math.round(it) }?.takeIf { it in 1..254 } ?: last.heartRate,
                speed = (last.speed * last.secs + mps * secs) / tot,
                resistance = resistance?.takeIf { it in 0..254 } ?: last.resistance,
                erg = erg?.let { it.copy(writes = it.writes + (last.erg?.writes ?: 0)) } ?: last.erg)
            return
        }
        samples += Sample(wallMs, secs, Math.round(power).coerceIn(0, 3000), Math.round(cadence).coerceIn(0, 254),
            heartRate?.let { Math.round(it) }?.takeIf { it in 1..254 }, step, mps, resistance?.takeIf { it in 0..254 }, erg)
    }

    /**
     * Best average power over [windowSecs] of riding, in samples whose step passes [inStep] (all by default),
     * or null if there isn't that much riding. Riding time, not wall time: a pause does not count.
     */
    fun bestAverage(windowSecs: Int, inStep: (Int) -> Boolean = { true }): Int? {
        val list = samples.filter { inStep(it.step) }
        var best: Double? = null
        var from = 0; var secs = 0.0; var joules = 0.0
        for (s in list) {
            secs += s.secs; joules += s.power * s.secs
            while (from < list.size && secs - list[from].secs >= windowSecs - 0.25) {
                secs -= list[from].secs; joules -= list[from].power * list[from].secs; from++
            }
            if (secs >= windowSecs - 0.25) best = maxOf(best ?: 0.0, joules / secs)
        }
        return best?.let { Math.round(it).toInt() }
    }

    /** The distinct workout steps that have samples in them, among those passing [inStep]. */
    fun stepsRidden(inStep: (Int) -> Boolean): Int = samples.map { it.step }.filter(inStep).distinct().size

    data class Totals(val seconds: Double, val avgPower: Int, val maxPower: Int, val np: Int, val kj: Int,
        val tss: Double, val intensityFactor: Double, val avgCadence: Int?, val maxCadence: Int?, val avgHr: Int?, val maxHr: Int?,
        val distance: Double = 0.0, val avgSpeed: Double = 0.0, val maxSpeed: Double = 0.0)

    fun totals(list: List<Sample> = samples): Totals {
        val secs = list.sumOf { it.secs }
        val joules = list.sumOf { it.power * it.secs }
        val avg = if (secs > 0) joules / secs else 0.0
        // NP: 30-s rolling average of 1-s power, to the 4th power, averaged, 4th root. A sample is normally one
        // second, but a late tick stands for up to 5 s and a merged one for less: the window counts seconds of
        // riding, not samples (Codex review), so each sample's work is spread over the seconds it covers.
        val roll = ArrayDeque<Double>(); var sum = 0.0; var p4 = 0.0; var n4 = 0
        var binSecs = 0.0; var binJoules = 0.0
        for (s in list) {
            var left = s.secs
            while (left > 1e-9) {
                val take = minOf(left, 1.0 - binSecs)
                binSecs += take; binJoules += s.power * take; left -= take
                if (binSecs >= 1.0 - 1e-9) {
                    roll.addLast(binJoules); sum += binJoules
                    if (roll.size > 30) sum -= roll.removeFirst()
                    if (roll.size == 30) { p4 += (sum / 30).pow(4); n4++ }
                    binSecs = 0.0; binJoules = 0.0
                }
            }
        }
        val np = if (n4 > 0) (p4 / n4).pow(0.25) else avg
        val ifac = if (ftp > 0) np / ftp else 0.0
        val tss = if (ftp > 0) secs * np * ifac / (ftp * 36.0) else 0.0
        val pedal = list.filter { it.cadence > 0 }
        val hrs = list.mapNotNull { it.heartRate }
        return Totals(secs, Math.round(avg).toInt(), list.maxOfOrNull { it.power } ?: 0, Math.round(np).toInt(),
            Math.round(joules / 1000).toInt(), tss, ifac,
            if (pedal.isNotEmpty()) Math.round(pedal.sumOf { it.cadence * it.secs } / pedal.sumOf { it.secs }).toInt() else null,
            pedal.maxOfOrNull { it.cadence },
            if (hrs.isNotEmpty()) Math.round(hrs.average()).toInt() else null, hrs.maxOrNull(),
            list.sumOf { it.speed * it.secs }, if (secs > 0) list.sumOf { it.speed * it.secs } / secs else 0.0,
            list.maxOfOrNull { it.speed } ?: 0.0)
    }

    /**
     * One line per workout step ridden, for the ride's description, e.g. "4. 0:30 @ 345 W → 347 W · 151 bpm · 98 rpm".
     * Long workouts (over 16 steps) list only the work steps (76% FTP and up), with the step's number in the workout.
     */
    fun intervalSummary(intensityPct: Int = 100): String {
        val byStep = samples.groupBy { it.step }.toSortedMap()
        val listAll = byStep.size <= 16
        val lines = byStep.mapNotNull { (i, list) ->
            val st = workout.steps.getOrNull(i) ?: return@mapNotNull null
            val pct = when (st) { is Step.Steady -> st.pct; is Step.Ramp -> maxOf(st.startPct, st.endPct); is Step.Free -> Double.NaN }
            if (!listAll && !(pct >= 76)) return@mapNotNull null
            val t = totals(list)
            fun w(p: Double) = com.madooroy.gregario.workout.watts(p, ftp, intensityPct)
            val target = when (st) {
                is Step.Steady -> if (st.top != null) "${w(st.pct)}–${w(st.top)} W" else "${w(st.pct)} W"
                is Step.Ramp -> "${w(st.startPct)}–${w(st.endPct)} W"
                is Step.Free -> "free"
            }
            val secs = Math.round(t.seconds).toInt()
            "${i + 1}. ${secs / 60}:${"%02d".format(secs % 60)} @ $target → ${t.avgPower} W" +
                (t.avgHr?.let { " · $it bpm" } ?: "") + (t.avgCadence?.let { " · $it rpm" } ?: "")
        }
        return if (lines.isEmpty()) "" else (if (listAll) "Intervals:\n" else "Work intervals:\n") + lines.joinToString("\n")
    }

    /**
     * The ride's work as intervals.icu intervals (start record index, end index exclusive, and a label), so the activity
     * shows its steps without "Use Laps" and without the hand-editing Marc did on TrainerRoad rides:
     * - steady steps only: ramps (warm-up, cool-down, the ramps between efforts) are left out, so an interval starts
     *   and ends where the effort does;
     * - Z1 (55% FTP and under) is left out: recoveries, the "off" of 30/30s;
     * - consecutive steady Z2 steps spanning at most [MERGE_PCT] are one interval (a long top-of-Z2 stretch with
     *   small variations is one Z2 interval); harder steps each stay their own;
     * - nothing shorter than [MIN_INTERVAL_SECS].
     * Each record is one second of riding, and the records are what intervals.icu indexes.
     */
    fun icuIntervals(): List<Map<String, Any?>> {
        class Run(val first: Int, var last: Int, val step: Int)
        val runs = ArrayList<Run>()
        samples.forEachIndexed { i, s ->
            val r = runs.lastOrNull()
            if (r != null && r.step == s.step) r.last = i else runs += Run(i, i, s.step)
        }
        class Block(val first: Int, var last: Int, var step: Int, val texts: MutableList<String?>, val pcts: MutableList<Double>)
        val blocks = ArrayList<Block>()
        var joinable = false
        for (r in runs) {
            val st = workout.steps.getOrNull(r.step) as? Step.Steady
            val pct = st?.let { (it.pct + (it.top ?: it.pct)) / 2 }
            if (st == null || pct == null || pct.isNaN() || pct <= Z1_TOP_PCT || r.last - r.first + 1 < MIN_INTERVAL_SECS) {
                joinable = false; continue
            }
            val b = blocks.lastOrNull()
            if (joinable && b != null && r.step == b.step + 1 && zoneOf(pct) == 2 && b.pcts.all { zoneOf(it) == 2 } &&
                maxOf(pct, b.pcts.max()) - minOf(pct, b.pcts.min()) <= MERGE_PCT) {
                b.last = r.last; b.step = r.step; b.texts += st.text; b.pcts += pct
            } else blocks += Block(r.first, r.last, r.step, mutableListOf(st.text), mutableListOf(pct))
            joinable = true
        }
        return blocks.map { b ->
            val z = zoneOf(b.pcts.first())
            val lo = Math.round(b.pcts.min()); val hi = Math.round(b.pcts.max())
            val text = b.texts.singleOrNull()?.takeIf { !it.isNullOrBlank() }
            linkedMapOf<String, Any?>("start_index" to b.first, "end_index" to b.last + 1,
                "label" to (text ?: "Z$z · " + if (lo == hi) "$lo%" else "$lo–$hi%"))
        }
    }

    /** The ride as a FIT activity file. Throws if nothing was recorded. */
    fun fit(): ByteArray {
        require(samples.isNotEmpty()) { "nothing recorded" }
        val start = startedMs!!
        val end = samples.last().wallMs
        val w = FitWriter()
        w.message(0, listOf(F(0, T.ENUM, 4), F(1, T.UINT16, MANUFACTURER_DEVELOPMENT), F(2, T.UINT16, PRODUCT),
            F(3, T.UINT32Z, (start / 1000) and 0xFFFFFFFFL), F(4, T.UINT32, FitWriter.fitTime(start))))
        w.message(23, listOf(F(253, T.UINT32, FitWriter.fitTime(start)), F(2, T.UINT16, MANUFACTURER_DEVELOPMENT),
            F(4, T.UINT16, PRODUCT), F(5, T.UINT16, 100)), listOf(Str(27, 16, "Gregario")))
        // ERG columns for intervals.icu custom streams (Charts > Custom Streams > Record field erg_level, ...).
        w.developerDataId("GregarioERGData1".toByteArray(Charsets.US_ASCII))
        w.developerField(0, T.UINT16, "erg_target", "W")
        w.developerField(1, T.UINT8, "erg_level", "%")
        w.developerField(2, T.UINT8, "erg_phase", "")
        w.developerField(3, T.UINT8, "erg_moves", "")
        w.message(21, listOf(F(253, T.UINT32, FitWriter.fitTime(start)), F(0, T.ENUM, 0), F(1, T.ENUM, 0)))   // timer start
        var dist = 0.0
        for (s in samples) {
            dist += s.speed * s.secs
            w.message(20, listOf(F(253, T.UINT32, FitWriter.fitTime(s.wallMs)),
                F(3, T.UINT8, s.heartRate?.toLong()), F(4, T.UINT8, s.cadence.toLong()),
                F(5, T.UINT32, Math.round(dist * 100)), F(6, T.UINT16, Math.round(s.speed * 1000)), F(7, T.UINT16, s.power.toLong()),
                // Record field 10 (resistance): the Bike+ level as the bike shows it, 0-100, for a resistance chart.
                F(10, T.UINT8, s.resistance?.coerceIn(0, 254)?.toLong())),
                devs = listOf(FitWriter.Dev(0, T.UINT16, s.erg?.target?.coerceIn(0, 65534)?.toLong()),
                    FitWriter.Dev(1, T.UINT8, s.erg?.level?.coerceIn(0, 254)?.toLong()),
                    FitWriter.Dev(2, T.UINT8, s.erg?.phase?.toLong()),
                    FitWriter.Dev(3, T.UINT8, s.erg?.writes?.coerceIn(0, 254)?.toLong())))
        }

        // Laps: one per workout step ridden (consecutive samples in the same step).
        val laps = ArrayList<List<Sample>>()
        for (s in samples) if (laps.isEmpty() || laps.last().last().step != s.step) laps += mutableListOf(s) else (laps.last() as MutableList).add(s)
        val firstHard = workout.steps.indexOfFirst { pctOf(it) >= 76 }
        laps.forEachIndexed { i, lap ->
            val t = totals(lap)
            val lapStart = lap.first().wallMs - (lap.first().secs * 1000).toLong()
            val step = lap.first().step
            val hard = workout.steps.getOrNull(step)?.let { pctOf(it) >= 76 } == true
            val intensity = when {
                hard -> 5L                                        // interval
                firstHard >= 0 && step < firstHard -> 2L          // warm-up
                i == laps.lastIndex -> 3L                         // cool-down
                else -> 4L                                        // recovery
            }
            w.message(19, listOf(F(254, T.UINT16, i.toLong()), F(253, T.UINT32, FitWriter.fitTime(lap.last().wallMs)),
                F(0, T.ENUM, 9), F(1, T.ENUM, 1), F(2, T.UINT32, FitWriter.fitTime(lapStart)),
                F(7, T.UINT32, (lap.last().wallMs - lapStart)), F(8, T.UINT32, Math.round(t.seconds * 1000)),
                F(9, T.UINT32, Math.round(t.distance * 100)), F(13, T.UINT16, Math.round(t.avgSpeed * 1000)),
                F(14, T.UINT16, Math.round(t.maxSpeed * 1000)),
                F(15, T.UINT8, t.avgHr?.toLong()), F(16, T.UINT8, t.maxHr?.toLong()),
                F(17, T.UINT8, t.avgCadence?.toLong()), F(18, T.UINT8, t.maxCadence?.toLong()),
                F(19, T.UINT16, t.avgPower.toLong()), F(20, T.UINT16, t.maxPower.toLong()),
                F(23, T.ENUM, intensity), F(24, T.ENUM, if (i == laps.lastIndex) 7L else 0L),
                F(25, T.ENUM, SPORT_CYCLING), F(39, T.ENUM, SUB_SPORT_INDOOR_CYCLING),
                F(41, T.UINT32, Math.round(lap.sumOf { it.power * it.secs })), F(71, T.UINT16, step.toLong())))
        }
        w.message(21, listOf(F(253, T.UINT32, FitWriter.fitTime(end)), F(0, T.ENUM, 0), F(1, T.ENUM, 4)))   // timer stop all

        val t = totals()
        w.message(18, listOf(F(254, T.UINT16, 0), F(253, T.UINT32, FitWriter.fitTime(end)), F(0, T.ENUM, 8), F(1, T.ENUM, 1),
            F(2, T.UINT32, FitWriter.fitTime(start)), F(5, T.ENUM, SPORT_CYCLING), F(6, T.ENUM, SUB_SPORT_INDOOR_CYCLING),
            F(7, T.UINT32, end - start), F(8, T.UINT32, Math.round(t.seconds * 1000)),
            F(9, T.UINT32, Math.round(t.distance * 100)), F(14, T.UINT16, Math.round(t.avgSpeed * 1000)),
            F(15, T.UINT16, Math.round(t.maxSpeed * 1000)),
            F(16, T.UINT8, t.avgHr?.toLong()), F(17, T.UINT8, t.maxHr?.toLong()),
            F(18, T.UINT8, t.avgCadence?.toLong()), F(19, T.UINT8, t.maxCadence?.toLong()),
            F(20, T.UINT16, t.avgPower.toLong()), F(21, T.UINT16, t.maxPower.toLong()),
            F(25, T.UINT16, 0), F(26, T.UINT16, laps.size.toLong()), F(28, T.ENUM, 0),
            F(34, T.UINT16, t.np.toLong()), F(35, T.UINT16, Math.round(t.tss * 10)), F(36, T.UINT16, Math.round(t.intensityFactor * 1000)),
            F(45, T.UINT16, ftp.toLong().takeIf { it > 0 }), F(48, T.UINT32, Math.round(samples.sumOf { it.power * it.secs }))))
        val offset = TimeZone.getDefault().getOffset(end) / 1000
        w.message(34, listOf(F(253, T.UINT32, FitWriter.fitTime(end)), F(0, T.UINT32, Math.round(t.seconds * 1000)),
            F(1, T.UINT16, 1), F(2, T.ENUM, 0), F(3, T.ENUM, 26), F(4, T.ENUM, 1),
            F(5, T.UINT32, FitWriter.fitTime(end) + offset)))
        return w.bytes()
    }

    private fun pctOf(s: Step) = when (s) { is Step.Steady -> s.pct; is Step.Ramp -> maxOf(s.startPct, s.endPct); is Step.Free -> 0.0 }

    companion object {
        /** intervals.icu's power zones (% FTP, top of Z1 to top of Z6), as Marc's settings have them. */
        private val ZONE_TOPS = doubleArrayOf(55.0, 75.0, 90.0, 105.0, 120.0, 150.0)
        fun zoneOf(pct: Double): Int = ZONE_TOPS.indexOfFirst { pct <= it }.let { if (it < 0) 7 else it + 1 }
        const val Z1_TOP_PCT = 55.0
        const val MERGE_PCT = 5.0
        const val MIN_INTERVAL_SECS = 10

        const val MPH_TO_MPS = 0.44704
        const val MANUFACTURER_DEVELOPMENT = 255L
        const val PRODUCT = 0x4752L          // "GR"
        const val SPORT_CYCLING = 2L
        const val SUB_SPORT_INDOOR_CYCLING = 6L
    }
}
