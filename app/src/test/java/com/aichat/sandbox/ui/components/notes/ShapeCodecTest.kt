package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCodecTest {

    @Test
    fun lineRoundTrips() {
        val original = Shape.Line(1f, 2f, 3f, 4f)
        val decoded = ShapeCodec.decode(ShapeCodec.encode(original, fillArgb = 0))
        assertEquals(original, decoded.shape)
        assertEquals(0, decoded.fillArgb)
    }

    @Test
    fun rectRoundTripsWithFill() {
        val original = Shape.Rect(0f, 0f, 100f, 50f, cornerRadius = 8f)
        val decoded = ShapeCodec.decode(ShapeCodec.encode(original, fillArgb = 0x80FF0000.toInt()))
        assertEquals(original, decoded.shape)
        assertEquals(0x80FF0000.toInt(), decoded.fillArgb)
    }

    @Test
    fun ellipseRoundTrips() {
        val original = Shape.Ellipse(10f, 20f, 30f, 40f, rotationRad = 0.5f)
        val decoded = ShapeCodec.decode(ShapeCodec.encode(original))
        assertEquals(original, decoded.shape)
    }

    @Test
    fun arrowRoundTrips() {
        val original = Shape.Arrow(0f, 0f, 100f, 0f, headSize = 12f)
        val decoded = ShapeCodec.decode(ShapeCodec.encode(original))
        assertEquals(original, decoded.shape)
    }

    @Test
    fun polygonRoundTripsClosed() {
        val pts = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val original = Shape.Polygon(pts, closed = true)
        val decoded = ShapeCodec.decode(ShapeCodec.encode(original))
        val polygon = decoded.shape as Shape.Polygon
        assertArrayEquals(pts, polygon.points, 0f)
        assertTrue(polygon.closed)
    }

    @Test
    fun polygonRoundTripsOpen() {
        val pts = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val original = Shape.Polygon(pts, closed = false)
        val decoded = ShapeCodec.decode(ShapeCodec.encode(original))
        val polygon = decoded.shape as Shape.Polygon
        assertArrayEquals(pts, polygon.points, 0f)
        assertEquals(false, polygon.closed)
    }

    @Test
    fun translateAppliesToAllVertices() {
        val m = StrokeTransform.translation(5f, -3f)
        val moved = ShapeCodec.transform(Shape.Line(0f, 0f, 10f, 10f), m) as Shape.Line
        assertEquals(5f, moved.x0, 0f)
        assertEquals(-3f, moved.y0, 0f)
        assertEquals(15f, moved.x1, 0f)
        assertEquals(7f, moved.y1, 0f)
    }

    @Test
    fun boundsOfRect() {
        val b = ShapeCodec.boundsOf(Shape.Rect(10f, 5f, 0f, 25f))
        assertNotNull(b)
        assertEquals(0f, b!![0], 0f)
        assertEquals(5f, b[1], 0f)
        assertEquals(10f, b[2], 0f)
        assertEquals(25f, b[3], 0f)
    }
}
