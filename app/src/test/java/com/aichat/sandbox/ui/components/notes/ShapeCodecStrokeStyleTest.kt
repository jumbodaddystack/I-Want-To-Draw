package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 10.3 — the optional trailing `strokeStyle:u8` on the shape wire
 * format. Backward compatibility is the load-bearing property: payloads
 * written before the byte existed must decode as solid.
 */
class ShapeCodecStrokeStyleTest {

    private val allShapes: List<Shape> = listOf(
        Shape.Line(1f, 2f, 3f, 4f),
        Shape.Rect(0f, 0f, 100f, 50f, cornerRadius = 8f),
        Shape.Ellipse(10f, 20f, 30f, 40f, rotationRad = 0.5f),
        Shape.Arrow(0f, 0f, 100f, 0f, headSize = 12f),
        Shape.Polygon(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), closed = true),
    )

    private val allStyles = listOf(
        ShapeCodec.STROKE_STYLE_SOLID,
        ShapeCodec.STROKE_STYLE_DASHED,
        ShapeCodec.STROKE_STYLE_DOTTED,
    )

    @Test
    fun everyShapeRoundTripsEveryStyle() {
        for (shape in allShapes) {
            for (style in allStyles) {
                val decoded = ShapeCodec.decode(
                    ShapeCodec.encode(shape, fillArgb = 0x40FF0000, strokeStyle = style)
                )
                assertEquals("style for $shape", style, decoded.strokeStyle)
                assertEquals("fill for $shape", 0x40FF0000, decoded.fillArgb)
            }
        }
    }

    @Test
    fun legacyPayloadWithoutTrailingByteDecodesSolid() {
        // Hand-build the pre-Phase-10 layout: [type][4 floats][fillArgb] with
        // no trailing strokeStyle byte.
        val buf = ByteBuffer.allocate(1 + 4 * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(Shape.TYPE_LINE)
        buf.putFloat(1f); buf.putFloat(2f); buf.putFloat(3f); buf.putFloat(4f)
        buf.putInt(0x80123456.toInt())
        val decoded = ShapeCodec.decode(buf.array())
        assertEquals(Shape.Line(1f, 2f, 3f, 4f), decoded.shape)
        assertEquals(0x80123456.toInt(), decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_SOLID, decoded.strokeStyle)
    }

    @Test
    fun transformPreservesFillAndStyle() {
        val payload = ShapeCodec.encode(
            Shape.Rect(0f, 0f, 10f, 10f),
            fillArgb = 0x20ABCDEF,
            strokeStyle = ShapeCodec.STROKE_STYLE_DASHED,
        )
        val decoded = ShapeCodec.decode(payload)
        val moved = ShapeCodec.transform(decoded.shape, StrokeTransform.translation(5f, 5f))
        val roundTripped = ShapeCodec.decode(
            ShapeCodec.encode(moved, decoded.fillArgb, decoded.strokeStyle)
        )
        assertEquals(0x20ABCDEF, roundTripped.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_DASHED, roundTripped.strokeStyle)
        assertEquals(Shape.Rect(5f, 5f, 15f, 15f), roundTripped.shape)
    }
}
