package com.aichat.sandbox.data.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I6** — the bounding-box / spatial prefilter (pure JVM, no ink).
 *
 * Locks in the three properties the mesh layer relies on: overlap queries are
 * correct (no false negatives), insert replaces cleanly so a transformed
 * stroke's stale cell membership doesn't linger, and results come back in stable
 * registration order (the deterministic id mapping the N2 work needs).
 */
class SpatialIndexTest {

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y1)

    @Test
    fun queryReturnsOnlyOverlappingIds() {
        val idx = SpatialIndex(cellSize = 100f)
        idx.insert("a", box(0f, 0f, 50f, 50f))
        idx.insert("b", box(300f, 300f, 360f, 360f))
        idx.insert("c", box(40f, 40f, 120f, 120f))

        val hits = idx.query(10f, 10f, 60f, 60f)
        assertTrue("a overlaps", "a" in hits)
        assertTrue("c overlaps", "c" in hits)
        assertFalse("b is far away", "b" in hits)
    }

    @Test
    fun queryAcrossManyCellsFindsLargeBox() {
        val idx = SpatialIndex(cellSize = 32f)
        // A long thin stroke spanning many cells.
        idx.insert("bar", box(0f, 100f, 1000f, 104f))
        assertTrue(idx.query(512f, 99f, 520f, 105f).contains("bar"))
        assertFalse(idx.query(512f, 200f, 520f, 210f).contains("bar"))
    }

    @Test
    fun reinsertMovesTheBoxWithNoStaleHits() {
        val idx = SpatialIndex(cellSize = 100f)
        idx.insert("a", box(0f, 0f, 10f, 10f))
        assertTrue(idx.query(0f, 0f, 5f, 5f).contains("a"))
        // Transform: the same id moves far away.
        idx.insert("a", box(500f, 500f, 510f, 510f))
        assertFalse("old cell must not still hold a", idx.query(0f, 0f, 5f, 5f).contains("a"))
        assertTrue(idx.query(500f, 500f, 505f, 505f).contains("a"))
        assertEquals(1, idx.size)
    }

    @Test
    fun resultsAreInDeterministicInsertionOrder() {
        val idx = SpatialIndex(cellSize = 1000f)
        idx.insert("z", box(0f, 0f, 10f, 10f))
        idx.insert("m", box(1f, 1f, 11f, 11f))
        idx.insert("a", box(2f, 2f, 12f, 12f))
        val first = idx.query(0f, 0f, 20f, 20f)
        val second = idx.query(0f, 0f, 20f, 20f)
        assertEquals(listOf("z", "m", "a"), first)
        assertEquals(first, second)
    }

    @Test
    fun retainAndRemoveDropIds() {
        val idx = SpatialIndex()
        idx.insert("a", box(0f, 0f, 1f, 1f))
        idx.insert("b", box(0f, 0f, 1f, 1f))
        idx.insert("c", box(0f, 0f, 1f, 1f))
        idx.retain(setOf("a", "c"))
        assertEquals(2, idx.size)
        assertFalse(idx.query(0f, 0f, 1f, 1f).contains("b"))
        idx.remove("a")
        assertEquals(1, idx.size)
        assertEquals(listOf("c"), idx.query(0f, 0f, 1f, 1f))
    }

    @Test
    fun queryPointUsesRadius() {
        val idx = SpatialIndex(cellSize = 50f)
        idx.insert("a", box(100f, 100f, 110f, 110f))
        assertTrue(idx.queryPoint(112f, 105f, radius = 5f).contains("a"))
        assertFalse(idx.queryPoint(130f, 105f, radius = 5f).contains("a"))
    }
}
