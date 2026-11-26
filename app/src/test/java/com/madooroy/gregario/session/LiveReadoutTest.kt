package com.madooroy.gregario.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReadoutTest {
    @Test fun powerIsTheNewestReadingWithNoAveraging() {
        val r = LiveReadout()
        r.offer(0, 200f, 90f, 40)
        r.offer(200, 300.4f, 95f, 45)
        assertEquals(300, r.power)      // the newest reading, not a mean with 200
        assertEquals(95, r.cadence)
        assertEquals(45, r.resistance)
        r.offer(1000, 349.6f, 99f, 46)
        assertEquals(350, r.power)
    }

    @Test fun noFreshSampleClears() {
        val r = LiveReadout()
        r.offer(0, 200f, 90f, 40)
        r.offer(null, null, null, null)
        assertNull(r.power); assertNull(r.cadence); assertNull(r.resistance)
    }
}
