package com.madooroy.gregario.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootStartTest {
    private val boot = "android.intent.action.BOOT_COMPLETED"

    @Test fun `only the boot broadcast counts`() = assertNull(BootStart.decide("something.else", true, true))
    @Test fun `off by default`() = assertEquals(BootStart.Off, BootStart.decide(boot, false, true))
    @Test fun `no permission, no panel`() = assertEquals(BootStart.NoPermission, BootStart.decide(boot, true, false))
    @Test fun `on with permission opens the panel`() = assertEquals(BootStart.OpenPanel, BootStart.decide(boot, true, true))
}
