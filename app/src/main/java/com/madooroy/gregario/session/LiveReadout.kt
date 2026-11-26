package com.madooroy.gregario.session

/**
 * The panel's live POWER / CADENCE / RES numbers, refreshed a few times a second (the workout loop runs once a
 * second, which made these lag Peloton's own display). Each is the newest bike reading as it arrives: Grupetto
 * already averages Peloton's noisy power over about a second, and a second average on top of it made the panel run
 * about 2 s behind Peloton's display (Nov 26 video). Null values (no fresh sample) clear the readout.
 */
class LiveReadout {
    var power: Int? = null; private set
    var cadence: Int? = null; private set
    var resistance: Int? = null; private set

    /** [timestamp] identifies the bike reading; null values (no fresh sample) clear the readout. */
    @Synchronized fun offer(timestamp: Long?, power: Float?, cadence: Float?, resistance: Int?) {
        if (timestamp == null || power == null || cadence == null) { clear(); return }
        this.power = Math.round(power)
        this.cadence = Math.round(cadence)
        this.resistance = resistance
    }

    @Synchronized fun clear() {
        power = null; cadence = null; resistance = null
    }
}
