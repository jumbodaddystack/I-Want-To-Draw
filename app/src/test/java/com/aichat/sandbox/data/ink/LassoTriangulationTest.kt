package com.aichat.sandbox.data.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase **I6** — ear-clipping the lasso loop into triangles (pure JVM, no ink).
 *
 * The lasso → mesh bridge is only sound if the triangle fan exactly tiles the
 * loop interior: the triangles' total area must equal the polygon's, and every
 * triangle must lie inside the loop (so "stroke mesh ∩ any triangle" really means
 * "stroke overlaps the loop"). Both convex and concave loops are checked, since a
 * freehand lasso is usually concave.
 */
class LassoTriangulationTest {

    private fun area(tris: FloatArray): Float {
        var sum = 0f
        var i = 0
        while (i < tris.size) {
            val ax = tris[i]; val ay = tris[i + 1]
            val bx = tris[i + 2]; val by = tris[i + 3]
            val cx = tris[i + 4]; val cy = tris[i + 5]
            sum += abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2f
            i += 6
        }
        return sum
    }

    private fun polygonArea(poly: FloatArray, n: Int): Float {
        var a = 0f
        var j = n - 1
        for (i in 0 until n) {
            a += (poly[j * 2] + poly[i * 2]) * (poly[j * 2 + 1] - poly[i * 2 + 1])
            j = i
        }
        return abs(a) / 2f
    }

    @Test
    fun convexSquareTilesExactly() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val tris = LassoTriangulation.triangulate(square, 4)
        assertEquals("a quad ear-clips into 2 triangles", 2, tris.size / 6)
        assertEquals(polygonArea(square, 4), area(tris), 1e-2f)
    }

    @Test
    fun concaveArrowTilesExactly() {
        // A concave "arrow"/chevron shape — fan triangulation would over-cover it.
        val poly = floatArrayOf(
            0f, 0f,
            100f, 50f,
            0f, 100f,
            40f, 50f, // the reflex notch
        )
        val tris = LassoTriangulation.triangulate(poly, 4)
        assertEquals(2, tris.size / 6)
        assertEquals(
            "concave loop area must match (no over-coverage)",
            polygonArea(poly, 4), area(tris), 1e-2f,
        )
    }

    @Test
    fun manyVertexLoopProducesNMinusTwoTriangles() {
        // A rough circle (CW order, to exercise the winding normalisation).
        val n = 24
        val poly = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = -2.0 * Math.PI * i / n // clockwise
            poly[i * 2] = (200 + 80 * Math.cos(a)).toFloat()
            poly[i * 2 + 1] = (200 + 80 * Math.sin(a)).toFloat()
        }
        val tris = LassoTriangulation.triangulate(poly, n)
        assertEquals(n - 2, tris.size / 6)
        assertEquals(polygonArea(poly, n), area(tris), 1f)
    }

    @Test
    fun degenerateLoopProducesNoTriangles() {
        assertTrue(LassoTriangulation.triangulate(floatArrayOf(0f, 0f, 1f, 1f), 2).isEmpty())
    }
}
