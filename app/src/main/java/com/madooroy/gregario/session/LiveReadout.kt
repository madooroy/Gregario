package com.madooroy.gregario.session

/**
 * The panel's live POWER / CADENCE / RES numbers, refreshed a few times a second (the workout loop runs once a
 * second, which made these lag Peloton's own display). Power is the mean of the bike readings from the last
 * [windowMs] so it doesn't flicker; cadence and resistance are the newest reading. Each bike reading counts once,
 * however often it is offered (the same sample is seen again until the bike sends a new one).
 */
class LiveReadout(private val windowMs: Long = 1000) {
    private data class Reading(val at: Long, val power: Float)
    private val readings = ArrayDeque<Reading>()
    private var lastTimestamp = Long.MIN_VALUE

    var power: Int? = null; private set
    var cadence: Int? = null; private set
    var resistance: Int? = null; private set

    /** [timestamp] identifies the bike reading; null values (no fresh sample) clear the readout. */
    @Synchronized fun offer(timestamp: Long?, power: Float?, cadence: Float?, resistance: Int?) {
        if (timestamp == null || power == null || cadence == null) { clear(); return }
        if (timestamp != lastTimestamp) {
            lastTimestamp = timestamp
            readings.addLast(Reading(timestamp, power))
        }
        while (readings.isNotEmpty() && readings.first().at <= timestamp - windowMs) readings.removeFirst()
        this.power = readings.map { it.power }.average().takeIf { !it.isNaN() }?.let { Math.round(it).toInt() }
        this.cadence = Math.round(cadence)
        this.resistance = resistance
    }

    @Synchronized fun clear() {
        readings.clear(); lastTimestamp = Long.MIN_VALUE
        power = null; cadence = null; resistance = null
    }
}
