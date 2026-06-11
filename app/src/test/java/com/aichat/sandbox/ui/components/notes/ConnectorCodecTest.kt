package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConnectorCodecTest {

    private fun sample(
        fromId: String? = "item-a",
        toId: String? = "item-b",
    ) = ConnectorCodec.ConnectorPayload(
        fromItemId = fromId,
        fromAnchor = ConnectorCodec.ANCHOR_E,
        toItemId = toId,
        toAnchor = ConnectorCodec.ANCHOR_W,
        x0 = 10f, y0 = 20f, x1 = 300f, y1 = -45.5f,
        arrowAtEnd = true,
        arrowAtStart = false,
        strokeStyle = ShapeCodec.STROKE_STYLE_DASHED,
    )

    @Test
    fun roundTripBound() {
        val payload = sample()
        assertEquals(payload, ConnectorCodec.decode(ConnectorCodec.encode(payload)))
    }

    @Test
    fun roundTripFreeEndpoints() {
        val payload = sample(fromId = null, toId = null)
        val decoded = ConnectorCodec.decode(ConnectorCodec.encode(payload))
        assertNull(decoded.fromItemId)
        assertNull(decoded.toItemId)
        assertEquals(payload, decoded)
    }

    @Test
    fun roundTripMixedBinding() {
        val payload = sample(fromId = null, toId = "only-end").copy(
            arrowAtEnd = false,
            arrowAtStart = true,
            strokeStyle = ShapeCodec.STROKE_STYLE_DOTTED,
        )
        assertEquals(payload, ConnectorCodec.decode(ConnectorCodec.encode(payload)))
    }

    @Test
    fun futureTrailingFieldsAreIgnored() {
        val payload = sample()
        val base = ConnectorCodec.encode(payload)
        val extended = ByteBuffer.allocate(base.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(base)
            .putFloat(99f)
            .array()
        assertEquals(payload, ConnectorCodec.decode(extended))
    }

    @Test
    fun transformMovesFallbackOnly() {
        val payload = sample()
        val moved = ConnectorCodec.transform(payload, StrokeTransform.translation(5f, 7f))
        assertEquals(15f, moved.x0, 1e-4f)
        assertEquals(27f, moved.y0, 1e-4f)
        assertEquals(305f, moved.x1, 1e-4f)
        assertEquals(-38.5f, moved.y1, 1e-4f)
        // Bindings, anchors and style ride along untouched.
        assertEquals(payload.fromItemId, moved.fromItemId)
        assertEquals(payload.toItemId, moved.toItemId)
        assertEquals(payload.fromAnchor, moved.fromAnchor)
        assertEquals(payload.toAnchor, moved.toAnchor)
        assertEquals(payload.strokeStyle, moved.strokeStyle)
        assertEquals(payload.arrowAtEnd, moved.arrowAtEnd)
    }
}
