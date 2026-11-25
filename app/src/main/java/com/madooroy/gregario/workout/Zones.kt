package com.madooroy.gregario.workout

/**
 * Power zones and colors matching intervals.icu's default Ride settings (Coggan limits),
 * colors sampled from intervals.icu's zone legend (Nov 19, 2026).
 */
enum class Zone(val label: String, val upToPct: Double, val argb: Long) {
    Z1("Recovery", 55.0, 0xFF469C81),
    Z2("Endurance", 75.0, 0xFF469C2D),
    Z3("Tempo", 90.0, 0xFFF7CD47),
    Z4("Threshold", 105.0, 0xFFEF8635),
    Z5("VO2 Max", 120.0, 0xFFCB2C4C),
    Z6("Anaerobic", 150.0, 0xFF5F36C3),
    Z7("Neuromuscular", Double.POSITIVE_INFINITY, 0xFF50485F);

    companion object {
        /** Zone for a % FTP target; whole-% limits as in intervals.icu (55.4 -> Z1, 55.6 -> Z2). */
        fun of(pct: Double): Zone = entries.first { Math.round(pct) <= it.upToPct }
    }
}
