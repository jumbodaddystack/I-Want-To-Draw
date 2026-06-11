package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StickyCodecTest {

    private fun sample(body: String = "Plan the sprint\n• demo") = StickyCodec.StickyPayload(
        minX = 100f, minY = -40f, maxX = 260f, maxY = 120f,
        fillArgb = StickyCodec.PRESET_FILLS[2],
        fontSize = 22f,
        body = body,
    )

    @Test
    fun roundTrip() {
        val payload = sample()
        val decoded = StickyCodec.decode(StickyCodec.encode(payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun roundTripEmptyBody() {
        val payload = sample(body = "")
        assertEquals(payload, StickyCodec.decode(StickyCodec.encode(payload)))
    }

    @Test
    fun roundTripUnicodeBody() {
        val payload = sample(body = "héllo 🌍 — multi-byte")
        assertEquals(payload, StickyCodec.decode(StickyCodec.encode(payload)))
    }

    @Test
    fun futureTrailingFieldsAreIgnored() {
        // The strokeStyle convention: a future build may append fields after
        // the body. Today's decoder must read the known prefix and ignore
        // the tail rather than throw.
        val payload = sample()
        val base = StickyCodec.encode(payload)
        val extended = ByteBuffer.allocate(base.size + 5).order(ByteOrder.LITTLE_ENDIAN)
            .put(base)
            .putInt(0x12345678)
            .put(7.toByte())
            .array()
        assertEquals(payload, StickyCodec.decode(extended))
    }

    @Test
    fun newAtCentresTheRect() {
        val payload = StickyCodec.newAt(centerX = 50f, centerY = -10f, fillArgb = 0xFF112233.toInt())
        assertEquals(50f - StickyCodec.DEFAULT_SIZE_WORLD / 2f, payload.minX, 1e-4f)
        assertEquals(50f + StickyCodec.DEFAULT_SIZE_WORLD / 2f, payload.maxX, 1e-4f)
        assertEquals(-10f - StickyCodec.DEFAULT_SIZE_WORLD / 2f, payload.minY, 1e-4f)
        assertEquals(StickyCodec.DEFAULT_SIZE_WORLD, payload.width, 1e-4f)
        assertEquals(StickyCodec.DEFAULT_SIZE_WORLD, payload.height, 1e-4f)
    }

    @Test
    fun transformTranslation() {
        val payload = sample()
        val moved = StickyCodec.transform(payload, StrokeTransform.translation(10f, -20f))
        assertEquals(payload.minX + 10f, moved.minX, 1e-3f)
        assertEquals(payload.maxY - 20f, moved.maxY, 1e-3f)
        assertEquals(payload.fontSize, moved.fontSize, 1e-3f)
        assertEquals(payload.body, moved.body)
        assertEquals(payload.fillArgb, moved.fillArgb)
    }

    @Test
    fun transformScaleScalesFont() {
        val payload = sample()
        val scaled = StickyCodec.transform(
            payload, StrokeTransform.scaleAround(2f, 2f, 0f, 0f),
        )
        assertEquals(payload.width * 2f, scaled.width, 1e-2f)
        assertEquals(payload.height * 2f, scaled.height, 1e-2f)
        assertEquals(payload.fontSize * 2f, scaled.fontSize, 1e-2f)
    }

    @Test
    fun transformRotationDegradesToEnvelope() {
        // 90° rotation around the rect centre: the (axis-aligned) sticky
        // keeps its area but swaps width/height via the corner envelope.
        val payload = sample()
        val cx = (payload.minX + payload.maxX) / 2f
        val cy = (payload.minY + payload.maxY) / 2f
        val rotated = StickyCodec.transform(
            payload,
            StrokeTransform.rotationAround((Math.PI / 2).toFloat(), cx, cy),
        )
        assertEquals(payload.height, rotated.width, 1e-2f)
        assertEquals(payload.width, rotated.height, 1e-2f)
        // Pure rotation has scale hint 1 — the font must not change.
        assertEquals(payload.fontSize, rotated.fontSize, 1e-2f)
    }

    @Test
    fun boundsMatchRect() {
        val payload = sample()
        assertArrayEquals(
            floatArrayOf(100f, -40f, 260f, 120f),
            StickyCodec.boundsOf(payload),
            1e-4f,
        )
    }

    @Test
    fun presetFillRosterHasEightOpaqueColours() {
        assertEquals(8, StickyCodec.PRESET_FILLS.size)
        assertEquals(8, StickyCodec.PRESET_FILLS.toSet().size)
        for (fill in StickyCodec.PRESET_FILLS) {
            assertTrue("fill should be opaque", (fill ushr 24) == 0xFF)
        }
    }
}
