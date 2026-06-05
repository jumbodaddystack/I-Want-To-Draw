package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorPathSampler
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeVectorizerTest {

    private fun shapeItem(shape: Shape, fillArgb: Int = 0, color: Int = 0xFF000000.toInt()): NoteItem =
        NoteItem(
            id = "sh", noteId = "n", zIndex = 0, kind = NoteItem.KIND_SHAPE,
            tool = null, colorArgb = color, baseWidthPx = 2f,
            payload = ShapeCodec.encode(shape, fillArgb),
        )

    @Test
    fun rectEmitsExactPathAndReparses() {
        val cmds = ShapeVectorizer.toCommands(Shape.Rect(0f, 0f, 10f, 8f))
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.LineTo(10f, 0f),
                PathCommand.LineTo(10f, 8f),
                PathCommand.LineTo(0f, 8f),
                PathCommand.Close(),
            ),
            cmds,
        )
        // And the editable round-trips back to the same commands.
        val editable = ShapeVectorizer.toEditablePath(shapeItem(Shape.Rect(0f, 0f, 10f, 8f)))!!
        assertEquals(1, editable.subpaths.size)
        assertTrue(editable.subpaths[0].closed)
    }

    @Test
    fun ellipseEmitsFourCubicArcsReparses() {
        val cmds = ShapeVectorizer.toCommands(Shape.Ellipse(cx = 12f, cy = 12f, rx = 10f, ry = 6f))
        assertEquals(1, cmds.count { it is PathCommand.MoveTo })
        assertEquals(4, cmds.count { it is PathCommand.CubicTo })
        assertEquals(1, cmds.count { it is PathCommand.Close })
        // Sampled bounds should match (cx±rx, cy±ry) within tolerance.
        val pts = VectorPathSampler.sample(cmds, curveSteps = 24).points
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        assertEquals(2f, minX, 0.2f); assertEquals(22f, maxX, 0.2f)
        assertEquals(6f, minY, 0.2f); assertEquals(18f, maxY, 0.2f)
    }

    @Test
    fun polygonClosedFlagPreserved() {
        val pts = floatArrayOf(0f, 0f, 10f, 0f, 5f, 8f)
        val closed = ShapeVectorizer.toCommands(Shape.Polygon(pts, closed = true))
        assertTrue(closed.last() is PathCommand.Close)
        val open = ShapeVectorizer.toCommands(Shape.Polygon(pts, closed = false))
        assertTrue(open.none { it is PathCommand.Close })
    }

    @Test
    fun lineAndArrowExact() {
        assertEquals(
            listOf(PathCommand.MoveTo(1f, 2f), PathCommand.LineTo(9f, 6f)),
            ShapeVectorizer.toCommands(Shape.Line(1f, 2f, 9f, 6f)),
        )
        val arrow = ShapeVectorizer.toCommands(Shape.Arrow(0f, 0f, 10f, 0f, headSize = 3f))
        // shaft (M,L) + head subpath (M,L,L)
        assertEquals(2, arrow.count { it is PathCommand.MoveTo })
        assertTrue(arrow.first() is PathCommand.MoveTo)
    }

    @Test
    fun shapeStyleCarriesFillAndStroke() {
        val item = shapeItem(
            Shape.Rect(0f, 0f, 4f, 4f),
            fillArgb = 0xFFAABBCC.toInt(),
            color = 0xFF010203.toInt(),
        )
        val style = ShapeVectorizer.toEditablePath(item)!!.style
        assertEquals("#FF010203", style.strokeColor)
        assertEquals("#FFAABBCC", style.fillColor)
        assertEquals(2f, style.strokeWidth!!, 1e-3f)
    }
}
