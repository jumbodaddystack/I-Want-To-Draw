package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Phase 2 — Ramer–Douglas–Peucker simplification and geometry helpers. */
class VectorPathSimplifierTest {

    private fun pt(x: Float, y: Float) = VectorPoint(x, y)

    @Test
    fun rdpPreservesEndpoints() {
        val points = listOf(pt(0f, 0f), pt(2f, 5f), pt(4f, -3f), pt(6f, 2f), pt(10f, 0f))
        val simplified = VectorPathSimplifier.simplify(points, tolerance = 0.25f)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun rdpRemovesCollinearInteriorPoints() {
        val points = listOf(
            pt(0f, 0f), pt(1f, 1f), pt(2f, 2f), pt(3f, 3f), pt(10f, 10f),
        )
        val simplified = VectorPathSimplifier.simplify(points, tolerance = 0.25f)
        assertEquals(listOf(pt(0f, 0f), pt(10f, 10f)), simplified)
    }

    @Test
    fun rdpKeepsImportantCorner() {
        // An L-shape: the corner must survive simplification.
        val points = listOf(
            pt(0f, 0f), pt(0f, 1f), pt(0f, 2f), pt(0f, 10f),
            pt(5f, 10f), pt(10f, 10f),
        )
        val simplified = VectorPathSimplifier.simplify(points, tolerance = 0.25f)
        assertEquals(listOf(pt(0f, 0f), pt(0f, 10f), pt(10f, 10f)), simplified)
    }

    @Test
    fun rdpReturnsInputForTooFewPoints() {
        val points = listOf(pt(0f, 0f), pt(10f, 10f))
        assertEquals(points, VectorPathSimplifier.simplify(points, tolerance = 0.25f))
    }

    @Test
    fun rdpReturnsInputForNonPositiveTolerance() {
        val points = listOf(pt(0f, 0f), pt(1f, 1f), pt(2f, 2f), pt(10f, 10f))
        assertEquals(points, VectorPathSimplifier.simplify(points, tolerance = 0f))
        assertEquals(points, VectorPathSimplifier.simplify(points, tolerance = -1f))
    }

    @Test
    fun removeConsecutiveDuplicatesDropsNearIdenticalPoints() {
        val points = listOf(
            pt(0f, 0f), pt(0f, 0f), pt(0.00001f, 0f),
            pt(5f, 5f), pt(5f, 5f), pt(10f, 10f),
        )
        val deduped = VectorPathSimplifier.removeConsecutiveDuplicates(points)
        assertEquals(listOf(pt(0f, 0f), pt(5f, 5f), pt(10f, 10f)), deduped)
    }

    @Test
    fun pathLengthHandlesOpenAndClosedPaths() {
        val square = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f))
        val open = VectorPathSimplifier.pathLength(square, closed = false)
        val closed = VectorPathSimplifier.pathLength(square, closed = true)
        assertTrue(abs(open - 30f) < 1e-3f)
        assertTrue(abs(closed - 40f) < 1e-3f)
    }

    @Test
    fun pathLengthIsZeroForSinglePoint() {
        assertEquals(0f, VectorPathSimplifier.pathLength(listOf(pt(3f, 3f))), 0f)
        assertEquals(0f, VectorPathSimplifier.pathLength(emptyList()), 0f)
    }

    @Test
    fun simplifiedPathBuilderHonorsClosedAndDecimals() {
        val points = listOf(pt(0f, 0f), pt(10.126f, 0f), pt(10f, 10f))
        val open = SimplifiedPathBuilder.buildPolylinePath(points, closed = false, decimalPlaces = 2)
        val closed = SimplifiedPathBuilder.buildPolylinePath(points, closed = true, decimalPlaces = 2)
        assertEquals("M0,0L10.13,0L10,10", open)
        assertEquals("M0,0L10.13,0L10,10Z", closed)
    }

    @Test
    fun simplifiedPathBuilderTrimsTrailingZerosAndAvoidsExponent() {
        val tiny = SimplifiedPathBuilder.formatFloat(0.10f, 4)
        assertEquals("0.1", tiny)
        val whole = SimplifiedPathBuilder.formatFloat(10f, 3)
        assertEquals("10", whole)
        // Very small value rounds to zero without scientific notation.
        val rounded = SimplifiedPathBuilder.formatFloat(0.0001f, 2)
        assertEquals("0", rounded)
        assertTrue(SimplifiedPathBuilder.formatFloat(123456f, 2).none { it == 'E' || it == 'e' })
    }
}
