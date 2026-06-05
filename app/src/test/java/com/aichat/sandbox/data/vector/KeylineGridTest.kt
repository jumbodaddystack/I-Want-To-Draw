package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — keyline-grid geometry derives every figure from [edge] by ratio. */
class KeylineGridTest {

    @Test
    fun safeZone_insets_by_padding() {
        val grid = KeylineGrid(edge = 24f, padding = 1f)
        val sz = grid.safeZone()
        assertEquals(1f, sz.l, EPS)
        assertEquals(1f, sz.t, EPS)
        assertEquals(23f, sz.r, EPS)
        assertEquals(23f, sz.b, EPS)
    }

    @Test
    fun circle_diameter_is_material_20_on_24_grid() {
        val grid = KeylineGrid(edge = 24f, shapes = setOf(KeylineShape.CIRCLE))
        val circle = grid.shapeFigures().filterIsInstance<KeylineGrid.Circle>().single()
        assertEquals(12f, circle.cx, EPS)
        assertEquals(12f, circle.cy, EPS)
        assertEquals(10f, circle.r, EPS)
    }

    @Test
    fun forViewport_scales_edge_to_viewportWidth() {
        val grid48 = KeylinePresets.forViewport(VectorViewport(48f, 48f, 48f, 48f))
        assertEquals(48f, grid48.edge, EPS)
        // Padding doubles (1 → 2), so the safe zone is exactly ×2 of the 24 grid.
        assertEquals(2f, grid48.padding, EPS)
        val sz = grid48.safeZone()
        assertEquals(2f, sz.l, EPS)
        assertEquals(46f, sz.r, EPS)
        // Circle radius doubles (10 → 20), centre at (24,24).
        val circle = grid48.shapeFigures().filterIsInstance<KeylineGrid.Circle>().single()
        assertEquals(20f, circle.r, EPS)
        assertEquals(24f, circle.cx, EPS)
    }

    @Test
    fun roundedSquare_corner_scales_linearly() {
        val grid = KeylineGrid(edge = 48f, shapes = setOf(KeylineShape.ROUNDED_SQUARE))
        val rr = grid.shapeFigures().filterIsInstance<KeylineGrid.RoundRect>().single()
        // Corner = 2 on 24 → 4 on 48; live square inset 3 on 24 → 6 on 48.
        assertEquals(4f, rr.corner, EPS)
        assertEquals(6f, rr.l, EPS)
        assertEquals(42f, rr.r, EPS)
    }

    @Test
    fun keylineLines_symmetric_about_center() {
        val grid = KeylineGrid(edge = 24f)
        val lines = grid.keylineLines()
        // The vertical centre line runs through x = edge/2 at both ends.
        val centerVertical = lines.firstOrNull { it.x0 == 12f && it.x1 == 12f }
        assertTrue("expected a centre cross line at x=12", centerVertical != null)
        val centerHorizontal = lines.firstOrNull { it.y0 == 12f && it.y1 == 12f }
        assertTrue("expected a centre cross line at y=12", centerHorizontal != null)
    }

    companion object {
        private const val EPS = 1e-4f
    }
}
