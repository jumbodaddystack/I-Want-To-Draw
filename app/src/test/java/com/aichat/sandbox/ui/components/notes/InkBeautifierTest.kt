package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sub-phase 14.1 — pins the commit-time beautify pass: endpoint
 * preservation, deviation reduction on a noisy line, the short-stroke
 * no-op, the output cap, stride-5 lane fidelity and determinism.
 */
class InkBeautifierTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    /** A horizontal line with per-sample vertical jitter. */
    private fun noisyLine(count: Int, jitter: Float): FloatArray {
        val out = FloatArray(count * stride)
        for (i in 0 until count) {
            out[i * stride] = i * 10f
            out[i * stride + 1] = jitter * sin(i * 2.4f)
            out[i * stride + 2] = 0.5f
            out[i * stride + 3] = 0.1f
        }
        return out
    }

    private fun maxDeviationFromAxis(samples: FloatArray, str: Int): Float {
        var worst = 0f
        for (i in 0 until samples.size / str) {
            worst = maxOf(worst, abs(samples[i * str + 1]))
        }
        return worst
    }

    @Test
    fun endpointsArePreserved() {
        val input = noisyLine(40, 3f)
        val out = InkBeautifier.beautify(input, stride)
        assertEquals(input[0], out[0], 1e-6f)
        assertEquals(input[1], out[1], 1e-6f)
        assertEquals(input[input.size - stride], out[out.size - stride], 1e-6f)
        assertEquals(input[input.size - stride + 1], out[out.size - stride + 1], 1e-6f)
    }

    @Test
    fun noisyLineGetsSmoother() {
        val input = noisyLine(60, 4f)
        val out = InkBeautifier.beautify(input, stride)
        val before = maxDeviationFromAxis(input, stride)
        val after = maxDeviationFromAxis(out, stride)
        assertTrue(
            "expected deviation to shrink (before=$before after=$after)",
            after < before * 0.7f,
        )
    }

    @Test
    fun shortStrokeReturnsUnchanged() {
        val input = noisyLine(5, 2f)
        val out = InkBeautifier.beautify(input, stride)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun outputIsCapped() {
        val input = noisyLine(900, 2f)
        val out = InkBeautifier.beautify(input, stride)
        // One Chaikin pass nearly doubles the count; the cap stops further
        // passes, mirroring the Clean-up behaviour.
        assertTrue("count=${out.size / stride}", out.size / stride <= 2 * 1024)
    }

    @Test
    fun strideFiveKeepsTimestampsMonotone() {
        val str = StrokeCodec.FLOATS_PER_SAMPLE_V2
        val count = 50
        val input = FloatArray(count * str)
        for (i in 0 until count) {
            input[i * str] = i * 8f
            input[i * str + 1] = 2.5f * sin(i * 1.9f)
            input[i * str + 2] = 0.6f
            input[i * str + 3] = 0.2f
            input[i * str + 4] = i * 4f // strictly increasing t (ms)
        }
        val out = InkBeautifier.beautify(input, str)
        var prevT = -Float.MAX_VALUE
        for (i in 0 until out.size / str) {
            val t = out[i * str + 4]
            assertTrue("t lane must stay monotone at sample $i", t >= prevT)
            prevT = t
        }
        assertEquals(0f, out[4], 1e-6f)
        assertEquals((count - 1) * 4f, out[out.size - str + 4], 1e-6f)
    }

    @Test
    fun beautifyIsDeterministic() {
        val input = noisyLine(70, 3f)
        val a = InkBeautifier.beautify(input.copyOf(), stride)
        val b = InkBeautifier.beautify(input.copyOf(), stride)
        assertArrayEquals(a, b, 0f)
    }
}
