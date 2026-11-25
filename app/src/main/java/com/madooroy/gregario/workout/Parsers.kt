package com.madooroy.gregario.workout

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads Zwift .zwo workouts (what most workout sites and apps offer).
 * Power values are FTP fractions. Files can come from anywhere (Open with Gregario), so DTDs are refused and a step
 * type this player can't ride is an error rather than silently left out (the workout would come out shorter).
 */
object ZwoParser {
    fun parse(xml: String): Workout {
        // Refuse DTDs and entities ourselves: Android's XML parser rejects the Xerces feature below (review D1), so on the
        // tablet it can't be relied on (it is still set where the parser supports it).
        require(!Regex("""<!\s*(DOCTYPE|ENTITY)""", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "Workout files with a DOCTYPE or ENTITY are not accepted" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val root = doc.documentElement
        fun text(tag: String) = root.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
        val body = root.getElementsByTagName("workout").item(0) as? Element ?: return Workout(text("name"), emptyList())
        val steps = mutableListOf<Step>()
        val kids = body.childNodes
        for (k in 0 until kids.length) {
            // Stop at the step limit while building (review N1): 1 MB of "Repeat=100" lines would otherwise build
            // millions of steps before the limit is checked, and run the app out of memory.
            require(steps.size <= Workout.MAX_STEPS) { "More than ${Workout.MAX_STEPS} steps" }
            val e = kids.item(k) as? Element ?: continue
            fun d(a: String) = e.getAttribute(a).toDoubleOrNull()
            fun i(a: String) = e.getAttribute(a).toDoubleOrNull()?.toInt()
            val note = (0 until e.childNodes.length).mapNotNull { (e.childNodes.item(it) as? Element) }
                .firstOrNull { it.tagName.equals("textevent", true) }?.getAttribute("message")?.takeIf { it.isNotBlank() }
            when (e.tagName) {
                // Some exporters write a steady step as a PowerLow–PowerHigh range instead of one Power.
                "SteadyState" -> steps += d("Power")?.let { Step.Steady(i("Duration") ?: 0, 100 * it, note) }
                    ?: Step.Steady(i("Duration") ?: 0, 100 * (d("PowerLow") ?: 0.0), note,
                        top = d("PowerHigh")?.let { 100 * it }?.takeIf { hi -> hi > 100 * (d("PowerLow") ?: 0.0) })
                "Warmup", "Cooldown", "Ramp" -> steps += Step.Ramp(i("Duration") ?: 0,
                    100 * (d("PowerLow") ?: 0.0), 100 * (d("PowerHigh") ?: 0.0), note)
                "IntervalsT" -> repeat((i("Repeat") ?: 1).also { require(it in 1..Workout.MAX_REPS) { "Repeat $it is out of range" } }) {
                    require(steps.size <= Workout.MAX_STEPS) { "More than ${Workout.MAX_STEPS} steps" }
                    steps += Step.Steady(i("OnDuration") ?: 0, 100 * (d("OnPower") ?: 0.0), note.takeIf { _ -> it == 0 })
                    steps += Step.Steady(i("OffDuration") ?: 0, 100 * (d("OffPower") ?: 0.0))
                }
                "FreeRide", "Freeride", "MaxEffort" -> steps += Step.Free(i("Duration") ?: 0, note ?: if (e.tagName == "MaxEffort") "All out" else null)
                "textevent", "TextEvent" -> {}
                else -> throw IllegalArgumentException("Step type ${e.tagName} isn't supported")
            }
        }
        return Workout(text("name"), steps.filter { it.seconds > 0 }, text("description"))
    }
}

/**
 * Reads .mrc (% FTP) and .erg (watts) course files: a [COURSE DATA] list of "minutes value" points joined by straight
 * lines. Two points at the same minute are a jump; equal values are a steady step, different values a ramp.
 * An .erg file's watts become % of the FTP in its header, or of [riderFtp] when it has none.
 */
object MrcParser {
    fun parse(text: String, riderFtp: Int, fallbackName: String = "Workout"): Workout {
        val lines = text.lines().map { it.trim() }
        fun header(key: String) = lines.firstOrNull { it.uppercase().startsWith(key) }?.substringAfter("=")?.trim()
        val watts = lines.any { it.uppercase().startsWith("MINUTES") && it.uppercase().contains("WATTS") }
        val ftp = header("FTP")?.toDoubleOrNull()?.takeIf { it > 0 } ?: riderFtp.toDouble()
        require(!watts || ftp > 0) { "This .erg file is in watts and there's no FTP to scale it" }
        val start = lines.indexOfFirst { it.equals("[COURSE DATA]", true) }
        require(start >= 0) { "No [COURSE DATA] section" }
        val pts = lines.drop(start + 1).takeWhile { !it.startsWith("[") }.mapNotNull { l ->
            val f = l.split(Regex("\\s+")); if (f.size < 2) null else
                f[0].toDoubleOrNull()?.let { m -> f[1].toDoubleOrNull()?.let { v -> m to (if (watts) 100 * v / ftp else v) } }
        }
        require(pts.size >= 2) { "No course points" }
        val steps = mutableListOf<Step>()
        for (k in 0 until pts.size - 1) {
            val (m0, v0) = pts[k]; val (m1, v1) = pts[k + 1]
            val secs = Math.round((m1 - m0) * 60).toInt()
            if (secs <= 0) continue
            steps += if (kotlin.math.abs(v1 - v0) < 0.05) Step.Steady(secs, v0) else Step.Ramp(secs, v0, v1)
        }
        val name = header("DESCRIPTION")?.takeIf { it.isNotBlank() } ?: fallbackName
        return Workout(name, steps)
    }
}

/** Any workout file Gregario can open, by its name or, failing that, its content. */
object WorkoutFile {
    fun parse(fileName: String, text: String, riderFtp: Int): Workout {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val base = fileName.substringBeforeLast('.').ifBlank { "Workout" }
        val w = when {
            ext == "zwo" || (ext !in setOf("mrc", "erg") && text.trimStart().startsWith("<")) -> ZwoParser.parse(text)
            ext == "mrc" || ext == "erg" || text.contains("[COURSE DATA]", true) -> MrcParser.parse(text, riderFtp, base)
            else -> throw IllegalArgumentException("Not a workout file Gregario can read (.zwo, .mrc or .erg)")
        }
        return if (w.name.isBlank()) w.copy(name = base) else w
    }
}

/** The same step with [extra] put in front of its own note. */
internal fun Step.withNote(extra: String?): Step {
    if (extra.isNullOrBlank()) return this
    val t = listOfNotNull(extra, text).joinToString(" · ")
    return when (this) { is Step.Steady -> copy(text = t); is Step.Ramp -> copy(text = t); is Step.Free -> copy(text = t) }
}

/**
 * Reads an intervals.icu planned workout (`workout_doc` of a calendar event).
 * Each step has `duration` (s) and `power` with `units` "%ftp" and either `value` or `start`/`end`.
 * `start`/`end` with `ramp: true` is a ramp; without it, a target range (ridden at its middle).
 * `reps` + nested `steps` is a repeat block; `text` is a note.
 * Throws IllegalArgumentException with a rider-readable reason when a workout cannot be ridden as planned:
 * a step not in %FTP, or steps with no time (distance, lap press) so the total does not match `workout_doc.duration`.
 * Checked against a real account's library and calendar (Nov 2026).
 */
object IcuParser {
    @Suppress("UNCHECKED_CAST")
    fun parseEvent(json: String): Workout {
        val ev = MiniJson.parse(json) as? Map<String, Any?> ?: emptyMap()   // anything else: no steps, skipped
        val doc = ev["workout_doc"] as? Map<String, Any?> ?: emptyMap()
        val w = Workout(ev["name"] as? String ?: "Workout", steps(doc["steps"] as? List<Any?> ?: emptyList()),
            ev["description"] as? String ?: "")
        val planned = (doc["duration"] as? Double)?.toInt()
        require(planned == null || kotlin.math.abs(planned - w.totalSeconds) <= 1) {
            "Some steps have no time (distance or lap?): ${w.totalSeconds / 60} of ${planned!! / 60} min"
        }
        return w
    }

    @Suppress("UNCHECKED_CAST")
    private fun steps(arr: List<Any?>): List<Step> {
        val out = mutableListOf<Step>()
        for (item in arr) {
            val s = item as? Map<String, Any?> ?: continue
            fun num(m: Map<String, Any?>?, k: String) = (m?.get(k) as? Double)
            val inner = s["steps"] as? List<Any?>
            if (inner != null) {
                // A block's own text ("Do not push big power here 3x") goes on its first step; a bare "3x" adds nothing.
                val blockText = (s["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.matches(Regex("\\d+\\s*[xX]")) }
                val block = steps(inner).mapIndexed { k, st -> if (k == 0) st.withNote(blockText) else st }
                val reps = (num(s, "reps") ?: 1.0).toInt()
                require(reps in 1..Workout.MAX_REPS) { "Repeat ${reps}x is out of range" }
                repeat(reps) { out += block }
                require(out.size <= Workout.MAX_STEPS) { "More than ${Workout.MAX_STEPS} steps" }
                continue
            }
            val sec = (num(s, "duration") ?: 0.0).toInt()
            // Cadence targets ride along as a note ("110 rpm", or a range "75–90 rpm").
            val cad = s["cadence"] as? Map<String, Any?>
            val rpm = cad?.let { c ->
                val v = num(c, "value"); val lo = num(c, "start"); val hi = num(c, "end")
                when {
                    v != null -> "${v.toInt()} rpm"
                    lo != null && hi != null -> "${minOf(lo, hi).toInt()}–${maxOf(lo, hi).toInt()} rpm"
                    else -> null
                }
            }
            val note = listOfNotNull((s["text"] as? String)?.takeIf { it.isNotBlank() }, rpm).joinToString(" · ").ifEmpty { null }
            val p = s["power"] as? Map<String, Any?>
            if (p == null || s["freeride"] == true) { out += Step.Free(sec, note); continue }
            val units = p["units"] as? String
            require(units == "%ftp") { "Only %FTP steps are supported (got ${units ?: "no units"})" }
            val start = num(p, "start"); val end = num(p, "end"); val value = num(p, "value")
            out += when {
                s["ramp"] == true -> {
                    require(start != null && end != null) { "A ramp without start and end" }
                    Step.Ramp(sec, start, end, note)
                }
                value != null -> Step.Steady(sec, value, note)
                // A range: ERG to the bottom; the rider may take the knob for the rest of the step.
                start != null && end != null -> if (start == end) Step.Steady(sec, start, note)
                    else Step.Steady(sec, minOf(start, end), note, top = maxOf(start, end))
                else -> throw IllegalArgumentException("A step without a power target")
            }
        }
        return out.filter { it.seconds > 0 }
    }
}
