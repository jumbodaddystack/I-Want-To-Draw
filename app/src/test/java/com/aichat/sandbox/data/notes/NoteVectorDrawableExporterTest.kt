package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteVectorDrawableExporterTest {

    private fun stroke() = NoteItem(
        noteId = "test", zIndex = 0, kind = "stroke", tool = "pen",
        colorArgb = 0xFF000000.toInt(), baseWidthPx = 2f,
        payload = StrokeCodec.encode(floatArrayOf(0f, 0f, 1f, 0f, 10f, 5f, 1f, 0f, 20f, 0f, 1f, 0f)),
    )

    @Test
    fun emptyEmitsValidVector() {
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(emptyList(), sizeDp = 24)
        assertTrue(xml.contains("<vector "))
        assertTrue(xml.contains("</vector>"))
        assertTrue(xml.contains("android:viewportWidth=\"24\""))
        assertTrue(xml.contains("android:width=\"24dp\""))
    }

    @Test
    fun chosenSizeFlowsToViewportAndDp() {
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(listOf(stroke()), sizeDp = 108)
        assertTrue(xml.contains("android:viewportWidth=\"108\""))
        assertTrue(xml.contains("android:viewportHeight=\"108\""))
        assertTrue(xml.contains("android:height=\"108dp\""))
    }

    @Test
    fun strokeEmitsStrokedPath() {
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(listOf(stroke()), sizeDp = 24)
        assertTrue(xml.contains("<path"))
        assertTrue(xml.contains("android:pathData=\"M"))
        assertTrue(xml.contains("android:strokeColor=\"#000000\""))
        assertTrue(xml.contains("android:fillColor=\"#00000000\""))
    }

    @Test
    fun rectWithCornerEmitsArcs() {
        val item = NoteItem(
            noteId = "test", zIndex = 0, kind = Shape.KIND, tool = "shape_rect",
            colorArgb = 0xFF0000FF.toInt(), baseWidthPx = 2f,
            payload = ShapeCodec.encode(Shape.Rect(0f, 0f, 50f, 50f, cornerRadius = 8f), fillArgb = 0xFFFF0000.toInt()),
        )
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(listOf(item), sizeDp = 24)
        assertTrue(xml.contains("android:pathData=\"M"))
        assertTrue("rounded rect should use elliptical arcs", xml.contains("A"))
        assertTrue(xml.contains("android:fillColor=\"#FF0000\""))
    }

    @Test
    fun ellipseEmitsArcPath() {
        val item = NoteItem(
            noteId = "test", zIndex = 0, kind = Shape.KIND, tool = "shape_ellipse",
            colorArgb = 0xFF00FF00.toInt(), baseWidthPx = 2f,
            payload = ShapeCodec.encode(Shape.Ellipse(25f, 25f, 20f, 20f)),
        )
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(listOf(item), sizeDp = 24)
        assertTrue(xml.contains("A"))
        assertTrue(xml.contains("android:strokeColor=\"#00FF00\""))
    }

    @Test
    fun textIsSkippedAndCounted() {
        val text = NoteItem(
            noteId = "test", zIndex = 0, kind = TextItemCodec.KIND, tool = null,
            colorArgb = 0xFF000000.toInt(), baseWidthPx = 0f,
            payload = TextItemCodec.encode(TextItemCodec.newAt(0f, 0f, body = "hi")),
        )
        val rendered = NoteVectorDrawableExporter.render(listOf(stroke(), text), sizeDp = 24)
        assertEquals(1, rendered.skippedCount)
        // The stroke still renders; only the text is dropped.
        assertTrue(rendered.xml.contains("<path"))
        assertFalse(rendered.xml.contains("hi"))
    }
}
