package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.ui.components.notes.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class AutoShapeFitterTest {

    @Test
    fun wobblyCircleDetectedAsCircle() {
        val cx = 50f; val cy = 50f; val r = 20f
        val pts = (0..24).map { i ->
            val a = i / 24f * 2f * Math.PI.toFloat()
            val noise = if (i % 2 == 0) 0.5f else -0.5f
            VectorPoint(cx + (r + noise) * cos(a), cy + (r + noise) * sin(a))
        }
        val shape = AutoShapeFitter.fit(pts)
        assertTrue("expected ellipse, got $shape", shape is Shape.Ellipse)
        shape as Shape.Ellipse
        assertEquals(cx, shape.cx, 1.5f)
        assertEquals(cy, shape.cy, 1.5f)
        assertEquals(r, shape.rx, 1.5f)
    }

    @Test
    fun nearAxisRectDetectedAsRect() {
        val w = 40f; val h = 30f
        val pts = ArrayList<VectorPoint>()
        // walk the perimeter with small noise
        for (i in 0..8) pts += VectorPoint(i / 8f * w, jitter(i))            // top
        for (i in 1..6) pts += VectorPoint(w, i / 6f * h)                    // right
        for (i in 1..8) pts += VectorPoint(w - i / 8f * w, h + jitter(i))    // bottom
        for (i in 1..6) pts += VectorPoint(0f, h - i / 6f * h)               // left (back to start)
        val shape = AutoShapeFitter.fit(pts)
        assertTrue("expected rect, got $shape", shape is Shape.Rect)
        shape as Shape.Rect
        assertEquals(0f, shape.minX, 1.2f)
        assertEquals(0f, shape.minY, 1.2f)
        assertEquals(w, shape.maxX, 1.2f)
        assertEquals(h, shape.maxY, 1.2f)
    }

    @Test
    fun nearStraightDetectedAsLine() {
        val pts = (0..12).map { i ->
            val x = i * 4f
            VectorPoint(x, x * 0.5f + (if (i % 2 == 0) 0.2f else -0.2f))
        }
        val shape = AutoShapeFitter.fit(pts)
        assertTrue("expected line, got $shape", shape is Shape.Line)
    }

    @Test
    fun genericSquiggleNotFitted() {
        val pts = (0..60).map { i ->
            val x = i.toFloat()
            VectorPoint(x, 8f * sin(x * 0.5f))
        }
        assertNull(AutoShapeFitter.fit(pts))
    }

    private fun jitter(i: Int): Float = if (i % 2 == 0) 0.6f else -0.6f
}
