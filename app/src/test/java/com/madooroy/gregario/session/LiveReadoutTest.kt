package com.madooroy.gregario.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReadoutTest {
    @Test fun powerIsTheLastSecondsMeanAndARepeatedSampleCountsOnce() {
        val r = LiveReadout()
        r.offer(0, 200f, 90f, 40)
        r.offer(0, 200f, 90f, 40)       // same bike reading offered again
        r.offer(200, 300f, 95f, 45)
        assertEquals(250, r.power)
        assertEquals(95, r.cadence)
        assertEquals(45, r.resistance)
        // A second later only the newer readings are in the window.
        r.offer(1000, 320f, 99f, 45)
        r.offer(1200, 340f, 100f, 46)
        assertEquals(330, r.power)      // 320 and 340; 300 (at 200 ms) is a full second old
    }

    @Test fun noFreshSampleClears() {
        val r = LiveReadout()
        r.offer(0, 200f, 90f, 40)
        r.offer(null, null, null, null)
        assertNull(r.power); assertNull(r.cadence); assertNull(r.resistance)
    }
}
