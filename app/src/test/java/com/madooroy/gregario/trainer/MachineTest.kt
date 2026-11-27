package com.madooroy.gregario.trainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MachineTest {
    @Test fun `the platform setting names the bike`() {
        assertEquals("Peloton Bike+", Machine.name("titan", "Peloton", "PLTN-TTR01"))
        assertEquals("Peloton Bike", Machine.name(" V1 ", "Peloton", "PLTN-RB1VQ"))
    }

    @Test fun `no setting - only a non Bike+ Peloton tablet is the original Bike`() {
        assertEquals("Peloton Bike", Machine.name(null, "Peloton", "PLTN-RB1VQ"))
        assertNull(Machine.name(null, "Peloton", "PLTN-TTR01"))      // Bike+, Tread or Row: can't tell
        assertNull(Machine.name("", "Peloton", "PLTN-TTR01"))
    }

    @Test fun `other machines and other tablets stay unnamed`() {
        assertNull(Machine.name("caesar", "Peloton", "PLTN-TTR01"))   // Row
        assertNull(Machine.name(null, "google", "sdk_gphone64_x86_64")) // the emulator
    }

    @Test fun `a Peloton tablet - the setting, the brand or Grupetto installed`() {
        org.junit.Assert.assertTrue(Machine.isPeloton("titan", "Peloton", false))
        org.junit.Assert.assertTrue(Machine.isPeloton(null, "PELOTON", false))
        org.junit.Assert.assertTrue(Machine.isPeloton("v1", "google", false))           // the emulator playing a Bike
        org.junit.Assert.assertTrue(Machine.isPeloton(null, "samsung", true))           // Grupetto installed: trust it
        org.junit.Assert.assertFalse(Machine.isPeloton(null, "samsung", false))
        org.junit.Assert.assertFalse(Machine.isPeloton(" ", "google", false))
    }
}
