package com.aichat.sandbox.data.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I7** — the constraint / snap engine (pure JVM float geometry, no ink).
 *
 * Pins each detection in isolation (alignment, even spacing, symmetry), the
 * conservatism (already-regular and deliberately-irregular layouts produce no
 * proposal), and that [ConstraintSnap.resolve] merges conflicting constraints
 * into a single per-axis nudge per item, deterministically.
 */
class ConstraintSnapTest {

    private fun item(id: String, minX: Float, minY: Float, maxX: Float, maxY: Float) =
        ConstraintSnap.Item(id, floatArrayOf(minX, minY, maxX, maxY))

    private fun List<ConstraintSnap.Adjustment>.dxOf(id: String) = first { it.id == id }.dx
    private fun List<ConstraintSnap.Adjustment>.dyOf(id: String) = first { it.id == id }.dy

    @Test
    fun alignsNearEqualLeftEdges() {
        // A,B share a near-left edge (10,12); C is far. Widths differ so right/
        // center edges never cluster — only the left-edge alignment should fire.
        val items = listOf(
            item("A", 10f, 0f, 30f, 20f),
            item("B", 12f, 0f, 60f, 20f),
            item("C", 100f, 0f, 120f, 20f),
        )
        val constraints = ConstraintSnap.detect(items)
        val left = constraints.firstOrNull { it.kind == ConstraintSnap.Kind.ALIGN_LEFT }
        assertTrue("left-edge alignment detected", left != null)
        val adj = ConstraintSnap.resolve(constraints, items.map { it.id })
        // mean left of {10,12} = 11 → A:+1, B:-1, C untouched.
        assertEquals(1f, adj.dxOf("A"), 1e-3f)
        assertEquals(-1f, adj.dxOf("B"), 1e-3f)
        assertTrue("C is not moved", adj.none { it.id == "C" })
    }

    @Test
    fun alreadyAlignedProducesNothing() {
        val items = listOf(
            item("A", 10f, 0f, 30f, 20f),
            item("B", 10f, 40f, 30f, 60f),
        )
        // Lefts identical, widths identical → every edge is already aligned.
        assertTrue(ConstraintSnap.detect(items).isEmpty())
    }

    @Test
    fun belowMinMoveIsIgnored() {
        val items = listOf(
            item("A", 10.0f, 0f, 30f, 20f),
            item("B", 10.2f, 40f, 30.2f, 60f),
        )
        // 0.1 px off the mean each — under minMove (0.5), so no proposal.
        assertTrue(ConstraintSnap.detect(items).none { it.kind == ConstraintSnap.Kind.ALIGN_LEFT })
    }

    @Test
    fun distributesNearEvenRow() {
        val items = listOf(
            item("A", -10f, 0f, 10f, 20f),  // center x 0
            item("B", 38f, 0f, 58f, 20f),   // center x 48 (mean gap 50 → +2)
            item("C", 90f, 0f, 110f, 20f),  // center x 100
        )
        val constraints = ConstraintSnap.detect(items)
        assertTrue("even spacing detected",
            constraints.any { it.kind == ConstraintSnap.Kind.DISTRIBUTE_X })
        val adj = ConstraintSnap.resolve(constraints, items.map { it.id })
        assertEquals("middle nudged to even spacing", 2f, adj.dxOf("B"), 1e-3f)
        assertTrue("ends stay fixed", adj.none { it.id == "A" || it.id == "C" })
    }

    @Test
    fun irregularRowIsNotReflowed() {
        val items = listOf(
            item("A", -10f, 0f, 10f, 20f),  // center 0
            item("B", 10f, 0f, 30f, 20f),   // center 20 (gap 20)
            item("C", 90f, 0f, 110f, 20f),  // center 100 (gap 80) — deliberately uneven
        )
        assertTrue("a clearly uneven row is left alone",
            ConstraintSnap.detect(items).none { it.kind == ConstraintSnap.Kind.DISTRIBUTE_X })
    }

    @Test
    fun symmetrizesNearMirrorPair() {
        // Slightly different widths so the union mid (edge-based) differs from the
        // pair midpoint (center-based), giving the engine something to fix.
        val items = listOf(
            item("A", 10f, 0f, 30f, 20f),  // center 20
            item("B", 70f, 0f, 94f, 20f),  // center 82
        )
        val constraints = ConstraintSnap.detect(items)
        assertTrue("symmetry detected",
            constraints.any { it.kind == ConstraintSnap.Kind.SYMMETRY_X })
        val adj = ConstraintSnap.resolve(constraints, items.map { it.id })
        // e = (20-52)+(82-52) = -2 → both nudged by +1.
        assertEquals(1f, adj.dxOf("A"), 1e-3f)
        assertEquals(1f, adj.dxOf("B"), 1e-3f)
    }

    @Test
    fun resolveKeepsStrongestPerAxisAndMergesAxes() {
        val strongX = ConstraintSnap.Constraint(
            ConstraintSnap.Kind.ALIGN_LEFT, "left",
            listOf(
                ConstraintSnap.Adjustment("A", 5f, 0f),
                ConstraintSnap.Adjustment("B", 5f, 0f),
                ConstraintSnap.Adjustment("C", 5f, 0f),
            ),
        )
        val weakX = ConstraintSnap.Constraint(
            ConstraintSnap.Kind.ALIGN_CENTER_X, "centerX",
            listOf(ConstraintSnap.Adjustment("A", -3f, 0f)),
        )
        val anyY = ConstraintSnap.Constraint(
            ConstraintSnap.Kind.ALIGN_TOP, "top",
            listOf(ConstraintSnap.Adjustment("A", 0f, 2f)),
        )
        val adj = ConstraintSnap.resolve(listOf(weakX, anyY, strongX), listOf("A", "B", "C"))
        // A's X comes from the stronger (3-item) left constraint, not the weak one;
        // A's Y comes from the top constraint. Both axes merged onto one item.
        assertEquals(5f, adj.dxOf("A"), 1e-3f)
        assertEquals(2f, adj.dyOf("A"), 1e-3f)
        // Deterministic order follows the caller's id order.
        assertEquals(listOf("A", "B", "C"), adj.map { it.id })
    }

    @Test
    fun emptyForTooFewItems() {
        assertTrue(ConstraintSnap.detect(listOf(item("A", 0f, 0f, 10f, 10f))).isEmpty())
        assertTrue(ConstraintSnap.resolve(emptyList(), listOf("A")).isEmpty())
    }
}
