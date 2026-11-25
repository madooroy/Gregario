package com.madooroy.gregario.trainer

/**
 * Which DirCon server Gregario rides through:
 * - Grupetto on this tablet (127.0.0.1:8081): Grupetto does the ERG with the Bike+ motor.
 * - A SmartSpin2K on the Wi-Fi (its address, port 8081): SS2K does the ERG by turning the knob, and gets its power
 *   from Grupetto's Bluetooth broadcast, so Grupetto stays on as the sensor but is never sent a target.
 *
 * SS2K's DirCon starts by itself whenever it is on Wi-Fi (no setting). Its address is typed in: Android doesn't
 * reliably resolve ".local" names, so an IP address (from the router, or SS2K's config app) is the safe choice.
 */
data class BikeChoice(val kind: Kind, val ss2kHost: String = "") {
    enum class Kind(val key: String, val label: String) {
        GRUPETTO("grupetto", "Grupetto"), SS2K("ss2k", "SmartSpin2K");
        companion object { fun of(key: String?) = values().firstOrNull { it.key == key } ?: GRUPETTO }
    }

    /** Where to connect, or null when SmartSpin2K is chosen without a usable address. */
    val host: String? get() = when (kind) {
        Kind.GRUPETTO -> "127.0.0.1"
        Kind.SS2K -> ss2kHost.trim().takeIf { validHost(it) }
    }

    /** The name the panel uses ("Looking for SmartSpin2K…", "No answer from SmartSpin2K"). */
    val name: String get() = kind.label

    /** What to do when it can't be reached, after "Looking for <name>: ". */
    val lookFor: String get() = when (kind) {
        Kind.GRUPETTO -> "open Grupetto and switch DirCon on"
        Kind.SS2K -> if (host == null) "type its address in Gregario's settings" else "check it's on and on this Wi-Fi ($ss2kHost)"
    }

    /**
     * The command that hands the bike back. Grupetto: FTMS Reset. SmartSpin2K: neutral simulation (flat, no wind or
     * rolling terms), because SS2K acknowledges Reset but keeps holding the last ERG target.
     */
    val handBack: ByteArray get() = when (kind) {
        Kind.GRUPETTO -> byteArrayOf(DirConLink.OP_RESET.toByte())
        Kind.SS2K -> NEUTRAL_SIMULATION.copyOf()
    }

    /** A SmartSpin2K keeps holding its last target when the connection drops (its firmware only logs it); Grupetto lets go. */
    val holdsAfterDisconnect: Boolean get() = kind == Kind.SS2K

    /** A SmartSpin2K handles a stopped rider itself (min watts, target restored on pedalling): auto-pause keeps ERG. */
    val holdsThroughStops: Boolean get() = kind == Kind.SS2K

    /** A SmartSpin2K announces its ERG shifts (FTMS status 0x08); Grupetto's 0x08 only echoes our own writes. */
    val followsShifts: Boolean get() = kind == Kind.SS2K

    companion object {
        /** FTMS Set Indoor Bike Simulation Parameters (0x11): wind 0, grade 0, rolling 0, wind resistance 0. */
        val NEUTRAL_SIMULATION = byteArrayOf(0x11, 0, 0, 0, 0, 0, 0)

        val DEFAULT = BikeChoice(Kind.GRUPETTO)

        private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
        private val NAME = Regex("""[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*""")

        /** An IPv4 address (each part 0–255) or a plain host name ("smartspin2k.local"); no port, scheme or spaces. */
        fun validHost(h: String): Boolean {
            if (h.isEmpty() || h.length > 253) return false
            IPV4.matchEntire(h)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
            // Only digits and dots that failed the IPv4 test (e.g. "192.168.1") are a mistyped address, not a name.
            if (h.all { it.isDigit() || it == '.' }) return false
            return NAME.matches(h)
        }
    }
}
