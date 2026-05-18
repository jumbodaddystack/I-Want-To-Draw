package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportControllerTest {

    private val tol = 1e-4f

    @Test
    fun defaultsAreIdentity() {
        val vp = ViewportController()
        assertEquals(0f, vp.offsetX, 0f)
        assertEquals(0f, vp.offsetY, 0f)
        assertEquals(1f, vp.scale, 0f)
        assertEquals(120f, vp.screenToWorldX(120f), 0f)
        assertEquals(80f, vp.worldToScreenY(80f), 0f)
    }

    @Test
    fun screenToWorldRoundTripsForArbitraryViewport() {
        val vp = ViewportController(offsetX = 47f, offsetY = -23f, scale = 1.75f)
        for (sx in -200..200 step 37) {
            for (sy in -120..280 step 43) {
                val wx = vp.screenToWorldX(sx.toFloat())
                val wy = vp.screenToWorldY(sy.toFloat())
                assertEquals(sx.toFloat(), vp.worldToScreenX(wx), tol)
                assertEquals(sy.toFloat(), vp.worldToScreenY(wy), tol)
            }
        }
    }

    @Test
    fun applyPanShiftsOffsetAndFiresListener() {
        var fired = 0
        val vp = ViewportController(offsetX = 10f, offsetY = 20f)
        vp.onChanged = { fired++ }
        vp.applyPan(5f, -3f)
        assertEquals(15f, vp.offsetX, 0f)
        assertEquals(17f, vp.offsetY, 0f)
        assertEquals(1, fired)
        // Zero-delta pan is a no-op.
        vp.applyPan(0f, 0f)
        assertEquals(1, fired)
    }

    @Test
    fun zoomKeepsFocalPointWorldCoordInvariant() {
        val vp = ViewportController(offsetX = 50f, offsetY = 30f, scale = 1f)
        val fx = 200f
        val fy = 150f
        val worldBeforeX = vp.screenToWorldX(fx)
        val worldBeforeY = vp.screenToWorldY(fy)
        vp.applyZoom(fx, fy, 1.5f)
        // World coord under the focus screen point is unchanged after zoom.
        assertEquals(worldBeforeX, vp.screenToWorldX(fx), tol)
        assertEquals(worldBeforeY, vp.screenToWorldY(fy), tol)
        assertEquals(1.5f, vp.scale, tol)
    }

    @Test
    fun zoomChainedKeepsFocalPointInvariant() {
        val vp = ViewportController(offsetX = -10f, offsetY = 80f, scale = 1f)
        val fx = 320f
        val fy = 240f
        val worldBeforeX = vp.screenToWorldX(fx)
        val worldBeforeY = vp.screenToWorldY(fy)
        vp.applyZoom(fx, fy, 1.25f)
        vp.applyZoom(fx, fy, 1.6f)
        vp.applyZoom(fx, fy, 0.5f)
        assertEquals(worldBeforeX, vp.screenToWorldX(fx), tol)
        assertEquals(worldBeforeY, vp.screenToWorldY(fy), tol)
    }

    @Test
    fun zoomIsClampedToMinAndMax() {
        val vp = ViewportController(scale = 1f)
        vp.applyZoom(0f, 0f, 100f)
        assertEquals(ViewportController.MAX_SCALE, vp.scale, 0f)
        vp.applyZoom(0f, 0f, 0.0001f)
        assertEquals(ViewportController.MIN_SCALE, vp.scale, 0f)
    }

    @Test
    fun zoomAtClampReportsNoChange() {
        var fired = 0
        val vp = ViewportController(scale = ViewportController.MAX_SCALE)
        vp.onChanged = { fired++ }
        vp.applyZoom(50f, 50f, 4f) // already at max
        assertEquals(0, fired)
    }

    @Test
    fun negativeOrZeroZoomFactorsAreIgnored() {
        val vp = ViewportController(scale = 2f)
        vp.applyZoom(0f, 0f, 0f)
        vp.applyZoom(0f, 0f, -1f)
        assertEquals(2f, vp.scale, 0f)
    }

    @Test
    fun resetClearsState() {
        val vp = ViewportController(offsetX = 11f, offsetY = -7f, scale = 2.5f)
        var fired = 0
        vp.onChanged = { fired++ }
        vp.reset()
        assertEquals(0f, vp.offsetX, 0f)
        assertEquals(0f, vp.offsetY, 0f)
        assertEquals(1f, vp.scale, 0f)
        assertEquals(1, fired)
        vp.reset()
        assertEquals(1, fired) // idempotent no-op
    }

    @Test
    fun fitToContentScalesAndCentersBounds() {
        val vp = ViewportController(offsetX = 0f, offsetY = 0f, scale = 1f)
        // 1000-wide content into a 500-wide canvas with 24-px margins on
        // each side → usable = 452. scale = 452/1000 = 0.452.
        val bounds = floatArrayOf(-500f, -200f, 500f, 200f) // 1000 × 400 world
        val canvas = floatArrayOf(500f, 300f)
        vp.fitToContent(bounds, canvas)
        assertTrue(
            "scale should fit horizontally: ${vp.scale}",
            vp.scale in 0.4f..0.46f,
        )
        // Content centre `(0, 0)` should land at canvas centre.
        assertEquals(250f, vp.worldToScreenX(0f), tol)
        assertEquals(150f, vp.worldToScreenY(0f), tol)
    }

    @Test
    fun fitToContentIgnoresEmptyBoundsOrCanvas() {
        val vp = ViewportController(offsetX = 11f, offsetY = 22f, scale = 1.5f)
        val canvas = floatArrayOf(500f, 300f)
        // Zero-width bounds.
        vp.fitToContent(floatArrayOf(0f, 0f, 0f, 100f), canvas)
        assertEquals(1.5f, vp.scale, 0f)
        assertEquals(11f, vp.offsetX, 0f)
        // Zero-size canvas.
        vp.fitToContent(floatArrayOf(0f, 0f, 100f, 100f), floatArrayOf(0f, 300f))
        assertEquals(1.5f, vp.scale, 0f)
    }

    @Test
    fun centerOnContentPansWithoutScaling() {
        val vp = ViewportController(offsetX = 0f, offsetY = 0f, scale = 2f)
        val bounds = floatArrayOf(50f, 60f, 150f, 200f) // centre (100, 130)
        val canvas = floatArrayOf(400f, 300f)
        vp.centerOnContent(bounds, canvas)
        assertEquals(2f, vp.scale, 0f)
        // World centre (100, 130) under canvas centre (200, 150) at scale 2.
        // offsetX = 200 - 100*2 = 0; offsetY = 150 - 130*2 = -110.
        assertEquals(0f, vp.offsetX, tol)
        assertEquals(-110f, vp.offsetY, tol)
    }

    @Test
    fun resetToOneHundredPreservesCanvasCentreWorldPoint() {
        val vp = ViewportController(offsetX = -250f, offsetY = -150f, scale = 2f)
        val canvas = floatArrayOf(500f, 400f)
        val worldCxBefore = vp.screenToWorldX(canvas[0] * 0.5f)
        val worldCyBefore = vp.screenToWorldY(canvas[1] * 0.5f)
        vp.resetToOneHundred(canvas)
        assertEquals(1f, vp.scale, 0f)
        // Same world point should still be at the canvas centre.
        assertEquals(worldCxBefore, vp.screenToWorldX(canvas[0] * 0.5f), tol)
        assertEquals(worldCyBefore, vp.screenToWorldY(canvas[1] * 0.5f), tol)
    }

    @Test
    fun resetToOneHundredIsNoOpAtCorrectScale() {
        val vp = ViewportController(offsetX = 7f, offsetY = -9f, scale = 1f)
        var fired = 0
        vp.onChanged = { fired++ }
        vp.resetToOneHundred(floatArrayOf(500f, 400f))
        assertEquals(0, fired)
        assertEquals(7f, vp.offsetX, 0f)
        assertEquals(-9f, vp.offsetY, 0f)
    }

    @Test
    fun pinchAroundFingertipKeepsItUnderFingertip() {
        // Simulates a pinch where the user is squeezing around (fx, fy) on
        // screen. The world point under their fingertips must stay under
        // their fingertips throughout the gesture.
        val vp = ViewportController(offsetX = 25f, offsetY = 40f, scale = 1f)
        val fx = 500f
        val fy = 700f
        val worldX = vp.screenToWorldX(fx)
        val worldY = vp.screenToWorldY(fy)
        // Many small factors approximating a slow squeeze in & out.
        listOf(1.05f, 1.05f, 1.05f, 0.9f, 0.9f, 1.2f, 0.8f).forEach {
            vp.applyZoom(fx, fy, it)
            assertEquals(worldX, vp.screenToWorldX(fx), tol)
            assertEquals(worldY, vp.screenToWorldY(fy), tol)
        }
        assertTrue(vp.scale in ViewportController.MIN_SCALE..ViewportController.MAX_SCALE)
    }
}
