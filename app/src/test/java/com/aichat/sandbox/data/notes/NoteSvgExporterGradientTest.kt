package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.FillStyle
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.2 — SVG `<defs>` gradient wire format (pure int math, JVM-pinnable). */
class NoteSvgExporterGradientTest {

    private fun emptyNote() = Note(
        id = "test",
        title = "Test",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 0f, maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )

    private fun item(kind: String, payload: ByteArray) = NoteItem(
        noteId = "test",
        zIndex = 0,
        kind = kind,
        tool = null,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 3f,
        payload = payload,
    )

    private val linear = FillStyle.Gradient(
        type = FillStyle.TYPE_LINEAR,
        x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
        stops = listOf(
            FillStyle.Stop(0f, 0xFF2463EB.toInt()),
            FillStyle.Stop(1f, 0x809333EA.toInt()),
        ),
    )

    @Test
    fun gradientShapeEmitsDefsAndUrlFill() {
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(item(
                Shape.KIND,
                ShapeCodec.encode(
                    Shape.Rect(0f, 0f, 100f, 50f, 0f),
                    linear.firstStopArgb,
                    ShapeCodec.STROKE_STYLE_SOLID,
                    linear,
                ),
            )),
        )
        assertTrue(svg.contains("<defs>"))
        assertTrue(svg.contains("<linearGradient id=\"grad0\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">"))
        assertTrue(svg.contains("<stop offset=\"0\" stop-color=\"#2463EB\"/>"))
        // The translucent second stop carries stop-opacity.
        assertTrue(svg.contains("<stop offset=\"1\" stop-color=\"#9333EA\" stop-opacity=\"0.502\"/>"))
        assertTrue(svg.contains("fill=\"url(#grad0)\""))
    }

    @Test
    fun gradientStickyEmitsRadialDef() {
        val radial = FillStyle.radial(0xFFFDE047.toInt(), 0xFFF472B6.toInt())
        val sticky = StickyCodec.newAt(100f, 100f, 0xFFFDE047.toInt()).copy(gradient = radial)
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(item(StickyCodec.KIND, StickyCodec.encode(sticky))),
        )
        assertTrue(svg.contains("<radialGradient id=\"grad0\" cx=\"0.5\" cy=\"0.5\" r=\"0.7\">"))
        assertTrue(svg.contains("fill=\"url(#grad0)\""))
    }

    @Test
    fun gradientClosedPathEmitsDefAndUrlFill() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f),
                PathCodec.Anchor(80f, 0f),
                PathCodec.Anchor(80f, 60f),
            ),
            closed = true,
            fillArgb = linear.firstStopArgb,
            gradient = linear,
        )
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(item(PathCodec.KIND, PathCodec.encode(payload))),
        )
        assertTrue(svg.contains("<linearGradient id=\"grad0\""))
        assertTrue(svg.contains("fill=\"url(#grad0)\""))
    }

    @Test
    fun openPathGradientIsIgnored() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(PathCodec.Anchor(0f, 0f), PathCodec.Anchor(80f, 40f)),
            closed = false,
            gradient = linear,
        )
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(item(PathCodec.KIND, PathCodec.encode(payload))),
        )
        assertFalse(svg.contains("<defs>"))
        assertTrue(svg.contains("fill=\"none\""))
    }

    @Test
    fun noGradientsMeansNoDefs() {
        val svg = NoteSvgExporter.renderSvg(
            emptyNote(),
            listOf(item(
                Shape.KIND,
                ShapeCodec.encode(Shape.Rect(0f, 0f, 10f, 10f, 0f), 0x40000000),
            )),
        )
        assertFalse(svg.contains("<defs>"))
    }

    @Test
    fun twoGradientItemsGetDistinctIds() {
        val a = item(
            Shape.KIND,
            ShapeCodec.encode(Shape.Rect(0f, 0f, 10f, 10f, 0f), 0, gradient = linear),
        )
        val b = item(
            Shape.KIND,
            ShapeCodec.encode(
                Shape.Ellipse(50f, 50f, 20f, 10f, 0f), 0,
                gradient = FillStyle.radial(0xFF109F5C.toInt(), 0xFF06B6D4.toInt()),
            ),
        ).copy(zIndex = 1)
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(a, b))
        assertTrue(svg.contains("<linearGradient id=\"grad0\""))
        assertTrue(svg.contains("<radialGradient id=\"grad1\""))
        assertTrue(svg.contains("fill=\"url(#grad0)\""))
        assertTrue(svg.contains("fill=\"url(#grad1)\""))
    }
}
