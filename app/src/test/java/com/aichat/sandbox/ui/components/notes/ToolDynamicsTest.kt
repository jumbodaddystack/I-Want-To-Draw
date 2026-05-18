package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sin

class ToolDynamicsTest {

    private val halfPi = (Math.PI / 2.0).toFloat()
    private val base = 4f

    @Test
    fun penWidthIncreasesWithPressureAndIsTiltInvariant() {
        val light = ToolDynamics.pen(base, pressure = 0f, tiltRadians = 0f)
        val firm = ToolDynamics.pen(base, pressure = 1f, tiltRadians = 0f)
        val firmTilted = ToolDynamics.pen(base, pressure = 1f, tiltRadians = halfPi)

        assertEquals(base * 0.35f, light.widthPx, 1e-4f)
        assertEquals(base * 1.15f, firm.widthPx, 1e-4f)
        // Tilt is ignored for pen.
        assertEquals(firm.widthPx, firmTilted.widthPx, 1e-4f)
        assertEquals(1f, light.alpha, 0f)
        assertEquals(1f, firm.alpha, 0f)
    }

    @Test
    fun penPressureCurveIsMonotonic() {
        var prev = ToolDynamics.pen(base, 0f, 0f).widthPx
        for (i in 1..10) {
            val w = ToolDynamics.pen(base, i / 10f, 0f).widthPx
            assertTrue("pen width non-monotonic at $i: prev=$prev cur=$w", w >= prev)
            prev = w
        }
    }

    @Test
    fun pencilWidthBroadensWithTilt() {
        val upright = ToolDynamics.pencil(base, pressure = 1f, tiltRadians = 0f)
        val midTilt = ToolDynamics.pencil(base, pressure = 1f, tiltRadians = halfPi / 2f)
        val flat = ToolDynamics.pencil(base, pressure = 1f, tiltRadians = halfPi)

        assertEquals(base * 0.7f, upright.widthPx, 1e-4f)
        assertEquals(base * 1.6f, flat.widthPx, 1e-4f)
        assertTrue("mid tilt $midTilt should sit between $upright and $flat",
            midTilt.widthPx > upright.widthPx && midTilt.widthPx < flat.widthPx)
    }

    @Test
    fun pencilAlphaTracksPressure() {
        val light = ToolDynamics.pencil(base, pressure = 0f, tiltRadians = halfPi / 2f)
        val firm = ToolDynamics.pencil(base, pressure = 1f, tiltRadians = halfPi / 2f)

        assertEquals(0.35f, light.alpha, 1e-4f)
        assertEquals(1.0f, firm.alpha, 1e-4f)
        // sqrt curve — half-pressure lands above the midpoint of [0.35, 1.0].
        val half = ToolDynamics.pencil(base, pressure = 0.5f, tiltRadians = 0f)
        val expectedHalf = 0.35f + (1.0f - 0.35f) * 0.5.pow(0.5).toFloat()
        assertEquals(expectedHalf, half.alpha, 1e-4f)
    }

    @Test
    fun highlighterIsConstant() {
        val a = ToolDynamics.highlighter(base, pressure = 0f, tiltRadians = 0f)
        val b = ToolDynamics.highlighter(base, pressure = 1f, tiltRadians = halfPi)
        val c = ToolDynamics.highlighter(base, pressure = 0.4f, tiltRadians = halfPi / 2f)

        assertEquals(base, a.widthPx, 0f)
        assertEquals(base, b.widthPx, 0f)
        assertEquals(base, c.widthPx, 0f)
        assertEquals(ToolDynamics.HIGHLIGHTER_ALPHA, a.alpha, 0f)
        assertEquals(ToolDynamics.HIGHLIGHTER_ALPHA, b.alpha, 0f)
        assertEquals(ToolDynamics.HIGHLIGHTER_ALPHA, c.alpha, 0f)
    }

    @Test
    fun widthClampsBelowMinAndAboveMax() {
        // Tiny base + light pressure → would underflow without the clamp.
        val tiny = ToolDynamics.pen(0.1f, pressure = 0f, tiltRadians = 0f)
        assertTrue(tiny.widthPx >= ToolDynamics.MIN_WIDTH_PX)

        // Absurd base → clamped at the cap.
        val huge = ToolDynamics.pencil(1000f, pressure = 1f, tiltRadians = halfPi)
        assertEquals(ToolDynamics.MAX_WIDTH_PX, huge.widthPx, 0f)
    }

    @Test
    fun forToolDispatchesByToolId() {
        // Same input, dispatched by id, matches the explicit call.
        val expected = ToolDynamics.pencil(base, 0.5f, halfPi / 3f)
        val actual = ToolDynamics.forTool(StrokeRenderer.TOOL_PENCIL, base, 0.5f, halfPi / 3f)
        assertEquals(expected, actual)
    }

    @Test
    fun forToolFallsBackToPenOnUnknown() {
        val pen = ToolDynamics.pen(base, 0.5f, 0f)
        val unknown = ToolDynamics.forTool("future-tool", base, 0.5f, 0f)
        val nulled = ToolDynamics.forTool(null, base, 0.5f, 0f)
        assertEquals(pen, unknown)
        assertEquals(pen, nulled)
    }

    @Test
    fun pencilTiltFactorMatchesSinCurve() {
        val tilts = floatArrayOf(0f, halfPi / 4f, halfPi / 2f, halfPi * 3f / 4f, halfPi)
        for (t in tilts) {
            val style = ToolDynamics.pencil(base, pressure = 1f, tiltRadians = t)
            val tiltN = (t / halfPi).coerceIn(0f, 1f)
            val gain = sin(tiltN * halfPi)
            val expected = base * (0.7f + (1.6f - 0.7f) * gain)
            assertEquals("tilt=$t", expected, style.widthPx, 1e-4f)
        }
    }
}
