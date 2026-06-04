package com.aichat.sandbox.data.vector.edit

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 round-trip tests: [VectorPath] → [EditablePath] → commands.
 *
 * For M/L/C/Z geometry the round trip is *exact* (token-for-token). Quads and
 * arcs are documented as normalized to cubics/lines, so those are checked for
 * curve equality rather than command equality.
 */
class EditablePathRoundTripTest {

    private fun editable(data: String, id: String = "p"): EditablePath =
        EditablePathFactory.fromPath(
            VectorPath(
                id = id,
                pathData = data,
                commands = PathDataParser.parse(data).commands,
                style = VectorStyle(),
            ),
        )

    private fun roundTrip(data: String): List<PathCommand> =
        EditablePathSerializer.toCommands(editable(data))

    @Test
    fun triangleRoundTripsExactly() {
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.LineTo(10f, 0f),
                PathCommand.LineTo(10f, 10f),
                PathCommand.Close(),
            ),
            roundTrip("M0,0 L10,0 L10,10 Z"),
        )
    }

    @Test
    fun openPolylineHasNoClose() {
        val cmds = roundTrip("M0,0 L10,0 L10,10")
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.LineTo(10f, 0f),
                PathCommand.LineTo(10f, 10f),
            ),
            cmds,
        )
        assertFalse(cmds.any { it is PathCommand.Close })
    }

    @Test
    fun singleCubicRoundTripsExactly() {
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.CubicTo(2f, 0f, 8f, 10f, 10f, 10f),
            ),
            roundTrip("M0,0 C2,0 8,10 10,10"),
        )
    }

    @Test
    fun relativeTriangleResolvesToAbsolute() {
        // m0,0 l10,0 l0,10 z is the same triangle expressed relatively.
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.LineTo(10f, 0f),
                PathCommand.LineTo(10f, 10f),
                PathCommand.Close(),
            ),
            roundTrip("m0,0 l10,0 l0,10 z"),
        )
    }

    @Test
    fun closedCurveFoldsClosingPointAndRoundTripsExactly() {
        // A closed two-cubic loop whose final cubic lands back on the start.
        // The factory folds the coincident closing point into the start anchor;
        // the serializer re-emits the curved closing segment before Z.
        val data = "M0,0 C0,-5 10,-5 10,0 C10,5 0,5 0,0 Z"
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.CubicTo(0f, -5f, 10f, -5f, 10f, 0f),
                PathCommand.CubicTo(10f, 5f, 0f, 5f, 0f, 0f),
                PathCommand.Close(),
            ),
            roundTrip(data),
        )
        // And the folded subpath has exactly two anchors (start + far point).
        assertEquals(2, editable(data).subpaths.single().anchors.size)
    }

    @Test
    fun quadElevatesToEquivalentCubic() {
        val sp = editable("M0,0 Q5,10 10,0").subpaths.single()
        assertEquals(2, sp.anchors.size)
        val a0 = sp.anchors[0]
        val a1 = sp.anchors[1]
        // The elevated cubic must trace the same curve as the original quad.
        for (i in 0..10) {
            val t = i / 10f
            val q = quadAt(0f, 0f, 5f, 10f, 10f, 0f, t)
            val c = cubicAt(
                a0.x, a0.y,
                a0.outHandle!!.x, a0.outHandle!!.y,
                a1.inHandle!!.x, a1.inHandle!!.y,
                a1.x, a1.y, t,
            )
            assertEquals("x@$t", q.first, c.first, 1e-3f)
            assertEquals("y@$t", q.second, c.second, 1e-3f)
        }
    }

    @Test
    fun multipleSubpathsArePreserved() {
        val ep = editable("M0,0 L1,0 L1,1 Z M5,5 L6,5 L6,6 Z")
        assertEquals(2, ep.subpaths.size)
        assertTrue(ep.subpaths.all { it.closed })
        assertEquals(listOf("p.s0", "p.s1"), ep.subpaths.map { it.id })
        assertEquals("p.s0.a0", ep.subpaths[0].anchors.first().id)
    }

    @Test
    fun straightAnchorsHaveNoHandles() {
        val a = editable("M0,0 L10,0").subpaths.single().anchors
        assertNull(a[0].outHandle)
        assertNull(a[1].inHandle)
        assertEquals(AnchorType.CORNER, a[0].type)
    }

    @Test
    fun colinearEqualHandlesClassifyAsSymmetric() {
        // Middle anchor (4,0): inHandle (4,-3), outHandle (4,3) — colinear, equal.
        val a = editable("M0,0 C0,-3 4,-3 4,0 C4,3 8,3 8,0").subpaths.single().anchors
        val mid = a[1]
        assertEquals(4f, mid.x, 0f)
        assertEquals(AnchorType.SYMMETRIC, mid.type)
    }

    @Test
    fun styleAndIdentityArePreserved() {
        val styled = VectorPath(
            id = "icon-leg",
            name = "leg",
            pathData = "M0,0 L4,0",
            commands = PathDataParser.parse("M0,0 L4,0").commands,
            style = VectorStyle(fillColor = "#FF0000", strokeWidth = 2f),
        )
        val back = EditablePathSerializer.toVectorPath(EditablePathFactory.fromPath(styled))
        assertEquals("icon-leg", back.id)
        assertEquals("leg", back.name)
        assertEquals("#FF0000", back.style.fillColor)
        assertEquals(2f, back.style.strokeWidth)
        // PathDataFormatter delimits commands by letter, no separator space.
        assertEquals("M0,0L4,0", back.pathData)
    }

    // --- local Bézier samplers for curve-equality checks ---

    private fun quadAt(
        p0x: Float, p0y: Float, cx: Float, cy: Float, p1x: Float, p1y: Float, t: Float,
    ): Pair<Float, Float> {
        val u = 1 - t
        val x = u * u * p0x + 2 * u * t * cx + t * t * p1x
        val y = u * u * p0y + 2 * u * t * cy + t * t * p1y
        return x to y
    }

    private fun cubicAt(
        p0x: Float, p0y: Float, c1x: Float, c1y: Float,
        c2x: Float, c2y: Float, p1x: Float, p1y: Float, t: Float,
    ): Pair<Float, Float> {
        val u = 1 - t
        val x = u * u * u * p0x + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * p1x
        val y = u * u * u * p0y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * p1y
        return x to y
    }
}
