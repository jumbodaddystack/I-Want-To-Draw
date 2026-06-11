package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Phase 10.1 — [ItemTransformer] was extracted from
 * `EditorAction.TransformItems`; these tests pin the per-kind behaviour the
 * undo action depended on.
 */
class ItemTransformerTest {

    private fun item(kind: String, payload: ByteArray) = NoteItem(
        id = "item-1",
        noteId = "note-1",
        zIndex = 0,
        kind = kind,
        tool = null,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = payload,
    )

    @Test
    fun strokeSamplesTranslate() {
        val samples = floatArrayOf(
            0f, 0f, 0.5f, 0f,
            10f, 10f, 0.5f, 0f,
        )
        val source = item(NoteItem.KIND_STROKE, StrokeCodec.encode(samples))
        val moved = ItemTransformer.transform(source, StrokeTransform.translation(5f, -2f))
        val decoded = StrokeCodec.decode(moved.payload)
        assertEquals(5f, decoded[0], 0f)
        assertEquals(-2f, decoded[1], 0f)
        assertEquals(15f, decoded[4], 0f)
        assertEquals(8f, decoded[5], 0f)
        // Pressure / tilt lanes untouched.
        assertEquals(0.5f, decoded[2], 0f)
    }

    @Test
    fun strokeTransformRoundTripsThroughInverse() {
        val samples = floatArrayOf(1f, 2f, 0.7f, 0.1f, 3f, 4f, 0.6f, 0.2f)
        val source = item(NoteItem.KIND_STROKE, StrokeCodec.encode(samples))
        val m = StrokeTransform.multiply(
            StrokeTransform.translation(12f, -7f),
            StrokeTransform.scaleAround(2f, 3f, 1f, 1f),
        )
        val there = ItemTransformer.transform(source, m)
        val back = ItemTransformer.transform(there, StrokeTransform.invert(m))
        assertArrayEquals(samples, StrokeCodec.decode(back.payload), 1e-3f)
    }

    @Test
    fun shapeTransformPreservesFillAndStrokeStyle() {
        val payload = ShapeCodec.encode(
            Shape.Ellipse(10f, 10f, 5f, 5f, 0f),
            fillArgb = 0x40FF0000,
            strokeStyle = ShapeCodec.STROKE_STYLE_DOTTED,
        )
        val moved = ItemTransformer.transform(
            item(Shape.KIND, payload),
            StrokeTransform.translation(10f, 0f),
        )
        val decoded = ShapeCodec.decode(moved.payload)
        assertEquals(Shape.Ellipse(20f, 10f, 5f, 5f, 0f), decoded.shape)
        assertEquals(0x40FF0000, decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_DOTTED, decoded.strokeStyle)
    }

    @Test
    fun textTransformComposesMatrixOnly() {
        val payload = TextItemCodec.encode(
            TextItemCodec.newAt(
                worldX = 100f,
                worldY = 50f,
                body = "hello",
                fontSize = 24f,
                alignment = TextItemCodec.ALIGN_LEFT,
            )
        )
        val moved = ItemTransformer.transform(
            item(TextItemCodec.KIND, payload),
            StrokeTransform.translation(-10f, 5f),
        )
        val decoded = TextItemCodec.decode(moved.payload)
        assertEquals("hello", decoded.body)
        assertEquals(24f, decoded.fontSize, 0f)
        assertEquals(90f, decoded.matrix[2], 0f)
        assertEquals(55f, decoded.matrix[5], 0f)
    }

    @Test
    fun unknownKindPassesThroughUntouched() {
        val source = item("future-kind", byteArrayOf(1, 2, 3))
        val out = ItemTransformer.transform(source, StrokeTransform.translation(1f, 1f))
        assertSame(source, out)
    }
}
