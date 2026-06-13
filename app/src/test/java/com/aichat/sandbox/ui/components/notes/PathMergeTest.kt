package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17.5 — pure path-merge rules: compatible payloads fold into one
 * multi-subpath payload (holes kept), incompatible ones refuse.
 */
class PathMergeTest {

    private fun square(x: Float, fill: Int = 0, rule: Byte = PathCodec.FILL_RULE_NON_ZERO) =
        PathCodec.PathPayload(
            subpaths = listOf(
                PathCodec.Subpath(
                    anchors = listOf(
                        PathCodec.Anchor(x, 0f),
                        PathCodec.Anchor(x + 10f, 0f),
                        PathCodec.Anchor(x + 10f, 10f),
                        PathCodec.Anchor(x, 10f),
                    ),
                    closed = true,
                ),
            ),
            fillRule = rule,
            fillArgb = fill,
        )

    @Test
    fun mergeConcatenatesSubpathsKeepingFirstStyle() {
        val merged = PathMerge.merge(listOf(square(0f, fill = 0xFF112233.toInt()), square(20f, fill = 0xFF112233.toInt())))!!
        assertEquals(2, merged.subpaths.size)
        assertEquals(0xFF112233.toInt(), merged.fillArgb)
        // Re-encode is a v2 multi-subpath payload that decodes back identically.
        val round = PathCodec.decode(PathCodec.encode(merged))
        assertEquals(2, round.subpaths.size)
    }

    @Test
    fun mergePreservesHolesViaEvenOddSubpaths() {
        // Outer + inner contour both even-odd → donut survives as 2 subpaths.
        val a = square(0f, rule = PathCodec.FILL_RULE_EVEN_ODD)
        val b = square(2f, rule = PathCodec.FILL_RULE_EVEN_ODD)
        val merged = PathMerge.merge(listOf(a, b))!!
        assertEquals(PathCodec.FILL_RULE_EVEN_ODD, merged.fillRule)
        assertEquals(2, merged.subpaths.size)
    }

    @Test
    fun mergeRefusesDifferentFill() {
        assertNull(PathMerge.merge(listOf(square(0f, fill = 0xFF000000.toInt()), square(20f, fill = 0xFFFFFFFF.toInt()))))
    }

    @Test
    fun mergeRefusesDifferentFillRule() {
        assertNull(
            PathMerge.merge(
                listOf(
                    square(0f, rule = PathCodec.FILL_RULE_NON_ZERO),
                    square(20f, rule = PathCodec.FILL_RULE_EVEN_ODD),
                ),
            ),
        )
    }

    @Test
    fun mergeNeedsAtLeastTwo() {
        assertNull(PathMerge.merge(emptyList()))
        assertNull(PathMerge.merge(listOf(square(0f))))
    }

    @Test
    fun groupByStylePartitionsMixedSelectionPreservingOrder() {
        val payloads = listOf(
            square(0f, fill = 0xFF000000.toInt()),   // group A
            square(20f, fill = 0xFFFF0000.toInt()),  // group B
            square(40f, fill = 0xFF000000.toInt()),  // group A
        )
        val groups = PathMerge.groupByStyle(payloads)
        assertEquals(2, groups.size)
        assertEquals(listOf(0, 2), groups[0])
        assertEquals(listOf(1), groups[1])
    }

    @Test
    fun compatibleIsSymmetric() {
        val a = square(0f, fill = 0xFF112233.toInt())
        val b = square(9f, fill = 0xFF112233.toInt())
        assertTrue(PathMerge.compatible(a, b))
        assertTrue(PathMerge.compatible(b, a))
    }
}
