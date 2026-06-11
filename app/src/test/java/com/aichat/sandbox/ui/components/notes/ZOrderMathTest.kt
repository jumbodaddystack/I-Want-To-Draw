package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.ui.components.notes.ZOrderMath.Entry
import com.aichat.sandbox.ui.components.notes.ZOrderMath.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZOrderMathTest {

    private val ink = ZOrderMath.BAND_INK
    private val hl = ZOrderMath.BAND_HIGHLIGHTER

    private val inkEntries = listOf(
        Entry("a", 0, ink),
        Entry("b", 1, ink),
        Entry("c", 2, ink),
        Entry("d", 3, ink),
    )

    @Test
    fun bringToFrontReassignsTopSlots() {
        val out = ZOrderMath.reorder(inkEntries, setOf("a", "b"), Op.BRING_TO_FRONT)
        // New order bottom→top: c, d, a, b — slots 0,1,2,3.
        assertEquals(mapOf("c" to 0, "d" to 1, "a" to 2, "b" to 3), out)
    }

    @Test
    fun sendToBackReassignsBottomSlots() {
        val out = ZOrderMath.reorder(inkEntries, setOf("d"), Op.SEND_TO_BACK)
        assertEquals(mapOf("d" to 0, "a" to 1, "b" to 2, "c" to 3), out)
    }

    @Test
    fun bringForwardSwapsWithNeighbourAbove() {
        val out = ZOrderMath.reorder(inkEntries, setOf("b"), Op.BRING_FORWARD)
        assertEquals(mapOf("c" to 1, "b" to 2), out)
    }

    @Test
    fun sendBackwardSwapsWithNeighbourBelow() {
        val out = ZOrderMath.reorder(inkEntries, setOf("c"), Op.SEND_BACKWARD)
        assertEquals(mapOf("c" to 1, "b" to 2), out)
    }

    @Test
    fun forwardAtTopIsNoOp() {
        val out = ZOrderMath.reorder(inkEntries, setOf("d"), Op.BRING_FORWARD)
        assertTrue(out.isEmpty())
    }

    @Test
    fun highlighterStaysInItsBand() {
        val entries = listOf(
            Entry("hl1", -1_000_000, hl),
            Entry("hl2", -999_999, hl),
            Entry("ink1", 0, ink),
        )
        val out = ZOrderMath.reorder(entries, setOf("hl1"), Op.BRING_TO_FRONT)
        // hl1 tops its own band; the ink band is untouched, so the
        // highlighter never crosses above ink.
        assertEquals(mapOf("hl2" to -1_000_000, "hl1" to -999_999), out)
    }

    @Test
    fun mixedSelectionReordersEachBandIndependently() {
        val entries = listOf(
            Entry("hl1", -1_000_000, hl),
            Entry("hl2", -999_999, hl),
            Entry("ink1", 0, ink),
            Entry("ink2", 1, ink),
        )
        val out = ZOrderMath.reorder(entries, setOf("hl1", "ink1"), Op.BRING_TO_FRONT)
        assertEquals(
            mapOf("hl2" to -1_000_000, "hl1" to -999_999, "ink2" to 0, "ink1" to 1),
            out,
        )
    }

    @Test
    fun existingSlotsAreReusedNotInvented() {
        // Sparse zIndex values survive as the slot set.
        val entries = listOf(
            Entry("a", 10, ink),
            Entry("b", 20, ink),
            Entry("c", 70, ink),
        )
        val out = ZOrderMath.reorder(entries, setOf("a"), Op.BRING_TO_FRONT)
        assertEquals(mapOf("b" to 10, "c" to 20, "a" to 70), out)
    }
}
