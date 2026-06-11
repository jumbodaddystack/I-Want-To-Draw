package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {

    private val s = StrokeCodec.FLOATS_PER_SAMPLE

    private fun pack(points: List<Pair<Float, Float>>): FloatArray {
        val out = FloatArray(points.size * s)
        for ((i, p) in points.withIndex()) {
            out[i * s] = p.first
            out[i * s + 1] = p.second
            out[i * s + 2] = 0.5f
            out[i * s + 3] = 0f
        }
        return out
    }

    private fun recognize(points: List<Pair<Float, Float>>) =
        ShapeRecognizer.recognize(pack(points), points.size)

    @Test
    fun straightStrokeBecomesLine() {
        // Slightly noisy diagonal.
        val points = (0..30).map { i ->
            val t = i / 30f
            (t * 300f + (i % 3) * 0.8f) to (t * 120f - (i % 2) * 0.8f)
        }
        val result = recognize(points)
        assertNotNull(result)
        val line = result!!.shape as Shape.Line
        assertEquals("line", result.label)
        assertEquals(0f, line.x0, 3f)
        assertEquals(300f, line.x1, 3f)
    }

    @Test
    fun circleBecomesEllipse() {
        val points = (0..63).map { i ->
            val a = i / 63.0 * 2.0 * Math.PI
            (100f + 80f * cos(a).toFloat()) to (100f + 80f * sin(a).toFloat())
        }
        val result = recognize(points)
        assertNotNull(result)
        assertEquals("ellipse", result!!.label)
        val ellipse = result.shape as Shape.Ellipse
        assertEquals(100f, ellipse.cx, 4f)
        assertEquals(100f, ellipse.cy, 4f)
        assertEquals(80f, ellipse.rx, 6f)
        assertEquals(80f, ellipse.ry, 6f)
    }

    @Test
    fun rectangleLoopBecomesRect() {
        // Walk the perimeter of a 200×120 rect, closing back at the start.
        val points = buildList {
            for (i in 0..10) add((i * 20f) to 0f)
            for (i in 1..6) add(200f to (i * 20f))
            for (i in 1..10) add((200f - i * 20f) to 120f)
            for (i in 1..6) add(0f to (120f - i * 20f))
        }
        val result = recognize(points)
        assertNotNull(result)
        assertEquals("rectangle", result!!.label)
        val rect = result.shape as Shape.Rect
        assertEquals(0f, rect.minX, 1f)
        assertEquals(0f, rect.minY, 1f)
        assertEquals(200f, rect.maxX, 1f)
        assertEquals(120f, rect.maxY, 1f)
    }

    @Test
    fun triangleLoopBecomesClosedPolygon() {
        val a = 0f to 0f
        val b = 200f to 20f
        val c = 90f to 180f
        fun edge(p: Pair<Float, Float>, q: Pair<Float, Float>, steps: Int) =
            (0 until steps).map { i ->
                val t = i / steps.toFloat()
                (p.first + (q.first - p.first) * t) to (p.second + (q.second - p.second) * t)
            }
        val points = edge(a, b, 12) + edge(b, c, 12) + edge(c, a, 12) + listOf(a)
        val result = recognize(points)
        assertNotNull(result)
        assertEquals("polygon", result!!.label)
        val polygon = result.shape as Shape.Polygon
        assertTrue(polygon.closed)
        assertEquals(3, polygon.points.size / 2)
    }

    @Test
    fun openScribbleIsNotRecognized() {
        // A wavy open stroke — handwriting-ish; must stay ink.
        val points = (0..40).map { i ->
            (i * 8f) to (40f * sin(i / 3.0).toFloat())
        }
        assertNull(recognize(points))
    }

    @Test
    fun tinyStrokeIsNotRecognized() {
        val points = (0..10).map { i -> (i.toFloat()) to (i.toFloat()) }
        assertNull(recognize(points))
    }
}
