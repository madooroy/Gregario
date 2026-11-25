package com.madooroy.gregario.trainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeChoiceTest {
    @Test fun `Grupetto is this tablet`() {
        assertEquals("127.0.0.1", BikeChoice.DEFAULT.host)
        assertEquals("Grupetto", BikeChoice.DEFAULT.name)
    }

    @Test fun `SmartSpin2K uses its typed address, trimmed`() {
        assertEquals("192.168.1.57", BikeChoice(BikeChoice.Kind.SS2K, " 192.168.1.57 ").host)
        assertEquals("smartspin2k.local", BikeChoice(BikeChoice.Kind.SS2K, "smartspin2k.local").host)
    }

    @Test fun `SmartSpin2K without a usable address has no host`() {
        assertNull(BikeChoice(BikeChoice.Kind.SS2K, "").host)
        assertNull(BikeChoice(BikeChoice.Kind.SS2K, "192.168.1").host)
        assertTrue(BikeChoice(BikeChoice.Kind.SS2K, "").lookFor.contains("settings"))
    }

    @Test fun `addresses and names`() {
        assertTrue(BikeChoice.validHost("10.0.0.255"))
        assertTrue(BikeChoice.validHost("ss2k"))
        assertFalse(BikeChoice.validHost("192.168.1.300"))
        assertFalse(BikeChoice.validHost("192.168.1"))
        assertFalse(BikeChoice.validHost("http://192.168.1.5"))
        assertFalse(BikeChoice.validHost("192.168.1.5:8081"))
        assertFalse(BikeChoice.validHost("my bike"))
        assertFalse(BikeChoice.validHost("-bad.local"))
    }

    @Test fun `unknown stored kind falls back to Grupetto`() {
        assertEquals(BikeChoice.Kind.GRUPETTO, BikeChoice.Kind.of(null))
        assertEquals(BikeChoice.Kind.GRUPETTO, BikeChoice.Kind.of("wahoo"))
        assertEquals(BikeChoice.Kind.SS2K, BikeChoice.Kind.of("ss2k"))
    }
}
