package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sub-phase 8.1 — geometric invariants for frames.
 *
 * `ViewportController.flyTo` reuses `fitToContent`; the goal is that a
 * frame's bounds end up centred in the viewport with margins applied.
 */
class FrameOverlayTest {

    @Test
    fun `flyTo centres a frame's bounds in the viewport`() {
        val controller = ViewportController()
        val frameBounds = floatArrayOf(100f, 200f, 300f, 400f)  // 200 x 200 world
        val canvas = floatArrayOf(1000f, 1000f)
        controller.flyTo(frameBounds, canvas, marginPx = 50f)
        // Frame centre = (200, 300). After flyTo, that point should map to
        // canvas centre = (500, 500).
        val cx = controller.worldToScreenX(200f)
        val cy = controller.worldToScreenY(300f)
        assertEquals(500f, cx, 0.5f)
        assertEquals(500f, cy, 0.5f)
    }

    @Test
    fun `flyTo respects MIN_SCALE for tiny frames`() {
        val controller = ViewportController()
        val tinyFrame = floatArrayOf(0f, 0f, 0.5f, 0.5f)
        val canvas = floatArrayOf(1000f, 1000f)
        controller.flyTo(tinyFrame, canvas)
        assertEquals(ViewportController.MAX_SCALE, controller.scale, 0.01f)
    }

    @Test
    fun `flyTo ignores empty bounds`() {
        val controller = ViewportController()
        val before = Triple(controller.offsetX, controller.offsetY, controller.scale)
        controller.flyTo(floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(800f, 600f))
        val after = Triple(controller.offsetX, controller.offsetY, controller.scale)
        assertEquals(before, after)
    }
}
