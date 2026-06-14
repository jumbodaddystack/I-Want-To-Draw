package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pen-size zoom scaling — pins the pure width math shared by [DrawingSurface]
 * (stroke start) and [StrokeRenderer] (per-segment render). Kept on the JVM
 * because the maths is Android-free; the Paint plumbing that consumes it is
 * exercised by instrumented tests.
 */
class ToolDynamicsZoomWidthTest {

    private val eps = 1e-4f

    // ── startWorldWidthPx (#1 screen-anchored) ───────────────────────────

    @Test
    fun screenAnchored_storesWidthDividedByZoom() {
        // Draw a 4px pen while zoomed in 2× → world width halves so it lands
        // at 4px on screen.
        assertEquals(2f, ToolDynamics.startWorldWidthPx(4f, 2f, screenAnchored = true, fixedWidth = false), eps)
        // Zoomed out to 0.25× → world width quadruples so it stays 4px on screen.
        assertEquals(16f, ToolDynamics.startWorldWidthPx(4f, 0.25f, screenAnchored = true, fixedWidth = false), eps)
        // At 100% the stored width equals the chosen width.
        assertEquals(4f, ToolDynamics.startWorldWidthPx(4f, 1f, screenAnchored = true, fixedWidth = false), eps)
    }

    @Test
    fun worldSpace_storesChosenWidthUnchanged() {
        // Screen-anchoring off → classic vector behaviour, width is world-space.
        assertEquals(4f, ToolDynamics.startWorldWidthPx(4f, 2f, screenAnchored = false, fixedWidth = false), eps)
        assertEquals(4f, ToolDynamics.startWorldWidthPx(4f, 0.25f, screenAnchored = false, fixedWidth = false), eps)
    }

    @Test
    fun fixedWidth_storesRawWidthRegardlessOfZoomOrAnchor() {
        // Fixed-width stores the raw screen width; the renderer divides zoom out.
        assertEquals(4f, ToolDynamics.startWorldWidthPx(4f, 2f, screenAnchored = true, fixedWidth = true), eps)
        assertEquals(4f, ToolDynamics.startWorldWidthPx(4f, 0.25f, screenAnchored = false, fixedWidth = true), eps)
    }

    @Test
    fun startWidth_guardsAgainstZeroZoom() {
        // A near-zero zoom must not blow the width up to infinity.
        assertEquals(4f, ToolDynamics.startWorldWidthPx(4f, 0f, screenAnchored = true, fixedWidth = false), eps)
    }

    // ── renderStrokeWidthPx (#2 fixed-width + #3 min-screen clamp) ────────

    @Test
    fun render_usesDynamicsWidthWhenNotFixed() {
        // No clamp, not fixed → the per-segment dynamics width passes through.
        assertEquals(
            6f,
            ToolDynamics.renderStrokeWidthPx(
                dynamicsWidthPx = 6f, baseWidthPx = 4f, viewportScale = 2f,
                fixedWidth = false, minScreenWidthPx = 0f,
            ),
            eps,
        )
    }

    @Test
    fun render_fixedWidthHoldsConstantOnScreen() {
        // Fixed width divides zoom out so width * scale == baseWidthPx on screen.
        val w = ToolDynamics.renderStrokeWidthPx(
            dynamicsWidthPx = 99f, baseWidthPx = 4f, viewportScale = 0.25f,
            fixedWidth = true, minScreenWidthPx = 0f,
        )
        assertEquals(16f, w, eps)            // world width
        assertEquals(4f, w * 0.25f, eps)     // on-screen width
    }

    @Test
    fun render_minScreenClampPreventsHairline() {
        // At 0.25× a 1px world stroke would paint 0.25px; the clamp lifts it so
        // the on-screen width is at least the floor.
        val floor = ToolDynamics.MIN_SCREEN_WIDTH_PX
        val w = ToolDynamics.renderStrokeWidthPx(
            dynamicsWidthPx = 1f, baseWidthPx = 1f, viewportScale = 0.25f,
            fixedWidth = false, minScreenWidthPx = floor,
        )
        assertTrue("expected clamp to apply", w >= floor / 0.25f - eps)
        assertEquals(floor, w * 0.25f, eps)
    }

    @Test
    fun render_minScreenClampInactiveWhenAlreadyThickEnough() {
        // A thick stroke is unaffected by the floor.
        val w = ToolDynamics.renderStrokeWidthPx(
            dynamicsWidthPx = 20f, baseWidthPx = 20f, viewportScale = 1f,
            fixedWidth = false, minScreenWidthPx = ToolDynamics.MIN_SCREEN_WIDTH_PX,
        )
        assertEquals(20f, w, eps)
    }
}
