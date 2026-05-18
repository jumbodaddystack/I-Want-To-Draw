package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSvgExporterTest {

    private fun emptyNote() = Note(
        id = "test",
        title = "Test",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 0f, maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )

    @Test
    fun emptyNoteEmitsValidStub() {
        val svg = NoteSvgExporter.renderSvg(emptyNote(), emptyList())
        assertTrue(svg.contains("<svg "))
        assertTrue(svg.contains("</svg>"))
        assertTrue(svg.contains("viewBox=\""))
    }

    @Test
    fun singleStrokeEmitsPath() {
        val samples = floatArrayOf(0f, 0f, 1f, 0f, 10f, 5f, 1f, 0f, 20f, 0f, 1f, 0f)
        val stroke = NoteItem(
            noteId = "test",
            zIndex = 0,
            kind = "stroke",
            tool = "pen",
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 2f,
            payload = StrokeCodec.encode(samples),
        )
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(stroke))
        assertTrue(svg.contains("<path d=\""))
        assertTrue(svg.contains("M0 0"))
    }

    @Test
    fun shapeLineEmitsLineElement() {
        val item = NoteItem(
            noteId = "test",
            zIndex = 1,
            kind = Shape.KIND,
            tool = "shape_line",
            colorArgb = 0xFFFF0000.toInt(),
            baseWidthPx = 3f,
            payload = ShapeCodec.encode(Shape.Line(10f, 20f, 30f, 40f)),
        )
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(item))
        assertTrue(svg.contains("<line "))
        assertTrue(svg.contains("x1=\"10\""))
        assertTrue(svg.contains("stroke=\"#FF0000\""))
    }

    @Test
    fun shapeRectEmitsRectElement() {
        val item = NoteItem(
            noteId = "test",
            zIndex = 2,
            kind = Shape.KIND,
            tool = "shape_rect",
            colorArgb = 0xFF0000FF.toInt(),
            baseWidthPx = 2f,
            payload = ShapeCodec.encode(Shape.Rect(0f, 0f, 50f, 25f, cornerRadius = 4f)),
        )
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(item))
        assertTrue(svg.contains("<rect "))
        assertTrue(svg.contains("rx=\"4\""))
        assertTrue(svg.contains("width=\"50\""))
    }

    @Test
    fun shapeEllipseEmitsEllipseElement() {
        val item = NoteItem(
            noteId = "test",
            zIndex = 3,
            kind = Shape.KIND,
            tool = "shape_ellipse",
            colorArgb = 0xFF00FF00.toInt(),
            baseWidthPx = 2f,
            payload = ShapeCodec.encode(Shape.Ellipse(50f, 50f, 25f, 15f)),
        )
        val svg = NoteSvgExporter.renderSvg(emptyNote(), listOf(item))
        assertTrue(svg.contains("<ellipse "))
        assertTrue(svg.contains("rx=\"25\""))
        assertTrue(svg.contains("ry=\"15\""))
    }
}
