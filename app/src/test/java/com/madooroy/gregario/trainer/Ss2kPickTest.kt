package com.madooroy.gregario.trainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ss2kPickTest {
    private val tablet = setOf("192.168.1.20")
    private val grupetto = DirConService("Grupetto 1A2B", "192.168.1.20")
    private val ss2k = DirConService("SmartSpin2k", "192.168.1.57")

    @Test fun `finds the SmartSpin2K and never Grupetto`() {
        assertEquals(ss2k, Ss2kPick.pick(listOf(grupetto, ss2k), tablet))
        assertNull(Ss2kPick.pick(listOf(grupetto), tablet))
    }

    @Test fun `anything on this tablet is skipped, whatever its name`() {
        assertNull(Ss2kPick.pick(listOf(DirConService("SmartSpin2k", "192.168.1.20")), tablet))
        assertNull(Ss2kPick.pick(listOf(DirConService("SmartSpin2k", "127.0.0.1")), tablet))
    }

    @Test fun `a SmartSpin name wins over another trainer`() {
        val kickr = DirConService("KICKR 7F3A", "192.168.1.80")
        assertEquals(ss2k, Ss2kPick.pick(listOf(kickr, ss2k, grupetto), tablet))
    }

    @Test fun `a renamed SS2K is found by its own service, never guessed`() {
        val renamed = DirConService("Garage bike", "192.168.1.57", "1826,${Ss2kPick.SS2K_SERVICE},180d")
        assertEquals(renamed, Ss2kPick.pick(listOf(grupetto, renamed), tablet))
        // A lone other trainer (or a renamed SS2K that lists no services yet) is never taken for the SS2K.
        assertNull(Ss2kPick.pick(listOf(grupetto, DirConService("KICKR 7F3A", "192.168.1.80", "1826")), tablet))
        assertNull(Ss2kPick.pick(listOf(DirConService("Garage bike", "192.168.1.57")), tablet))
    }

    @Test fun `the same device announced twice gives the same address`() {
        val again = DirConService("SmartSpin2k (2)", "192.168.1.57")
        assertEquals("192.168.1.57", Ss2kPick.pick(listOf(again, ss2k), tablet)?.host)
    }
}
