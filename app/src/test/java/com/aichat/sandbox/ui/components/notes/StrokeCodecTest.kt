package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class StrokeCodecTest {

    @Test
    fun emptyArrayRoundTrips() {
        val empty = FloatArray(0)
        val payload = StrokeCodec.encode(empty)
        assertEquals(0, payload.size)
        assertArrayEquals(empty, StrokeCodec.decode(payload), 0f)
    }

    @Test
    fun randomSamplesRoundTrip() {
        val rng = Random(0x5A5A_C0DEL)
        repeat(8) {
            val sampleCount = rng.nextInt(1, 256)
            val floats = FloatArray(sampleCount * StrokeCodec.FLOATS_PER_SAMPLE) {
                rng.nextFloat() * 2048f - 1024f
            }
            val decoded = StrokeCodec.decode(StrokeCodec.encode(floats))
            assertArrayEquals(floats, decoded, 0f)
        }
    }

    @Test
    fun encodedSizeMatchesSampleCount() {
        val floats = FloatArray(StrokeCodec.FLOATS_PER_SAMPLE * 10)
        assertEquals(StrokeCodec.BYTES_PER_SAMPLE * 10, StrokeCodec.encode(floats).size)
    }

    @Test
    fun encodeRejectsMisalignedFloatArray() {
        assertThrows(IllegalArgumentException::class.java) {
            StrokeCodec.encode(FloatArray(3))
        }
    }

    @Test
    fun decodeRejectsMisalignedByteArray() {
        // 12 bytes = 3 floats, not a multiple of 4-float samples.
        assertThrows(IllegalArgumentException::class.java) {
            StrokeCodec.decode(ByteArray(12))
        }
    }

    // ── Sub-phase 9.4 — v2 (per-sample timestamps) round-trip coverage ────

    @Test
    fun v2RoundTripsRandomSamples() {
        val rng = Random(0xC0FFEEL)
        repeat(8) {
            val sampleCount = rng.nextInt(1, 128)
            val v2 = FloatArray(sampleCount * StrokeCodec.FLOATS_PER_SAMPLE_V2) {
                rng.nextFloat() * 4096f - 2048f
            }
            val payload = StrokeCodec.encodeV2(v2)
            assertEquals(
                1 + sampleCount * StrokeCodec.BYTES_PER_SAMPLE_V2,
                payload.size,
            )
            assertEquals(true, StrokeCodec.isV2(payload))
            // decodeWithT preserves the full (x,y,p,tilt,t) lane.
            assertArrayEquals(v2, StrokeCodec.decodeWithT(payload), 0f)
        }
    }

    @Test
    fun v2DecodeStripsTimestampLane() {
        // decode() returns a v1-shaped FloatArray for both v1 and v2
        // payloads so existing callers (renderer, hit-test, SVG export)
        // don't have to branch.
        val v2Samples = floatArrayOf(
            10f, 20f, 0.5f, 0.1f, 100f,   // sample 0: (x,y,p,tilt,t)
            11f, 21f, 0.6f, 0.2f, 200f,   // sample 1
        )
        val payload = StrokeCodec.encodeV2(v2Samples)
        val v1Shape = StrokeCodec.decode(payload)
        assertEquals(2 * StrokeCodec.FLOATS_PER_SAMPLE, v1Shape.size)
        // Sample 0
        assertEquals(10f, v1Shape[0], 0f); assertEquals(20f, v1Shape[1], 0f)
        assertEquals(0.5f, v1Shape[2], 0f); assertEquals(0.1f, v1Shape[3], 0f)
        // Sample 1
        assertEquals(11f, v1Shape[4], 0f); assertEquals(21f, v1Shape[5], 0f)
        assertEquals(0.6f, v1Shape[6], 0f); assertEquals(0.2f, v1Shape[7], 0f)
    }

    @Test
    fun v1ReadbackThroughDecodeWithTSynthesizesZeroTimestamps() {
        val v1 = floatArrayOf(
            1f, 2f, 0.3f, 0.0f,
            3f, 4f, 0.5f, 0.0f,
        )
        val payload = StrokeCodec.encode(v1)
        assertEquals(false, StrokeCodec.isV2(payload))
        val v2Shape = StrokeCodec.decodeWithT(payload)
        assertEquals(2 * StrokeCodec.FLOATS_PER_SAMPLE_V2, v2Shape.size)
        // t-lane is synthesized as 0 for v1 strokes.
        assertEquals(0f, v2Shape[4], 0f)
        assertEquals(0f, v2Shape[9], 0f)
    }

    @Test
    fun v2EncodeRejectsMisalignedFloatArray() {
        // 7 floats = 1 full v2 sample + 2 leftovers.
        assertThrows(IllegalArgumentException::class.java) {
            StrokeCodec.encodeV2(FloatArray(7))
        }
    }
}
