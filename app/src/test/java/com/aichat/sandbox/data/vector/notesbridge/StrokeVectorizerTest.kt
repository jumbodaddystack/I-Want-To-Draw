package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorPathSampler
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Test

class StrokeVectorizerTest {

    private fun strokeItem(
        points: List<Pair<Float, Float>>,
        pressures: List<Float> = List(points.size) { 1f },
        colorArgb: Int = 0xFF112233.toInt(),
        baseWidth: Float = 4f,
        id: String = "s1",
    ): NoteItem {
        val arr = FloatArray(points.size * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in points.indices) {
            val o = i * StrokeCodec.FLOATS_PER_SAMPLE
            arr[o] = points[i].first
            arr[o + 1] = points[i].second
            arr[o + 2] = pressures[i]
            arr[o + 3] = 0f
        }
        return NoteItem(
            id = id, noteId = "n", zIndex = 0, kind = NoteItem.KIND_STROKE,
            tool = "pen", colorArgb = colorArgb, baseWidthPx = baseWidth,
            payload = StrokeCodec.encode(arr),
        )
    }

    private fun distToPolyline(p: VectorPoint, poly: List<VectorPoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            // Point-to-segment distance (clamped to the segment).
            val a = poly[i]; val b = poly[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y
            val lenSq = dx * dx + dy * dy
            val d = if (lenSq < 1e-6f) {
                hypot(p.x - a.x, p.y - a.y)
            } else {
                val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
                hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
            }
            best = minOf(best, d)
        }
        return best
    }

    @Test
    fun strokeSamplesRoundTripToCubicWithinTolerance() {
        // A gentle sine arc, densely sampled.
        val src = (0..40).map { i ->
            val x = i.toFloat()
            x to (10f * sin(x / 40f * (Math.PI.toFloat() / 2f)))
        }
        val item = strokeItem(src)
        val editable = StrokeVectorizer.toEditablePath(item, autoShape = false)!!
        // Resample the fitted curve and confirm it hugs the original polyline.
        val cmds = EditablePathSerializer.toCommands(editable)
        val resampled = VectorPathSampler.sample(cmds, curveSteps = 16).points
        val srcPoly = src.map { VectorPoint(it.first, it.second) }
        val maxDev = resampled.maxOf { distToPolyline(it, srcPoly) }
        assertTrue("max deviation $maxDev should be small", maxDev < 1.5f)
    }

    @Test
    fun straightStrokeStaysStraight() {
        // Collinear samples → auto-shape line → single straight LineTo, no curves.
        val src = (0..10).map { i -> (i * 3f) to (i * 1.5f) }
        val item = strokeItem(src)
        val editable = StrokeVectorizer.toEditablePath(item, autoShape = true)!!
        assertEquals(1, editable.subpaths.size)
        assertEquals(2, editable.subpaths[0].anchors.size)
        editable.subpaths[0].anchors.forEach { assertEquals(AnchorType.CORNER, it.type) }
        val cmds = EditablePathSerializer.toCommands(editable)
        assertEquals(2, cmds.size)
        assertTrue(cmds[0] is PathCommand.MoveTo)
        assertTrue(cmds[1] is PathCommand.LineTo)
    }

    @Test
    fun widthMapsToUniformStroke() {
        val pressures = listOf(0.4f, 0.6f, 0.8f, 0.6f) // mean 0.6
        val src = listOf(0f to 0f, 4f to 1f, 9f to 0f, 13f to 2f)
        val item = strokeItem(src, pressures, colorArgb = 0xFF3366CC.toInt(), baseWidth = 5f)
        val editable = StrokeVectorizer.toEditablePath(item, autoShape = false)!!
        val style = editable.style
        assertEquals(5f * 0.6f, style.strokeWidth!!, 1e-3f)
        assertEquals("round", style.strokeLineCap)
        assertEquals("round", style.strokeLineJoin)
        assertEquals("#FF3366CC", style.strokeColor)
        assertEquals(null, style.fillColor)
    }

    @Test
    fun outlineFillModeProducesClosedFilledPathForVariableWidth() {
        // Strong pressure variation along a curve → a pen stroke whose width
        // genuinely varies, so OUTLINE_FILL traces a filled boundary.
        val src = (0..20).map { i -> i.toFloat() to (4f * sin(i / 4f)) }
        val pressures = (0..20).map { i -> 0.2f + 0.7f * (sin(i / 3f) * 0.5f + 0.5f) }
        val item = strokeItem(src, pressures, colorArgb = 0xFF204060.toInt(), baseWidth = 10f)
        val editable = StrokeVectorizer.toEditablePath(
            item, widthMode = WidthMode.OUTLINE_FILL, autoShape = false,
        )!!
        assertEquals(1, editable.subpaths.size)
        assertTrue("outline should close", editable.subpaths[0].closed)
        assertTrue("outline needs ≥3 boundary anchors", editable.subpaths[0].anchors.size >= 3)
        // Filled, not stroked.
        assertEquals("#FF204060", editable.style.fillColor)
        assertEquals(null, editable.style.strokeColor)
        // Boundary anchors are plain corners (straight segments between samples).
        editable.subpaths[0].anchors.forEach { assertEquals(AnchorType.CORNER, it.type) }
    }

    @Test
    fun outlineFillModeFallsBackToCenterlineForUniformWidth() {
        // Constant pressure → uniform width → outlining buys nothing, so the
        // bridge falls back to the stroked centerline path.
        val src = (0..10).map { i -> i.toFloat() to (3f * sin(i / 3f)) }
        val item = strokeItem(src, List(src.size) { 1f }, baseWidth = 4f)
        val editable = StrokeVectorizer.toEditablePath(
            item, widthMode = WidthMode.OUTLINE_FILL, autoShape = false,
        )!!
        assertTrue("centerline stays open", !editable.subpaths[0].closed)
        assertEquals(null, editable.style.fillColor)
        assertTrue(editable.style.strokeColor != null)
    }

    @Test
    fun interiorAnchorsAreSmoothEndpointsCorner() {
        val src = (0..20).map { i -> i.toFloat() to (5f * sin(i / 3f)) }
        val editable = StrokeVectorizer.toEditablePath(strokeItem(src), autoShape = false)!!
        val anchors = editable.subpaths[0].anchors
        assertTrue(anchors.size >= 3)
        assertEquals(AnchorType.CORNER, anchors.first().type)
        assertEquals(AnchorType.CORNER, anchors.last().type)
        assertEquals(AnchorType.SMOOTH, anchors[1].type)
    }
}
