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
}
