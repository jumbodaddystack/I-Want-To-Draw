package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentMathTest {

    // Three boxes: a 10-wide at x=0, a 20-wide at x=50, a 5-wide at x=100.
    private val entries = listOf(
        "a" to floatArrayOf(0f, 0f, 10f, 10f),
        "b" to floatArrayOf(50f, 20f, 70f, 40f),
        "c" to floatArrayOf(100f, 5f, 105f, 30f),
    )

    private fun dx(m: FloatArray) = m[2]
    private fun dy(m: FloatArray) = m[5]

    @Test
    fun alignLeftMovesEverythingToMinX() {
        val out = AlignmentMath.align(entries, AlignmentMath.AlignEdge.LEFT)
        // "a" already sits at minX → omitted.
        assertEquals(setOf("b", "c"), out.keys)
        assertEquals(-50f, dx(out.getValue("b")), 0f)
        assertEquals(-100f, dx(out.getValue("c")), 0f)
        assertEquals(0f, dy(out.getValue("b")), 0f)
    }

    @Test
    fun alignRightMovesEverythingToMaxX() {
        val out = AlignmentMath.align(entries, AlignmentMath.AlignEdge.RIGHT)
        assertEquals(95f, dx(out.getValue("a")), 0f)
        assertEquals(35f, dx(out.getValue("b")), 0f)
        assertTrue("c" !in out)
    }

    @Test
    fun alignCenterHorizontal() {
        val out = AlignmentMath.align(entries, AlignmentMath.AlignEdge.CENTER_H)
        // Union spans 0..105, centre 52.5.
        assertEquals(47.5f, dx(out.getValue("a")), 1e-4f)
        assertEquals(-7.5f, dx(out.getValue("b")), 1e-4f)
        assertEquals(-50f, dx(out.getValue("c")), 1e-4f)
    }

    @Test
    fun alignTopBottomMiddleAreVerticalOnly() {
        val top = AlignmentMath.align(entries, AlignmentMath.AlignEdge.TOP)
        assertEquals(-20f, dy(top.getValue("b")), 0f)
        assertEquals(-5f, dy(top.getValue("c")), 0f)
        assertEquals(0f, dx(top.getValue("b")), 0f)

        val bottom = AlignmentMath.align(entries, AlignmentMath.AlignEdge.BOTTOM)
        // Union spans y 0..40.
        assertEquals(30f, dy(bottom.getValue("a")), 0f)
        assertEquals(10f, dy(bottom.getValue("c")), 0f)

        val middle = AlignmentMath.align(entries, AlignmentMath.AlignEdge.CENTER_V)
        // Union centre y = 20; "a" centre 5 → +15.
        assertEquals(15f, dy(middle.getValue("a")), 1e-4f)
    }

    @Test
    fun singleItemAlignIsNoOp() {
        val out = AlignmentMath.align(entries.take(1), AlignmentMath.AlignEdge.LEFT)
        assertTrue(out.isEmpty())
    }

    @Test
    fun distributeHorizontalEqualizesGapsWithUnevenSizes() {
        val out = AlignmentMath.distribute(entries, AlignmentMath.Axis.HORIZONTAL)
        // Span 0..105, total widths 35 → 2 gaps of 35 each.
        // "a" fixed; "b" should start at 10+35=45 (currently 50 → -5);
        // "c" should start at 45+20+35=100 (already there → omitted).
        assertEquals(setOf("b"), out.keys)
        assertEquals(-5f, dx(out.getValue("b")), 1e-4f)
        assertEquals(0f, dy(out.getValue("b")), 0f)
    }

    @Test
    fun distributeVerticalEqualizesGaps() {
        // Heights: a=10 (y 0..10), b=20 (y 20..40), c=25 (y 5..30).
        val out = AlignmentMath.distribute(entries, AlignmentMath.Axis.VERTICAL)
        // Sorted by top edge: a(0), c(5), b(20). Span 0..40, sizes 55 → the
        // overlap forces a negative gap of (40 - 55) / 2 = -7.5.
        // c should start at 10 + (-7.5) = 2.5 → delta -2.5.
        // b should start at 2.5 + 25 - 7.5 = 20 → already there.
        assertEquals(setOf("c"), out.keys)
        assertEquals(-2.5f, dy(out.getValue("c")), 1e-4f)
    }

    @Test
    fun distributeBelowMinimumCountIsNoOp() {
        val out = AlignmentMath.distribute(entries.take(2), AlignmentMath.Axis.HORIZONTAL)
        assertTrue(out.isEmpty())
    }
}
