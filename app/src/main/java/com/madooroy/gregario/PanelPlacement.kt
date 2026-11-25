package com.madooroy.gregario

/**
 * Where the panel sits on the screen: offsets in pixels from the nearest horizontal and vertical edges.
 * Anchoring to the nearest edges means the panel grows away from them (compact → full) and stays on screen,
 * wherever the rider dropped it.
 */
data class PanelPlacement(val right: Boolean, val bottom: Boolean, val x: Int, val y: Int) {
    /** Top-left corner on a [screenW]×[screenH] screen for a [w]×[h] panel, kept fully on screen. */
    fun topLeft(w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val left = if (right) screenW - w - x else x
        val top = if (bottom) screenH - h - y else y
        return left.coerceIn(0, maxOf(0, screenW - w)) to top.coerceIn(0, maxOf(0, screenH - h))
    }

    fun encode() = "${if (right) "R" else "L"},${if (bottom) "B" else "T"},$x,$y"

    /** For the settings button. */
    fun label() = (if (bottom) "bottom" else "top") + "-" + (if (right) "right" else "left") +
        if (x != INSET || y != INSET) " (moved)" else ""

    companion object {
        /** Gap to the screen edge. */
        const val INSET = 16
        /** Dropped within this many pixels of an edge: snaps to [INSET]. */
        const val SNAP = 48

        fun corner(right: Boolean, bottom: Boolean) = PanelPlacement(right, bottom, INSET, INSET)

        /** The four corners in turn, for the settings button. */
        fun nextCorner(p: PanelPlacement): PanelPlacement = when {
            p.bottom && !p.right -> corner(right = true, bottom = true)
            p.bottom && p.right -> corner(right = true, bottom = false)
            !p.bottom && p.right -> corner(right = false, bottom = false)
            else -> corner(right = false, bottom = true)
        }

        fun decode(s: String?): PanelPlacement? {
            val f = s?.split(",") ?: return null
            if (f.size != 4) return null
            val x = f[2].toIntOrNull() ?: return null; val y = f[3].toIntOrNull() ?: return null
            if (x < 0 || y < 0) return null
            return PanelPlacement(f[0] == "R", f[1] == "B", x, y)
        }

        /** Where a panel dropped with its top-left at ([left], [top]) belongs: anchored to the nearer edges, snapped when close. */
        fun dropped(left: Int, top: Int, w: Int, h: Int, screenW: Int, screenH: Int): PanelPlacement {
            val l = left.coerceIn(0, maxOf(0, screenW - w)); val t = top.coerceIn(0, maxOf(0, screenH - h))
            val right = l + w / 2 > screenW / 2
            val bottom = t + h / 2 > screenH / 2
            var x = if (right) screenW - w - l else l
            var y = if (bottom) screenH - h - t else t
            if (x < SNAP) x = INSET
            if (y < SNAP) y = INSET
            return PanelPlacement(right, bottom, x, y)
        }
    }
}
