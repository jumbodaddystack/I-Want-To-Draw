package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — integer/pixel-grid quantization: unconditional, bounded, idempotent. */
class EditSnapTest {

    @Test
    fun quantize_rounds_to_nearest_integer() {
        val r = EditSnap.quantize(12.7f, 3.2f)
        assertEquals(13f, r.x, EPS)
        assertEquals(3f, r.y, EPS)
        assertTrue(r.snapped)
    }

    @Test
    fun quantizeInBounds_never_leaves_artboard() {
        val bounds = floatArrayOf(0f, 0f, 24f, 24f)
        val low = EditSnap.quantizeInBounds(-3.4f, -0.6f, bounds)
        assertEquals(0f, low.x, EPS)
        assertEquals(0f, low.y, EPS)
        val high = EditSnap.quantizeInBounds(23.6f, 99f, bounds)
        assertEquals(24f, high.x, EPS)
        assertEquals(24f, high.y, EPS)
    }

    @Test
    fun quantize_is_idempotent_for_already_integer_input() {
        val once = EditSnap.quantize(7f, 9f)
        val twice = EditSnap.quantize(once.x, once.y)
        assertEquals(once.x, twice.x, EPS)
        assertEquals(once.y, twice.y, EPS)
    }

    @Test
    fun quantize_supports_device_pixel_step() {
        val r = EditSnap.quantize(9f, 21f, step = 2f)
        assertEquals(8f, r.x, EPS)
        assertEquals(20f, r.y, EPS)
    }

    companion object {
        private const val EPS = 1e-4f
    }
}
