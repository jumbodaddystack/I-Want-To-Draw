package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PathNodeMathTest {

    private fun curved() = PathCodec.PathPayload(
        anchors = listOf(
            PathCodec.Anchor(0f, 0f, outDx = 30f, outDy = -40f, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(100f, 0f, inDx = -30f, inDy = -40f, outDx = 30f, outDy = 40f, type = PathCodec.TYPE_SYMMETRIC),
            PathCodec.Anchor(200f, 0f, inDx = -30f, inDy = 40f, type = PathCodec.TYPE_CORNER),
        ),
        closed = false,
    )

    @Test
    fun nearestOnPathFindsAnAnchorPoint() {
        val near = PathNodeMath.nearestOnPath(curved(), 0f, 0f)
        assertNotNull(near)
        assertTrue(near!!.distance < 0.5f)
        assertEquals(0, near.segment)
        assertTrue(near.t < 0.05f)
    }

    @Test
    fun nearestOnPathTracksTheBulge() {
        // Midpoint of segment 0 sits below y = 0 (handles pull down at -40…
        // wait, outDy = -40 pulls *up* in screen coords). Query near the
        // apex and require a sub-pixel distance.
        val payload = curved()
        val s = PathCodec.segment(payload, 0)
        val apexX = PathCodec.cubicAt(s[0], s[2], s[4], s[6], 0.5f)
        val apexY = PathCodec.cubicAt(s[1], s[3], s[5], s[7], 0.5f)
        val near = PathNodeMath.nearestOnPath(payload, apexX, apexY)!!
        assertTrue(near.distance < 0.5f)
        assertEquals(0, near.segment)
        assertTrue(abs(near.t - 0.5f) < 0.1f)
    }

    @Test
    fun insertPreservesGeometry() {
        val payload = curved()
        val before = PathCodec.flatten(payload, stepsPerSegment = 64)
        val after = PathNodeMath.insertAnchor(payload, 0, 0.37f)
        assertEquals(payload.anchors.size + 1, after.anchors.size)
        // Every sample of the new path must lie on (or extremely near) the
        // old curve: max deviation against the dense old polyline.
        val newPts = PathCodec.flatten(after, stepsPerSegment = 64)
        var maxDev = 0f
        var i = 0
        while (i < newPts.size) {
            var best = Float.MAX_VALUE
            var j = 0
            while (j < before.size) {
                val d = hypot(newPts[i] - before[j], newPts[i + 1] - before[j + 1])
                if (d < best) best = d
                j += 2
            }
            if (best > maxDev) maxDev = best
            i += 2
        }
        assertTrue("max deviation $maxDev", maxDev < 1.5f)
    }

    @Test
    fun insertIntoClosingSegmentKeepsOrder() {
        val payload = curved().let { it.withSingleSubpath(it.anchors, closed = true) }
        val segs = payload.segmentCount
        val after = PathNodeMath.insertAnchor(payload, segs - 1, 0.5f)
        assertEquals(payload.anchors.size + 1, after.anchors.size)
        // The inserted anchor is appended at the end (between last and first).
        val inserted = after.anchors.last()
        assertEquals(PathCodec.TYPE_SMOOTH, inserted.type)
    }

    @Test
    fun deleteFloorsAtTwoAnchors() {
        val payload = curved()
        val after = PathNodeMath.deleteAnchor(payload, 1)!!
        assertEquals(2, after.anchors.size)
        assertNull(PathNodeMath.deleteAnchor(after, 0))
    }

    @Test
    fun toggleCornerToSmoothAlignsHandles() {
        val payload = curved()
        val toggled = PathNodeMath.toggleType(payload, 2)
        val a = toggled.anchors[2]
        assertEquals(PathCodec.TYPE_SMOOTH, a.type)
        // In and out handles must be anti-parallel (cross ≈ 0, dot < 0).
        val cross = a.inDx * a.outDy - a.inDy * a.outDx
        val dot = a.inDx * a.outDx + a.inDy * a.outDy
        assertTrue(abs(cross) < 1e-2f)
        assertTrue(dot < 0f)
    }

    @Test
    fun toggleSmoothToCornerKeepsHandles() {
        val payload = curved()
        val toggled = PathNodeMath.toggleType(payload, 0)
        val before = payload.anchors[0]
        val after = toggled.anchors[0]
        assertEquals(PathCodec.TYPE_CORNER, after.type)
        assertEquals(before.outDx, after.outDx, 1e-4f)
        assertEquals(before.outDy, after.outDy, 1e-4f)
    }

    @Test
    fun toggleCornerWithNoHandlesSynthesizesFromChord() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f),
                PathCodec.Anchor(100f, 0f),
                PathCodec.Anchor(200f, 100f),
            ),
            closed = false,
        )
        val toggled = PathNodeMath.toggleType(payload, 1)
        val a = toggled.anchors[1]
        assertEquals(PathCodec.TYPE_SMOOTH, a.type)
        // Handles exist now and run along the prev→next chord direction.
        assertTrue(hypot(a.outDx, a.outDy) > 1f)
        assertTrue(hypot(a.inDx, a.inDy) > 1f)
        val chordX = 200f - 0f
        val chordY = 100f - 0f
        val cross = a.outDx * chordY - a.outDy * chordX
        assertTrue(abs(cross) < 1e-2f * hypot(chordX, chordY) * hypot(a.outDx, a.outDy))
    }

    @Test
    fun moveAnchorCarriesHandles() {
        val payload = curved()
        val moved = PathNodeMath.moveAnchor(payload, 1, 150f, 30f)
        val a = moved.anchors[1]
        assertEquals(150f, a.x, 1e-4f)
        assertEquals(30f, a.y, 1e-4f)
        assertEquals(payload.anchors[1].inDx, a.inDx, 1e-4f)
        assertEquals(payload.anchors[1].outDy, a.outDy, 1e-4f)
    }

    @Test
    fun moveHandleSymmetricMirrorsBoth() {
        val payload = curved()
        val moved = PathNodeMath.moveHandle(payload, 1, out = true, x = 140f, y = 20f)
        val a = moved.anchors[1]
        assertEquals(40f, a.outDx, 1e-4f)
        assertEquals(20f, a.outDy, 1e-4f)
        assertEquals(-40f, a.inDx, 1e-4f)
        assertEquals(-20f, a.inDy, 1e-4f)
    }

    @Test
    fun moveHandleSmoothMirrorsDirectionKeepsLength() {
        val payload = curved()
        val originalInLen = hypot(payload.anchors[0].inDx, payload.anchors[0].inDy)
        val moved = PathNodeMath.moveHandle(payload, 0, out = true, x = 50f, y = 0f)
        val a = moved.anchors[0]
        assertEquals(50f, a.outDx, 1e-4f)
        assertEquals(0f, a.outDy, 1e-4f)
        // In-handle keeps its (zero) length but points the other way.
        assertEquals(originalInLen, hypot(a.inDx, a.inDy), 1e-3f)
    }

    @Test
    fun moveHandleCornerMovesOneSideOnly() {
        val payload = curved()
        val moved = PathNodeMath.moveHandle(payload, 2, out = true, x = 220f, y = 20f)
        val a = moved.anchors[2]
        assertEquals(20f, a.outDx, 1e-4f)
        assertEquals(20f, a.outDy, 1e-4f)
        assertEquals(payload.anchors[2].inDx, a.inDx, 1e-4f)
        assertEquals(payload.anchors[2].inDy, a.inDy, 1e-4f)
    }

    // ── Phase 17.2 — multi-subpath node editing ──────────────────────────

    /** Even-odd donut: outer square + inner hole, both closed contours. */
    private fun donut() = PathCodec.PathPayload(
        subpaths = listOf(
            PathCodec.Subpath(
                anchors = listOf(
                    PathCodec.Anchor(0f, 0f),
                    PathCodec.Anchor(100f, 0f),
                    PathCodec.Anchor(100f, 100f),
                    PathCodec.Anchor(0f, 100f),
                ),
                closed = true,
            ),
            PathCodec.Subpath(
                anchors = listOf(
                    PathCodec.Anchor(30f, 30f),
                    PathCodec.Anchor(70f, 30f),
                    PathCodec.Anchor(70f, 70f),
                    PathCodec.Anchor(30f, 70f),
                ),
                closed = true,
            ),
        ),
        fillRule = PathCodec.FILL_RULE_EVEN_ODD,
        fillArgb = 0xFF112233.toInt(),
    )

    @Test
    fun nearestOnPathPicksTheNearerSubpath() {
        // A point hugging the inner contour's top edge resolves to subpath 1.
        val near = PathNodeMath.nearestOnPath(donut(), 50f, 30f)!!
        assertEquals(1, near.subpath)
        assertTrue(near.distance < 0.5f)
    }

    @Test
    fun insertAnchorOnSecondSubpathPreservesFirst() {
        val payload = donut()
        val after = PathNodeMath.insertAnchor(payload, segment = 0, t = 0.5f, subpath = 1)
        // Edited contour gained an anchor; the other contour is untouched.
        assertEquals(5, after.subpaths[1].anchors.size)
        assertEquals(payload.subpaths[0].anchors, after.subpaths[0].anchors)
        // Style fields ride along.
        assertEquals(PathCodec.FILL_RULE_EVEN_ODD, after.fillRule)
        assertEquals(0xFF112233.toInt(), after.fillArgb)
    }

    @Test
    fun moveAnchorOnSecondSubpathLeavesFirstAlone() {
        val payload = donut()
        val moved = PathNodeMath.moveAnchor(payload, index = 0, x = 35f, y = 35f, subpath = 1)
        assertEquals(35f, moved.subpaths[1].anchors[0].x, 1e-4f)
        assertEquals(35f, moved.subpaths[1].anchors[0].y, 1e-4f)
        assertEquals(payload.subpaths[0].anchors, moved.subpaths[0].anchors)
    }

    @Test
    fun toggleTypeOnSecondSubpathTargetsTheRightAnchor() {
        val payload = donut()
        val toggled = PathNodeMath.toggleType(payload, index = 0, subpath = 1)
        assertEquals(PathCodec.TYPE_SMOOTH, toggled.subpaths[1].anchors[0].type)
        // First subpath's anchors stay corners.
        assertTrue(toggled.subpaths[0].anchors.all { it.type == PathCodec.TYPE_CORNER })
    }

    @Test
    fun deleteAnchorDropsAContourBelowTwoButKeepsOthers() {
        // Square + a 2-anchor open contour; deleting from the small contour
        // removes it entirely while the square survives.
        val payload = PathCodec.PathPayload(
            subpaths = listOf(
                donut().subpaths[0],
                PathCodec.Subpath(
                    anchors = listOf(PathCodec.Anchor(10f, 10f), PathCodec.Anchor(20f, 20f)),
                    closed = false,
                ),
            ),
            fillRule = PathCodec.FILL_RULE_EVEN_ODD,
        )
        val after = PathNodeMath.deleteAnchor(payload, index = 0, subpath = 1)!!
        assertEquals(1, after.subpaths.size)
        assertEquals(4, after.subpaths[0].anchors.size)
        assertEquals(PathCodec.FILL_RULE_EVEN_ODD, after.fillRule)
    }

    @Test
    fun deleteAnchorReturnsNullWhenTheLastContourEmpties() {
        val payload = PathCodec.PathPayload(
            subpaths = listOf(
                PathCodec.Subpath(
                    anchors = listOf(PathCodec.Anchor(0f, 0f), PathCodec.Anchor(50f, 0f)),
                    closed = false,
                ),
            ),
        )
        assertNull(PathNodeMath.deleteAnchor(payload, index = 0, subpath = 0))
    }

    @Test
    fun deleteAnchorAboveTwoKeepsTheContour() {
        val payload = donut()
        val after = PathNodeMath.deleteAnchor(payload, index = 1, subpath = 0)!!
        assertEquals(3, after.subpaths[0].anchors.size)
        // The hole is untouched.
        assertEquals(payload.subpaths[1].anchors, after.subpaths[1].anchors)
    }
}
