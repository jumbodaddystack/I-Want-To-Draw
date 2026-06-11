package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 13.2 — gradient block round-trip + legacy decode on [ShapeCodec]. */
class ShapeCodecGradientTest {

    private val gradient = FillStyle.Gradient(
        type = FillStyle.TYPE_LINEAR,
        x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
        stops = listOf(
            FillStyle.Stop(0f, 0xFF2463EB.toInt()),
            FillStyle.Stop(0.5f, 0x809333EA.toInt()),
            FillStyle.Stop(1f, 0xFF9333EA.toInt()),
        ),
    )

    private val rect = Shape.Rect(0f, 0f, 100f, 50f, 0f)

    @Test
    fun gradientRoundTrips() {
        val decoded = ShapeCodec.decode(
            ShapeCodec.encode(rect, 0xFF2463EB.toInt(), ShapeCodec.STROKE_STYLE_DASHED, gradient),
        )
        assertEquals(gradient, decoded.gradient)
        assertEquals(0xFF2463EB.toInt(), decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_DASHED, decoded.strokeStyle)
    }

    @Test
    fun radialGradientRoundTrips() {
        val radial = FillStyle.radial(0xFFFDE047.toInt(), 0xFFF472B6.toInt())
        val decoded = ShapeCodec.decode(ShapeCodec.encode(rect, 0, gradient = radial))
        assertEquals(radial, decoded.gradient)
        assertEquals(FillStyle.TYPE_RADIAL, decoded.gradient!!.type)
    }

    @Test
    fun noGradientDecodesNull() {
        assertNull(ShapeCodec.decode(ShapeCodec.encode(rect, 0x40000000)).gradient)
    }

    @Test
    fun legacyPayloadEndingAtStrokeStyleDecodesNull() {
        // Pre-13.2 payloads end at strokeStyle — drop the trailing fillType
        // byte that today's encode appends.
        val full = ShapeCodec.encode(rect, 0x40000000, ShapeCodec.STROKE_STYLE_DOTTED)
        val legacy = full.copyOf(full.size - 1)
        val decoded = ShapeCodec.decode(legacy)
        assertNull(decoded.gradient)
        assertEquals(0x40000000, decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_DOTTED, decoded.strokeStyle)
    }

    @Test
    fun transformPreservesGradient() {
        val item = NoteItem(
            noteId = "n",
            zIndex = 0,
            kind = Shape.KIND,
            tool = null,
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 3f,
            payload = ShapeCodec.encode(rect, gradient.firstStopArgb, gradient = gradient),
        )
        val moved = ItemTransformer.transform(item, StrokeTransform.translation(40f, -10f))
        val decoded = ShapeCodec.decode(moved.payload)
        // Bounds-normalized geometry: the gradient block is byte-identical
        // after any affine.
        assertEquals(gradient, decoded.gradient)
        assertEquals(40f, ShapeCodec.boundsOf(decoded.shape)!![0], 1e-4f)
    }
}
