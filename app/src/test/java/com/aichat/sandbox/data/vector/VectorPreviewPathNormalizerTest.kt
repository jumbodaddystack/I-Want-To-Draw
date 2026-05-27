package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8 — pure command→absolute-geometry conversion for preview rendering. */
class VectorPreviewPathNormalizerTest {

    private fun parse(pathData: String): List<PathCommand> =
        PathDataParser.parse(pathData).commands

    @Test
    fun relativeCommandsResolveToAbsolutePoints() {
        // m10,10 l5,0 l0,5 -> absolute (10,10) line (15,10) line (15,15)
        val subpaths = VectorPreviewPathNormalizer.normalize(parse("m10,10 l5,0 l0,5"))
        val sp = subpaths.single()
        assertEquals(10f, sp.startX)
        assertEquals(10f, sp.startY)
        val pts = sp.segments.map { it.endX to it.endY }
        assertEquals(listOf(15f to 10f, 15f to 15f), pts)
    }

    @Test
    fun horizontalAndVerticalResolveAgainstCurrentPoint() {
        val sp = VectorPreviewPathNormalizer.normalize(parse("M2,3 H8 V9")).single()
        val segs = sp.segments
        assertEquals(8f to 3f, segs[0].endX to segs[0].endY)
        assertEquals(8f to 9f, segs[1].endX to segs[1].endY)
    }

    @Test
    fun smoothCurvesUseReflectedControls() {
        // C with control2 at (3,0) from current (0,0)..end (4,0); the following
        // S must reflect that control about the join point (4,0) -> (5,0).
        val sp = VectorPreviewPathNormalizer.normalize(
            parse("M0,0 C1,0 3,0 4,0 S7,0 8,0"),
        ).single()
        val cubic = sp.segments[0] as PreviewSegment.Cubic
        assertEquals(3f, cubic.c2x)
        val smooth = sp.segments[1] as PreviewSegment.Cubic
        // reflected first control point = 2*4 - 3 = 5
        assertEquals(5f, smooth.c1x)
        assertEquals(0f, smooth.c1y)
    }

    @Test
    fun smoothCurveWithoutPriorCurveUsesCurrentPoint() {
        // Leading S has no previous cubic, so the first control = current point.
        val sp = VectorPreviewPathNormalizer.normalize(parse("M2,2 S5,5 6,6")).single()
        val cubic = sp.segments.single() as PreviewSegment.Cubic
        assertEquals(2f, cubic.c1x)
        assertEquals(2f, cubic.c1y)
    }

    @Test
    fun arcCommandsDoNotThrow() {
        val datasets = listOf(
            "M5,5 a4,4 0 1,0 8,0 a4,4 0 1,0 -8,0 Z",
            "M0,0 A10,10 0 0,1 10,10",
            "M0,0 A0,0 0 0,0 5,5",         // degenerate radii -> line fallback
            "M3,3 A5,5 45 1,1 3,3",         // coincident endpoints
        )
        for (data in datasets) {
            val subpaths = VectorPreviewPathNormalizer.normalize(parse(data))
            // Should produce geometry and never throw.
            assertTrue("expected segments for: $data", subpaths.any { it.segments.isNotEmpty() })
        }
    }

    @Test
    fun multipleSubpathsRemainSeparate() {
        val subpaths = VectorPreviewPathNormalizer.normalize(
            parse("M0,0 L1,1 Z M5,5 L6,6 L7,5 Z"),
        )
        assertEquals(2, subpaths.size)
        assertTrue(subpaths[0].closed)
        assertTrue(subpaths[1].closed)
        assertEquals(0f to 0f, subpaths[0].startX to subpaths[0].startY)
        assertEquals(5f to 5f, subpaths[1].startX to subpaths[1].startY)
    }

    @Test
    fun openSubpathIsNotClosed() {
        val sp = VectorPreviewPathNormalizer.normalize(parse("M0,0 L10,0 L10,10")).single()
        assertTrue(!sp.closed)
        assertEquals(2, sp.segments.size)
    }

    @Test
    fun emptyCommandsProduceNoSubpaths() {
        assertTrue(VectorPreviewPathNormalizer.normalize(emptyList()).isEmpty())
    }

    @Test
    fun quadraticCurveIsPreserved() {
        val sp = VectorPreviewPathNormalizer.normalize(parse("M0,0 Q2,4 4,0")).single()
        val quad = sp.segments.single() as PreviewSegment.Quad
        assertEquals(2f, quad.cx)
        assertEquals(4f, quad.cy)
        assertEquals(4f, quad.endX)
        assertEquals(0f, quad.endY)
    }
}
