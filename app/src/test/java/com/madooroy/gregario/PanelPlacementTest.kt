package com.madooroy.gregario

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelPlacementTest {
    // Bike+ screen 1920x1080, panel 350x800 (full, 125%)
    private val sw = 1920; private val sh = 1080; private val w = 350; private val h = 800

    @Test fun `dropped near a corner snaps to it`() {
        assertEquals(PanelPlacement(right = true, bottom = true, x = 16, y = 16), PanelPlacement.dropped(1560, 270, w, h, sw, sh))
        assertEquals(PanelPlacement(right = false, bottom = true, x = 16, y = 16), PanelPlacement.dropped(5, 280, w, h, sw, sh))
        assertEquals(PanelPlacement(right = false, bottom = false, x = 16, y = 16), PanelPlacement.dropped(-40, -30, w, 130, sw, sh))
    }

    @Test fun `dropped mid-screen keeps its spot, anchored to the nearer edges`() {
        val p = PanelPlacement.dropped(1200, 100, w, 130, sw, sh)        // compact, upper right-ish
        assertEquals(PanelPlacement(right = true, bottom = false, x = 1920 - 350 - 1200, y = 100), p)
        assertEquals(1200 to 100, p.topLeft(w, 130, sw, sh))
        // Expanding grows downward from the top edge it is anchored to, and stays on screen.
        assertEquals(1200 to 100, p.topLeft(w, h, sw, sh))
    }

    @Test fun `a bottom-anchored panel grows upward and is kept on screen`() {
        val p = PanelPlacement(right = false, bottom = true, x = 16, y = 400)
        assertEquals(16 to (1080 - 130 - 400), p.topLeft(w, 130, sw, sh))
        assertEquals(16 to 0, p.topLeft(w, h, sw, sh))                    // would go off the top: clamped
    }

    @Test fun `saved and read back, old settings and the corner button`() {
        val p = PanelPlacement(right = true, bottom = false, x = 220, y = 100)
        assertEquals(p, PanelPlacement.decode(p.encode()))
        assertEquals(null, PanelPlacement.decode("junk"))
        assertEquals(null, PanelPlacement.decode(null))
        var c = PanelPlacement.corner(right = false, bottom = true)
        val seen = (1..4).map { c = PanelPlacement.nextCorner(c); c.label() }
        assertEquals(listOf("bottom-right", "top-right", "top-left", "bottom-left"), seen)
        assertEquals("top-right (moved)", p.label())
    }
}
