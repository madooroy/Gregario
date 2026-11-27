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
}
