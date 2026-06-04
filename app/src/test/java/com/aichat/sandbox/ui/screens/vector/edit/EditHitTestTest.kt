package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Phase 1 (step 1b) — exercises the pure hit-testing the canvas gestures lean on:
 * anchor / handle / segment selection across zoom levels, the closing-segment wrap
 * for closed subpaths, de Casteljau-accurate cubic picking, and that a returned
 * [EditHitTest.Hit.Segment] feeds straight back into the reducer's insert. No
 * Compose/Android on the classpath.
 */
class EditHitTestTest {

    private fun editable(data: String, id: String = "p"): EditablePath =
        EditablePathFactory.fromPath(
            VectorPath(
                id = id,
                pathData = data,
                commands = PathDataParser.parse(data).commands,
                style = VectorStyle(),
            ),
        )

    /** Screen-px touch radius converted to world units for [scale]. */
    private fun tol(scale: Float): Float =
        EditHitTest.worldTolerance(EditHitTest.DEFAULT_TOLERANCE_PX, scale)

    // ---- anchors ----

    @Test
    fun hitsNearestAnchor() {
        val path = editable("M0,0 L20,0 L20,20 Z")
        val hit = EditHitTest.hitAnchor(path, 19f, 1f, tolerance = 5f)
        assertEquals("p.s0.a1", hit?.anchorId)
        assertEquals("p.s0", hit?.subpathId)
    }

    @Test
    fun anchorMissOutsideTolerance() {
        val path = editable("M0,0 L20,0 L20,20 Z")
        assertNull(EditHitTest.hitAnchor(path, 10f, 10f, tolerance = 5f))
    }

    /** Same screen-px touch: misses when zoomed in (small world tol), hits zoomed out. */
    @Test
    fun anchorPickIsZoomDependent() {
        val path = editable("M0,0 L20,0 L20,20 Z")
        // Query 5 world-units from anchor a1 (20,0).
        val qx = 15f; val qy = 0f
        assertNull("zoomed in: 22px ÷ 8 ≈ 2.75 world < 5", EditHitTest.hitAnchor(path, qx, qy, tol(8f)))
        assertNotNull("100%: 22px world > 5", EditHitTest.hitAnchor(path, qx, qy, tol(1f)))
        assertNotNull("zoomed out: 22px ÷ .25 = 88 world", EditHitTest.hitAnchor(path, qx, qy, tol(0.25f)))
    }

    // ---- handles ----

    @Test
    fun handleHitsOnlyForCandidateAnchors() {
        // a0(0,0) carries an out-handle at (0,10); a1(10,0) an in-handle at (10,10).
        val path = editable("M0,0 C0,10 10,10 10,0")
        val a0 = "p.s0.a0"
        val notCandidate = EditHitTest.hitHandle(path, 0f, 10f, tolerance = 5f, candidates = emptySet())
        assertNull("no candidates → no handle", notCandidate)
        val hit = EditHitTest.hitHandle(path, 0f, 10f, tolerance = 5f, candidates = setOf(a0))
        assertEquals(a0, hit?.anchorId)
        assertEquals(EditHitTest.HandleSide.OUT, hit?.side)
    }

    @Test
    fun handleWinsOverAnchorAndSegmentInCombinedHitTest() {
        val path = editable("M0,0 C0,10 10,10 10,0")
        val a0 = "p.s0.a0"
        // Query right on a0's out-handle knob (0,10), with a0 selected.
        val hit = EditHitTest.hitTest(
            path, 0f, 10f, tolerance = tol(1f),
            selection = Selection(setOf(a0)),
        )
        assertTrue("expected a handle hit", hit is EditHitTest.Hit.Handle)
        hit as EditHitTest.Hit.Handle
        assertEquals(a0, hit.anchorId)
        assertEquals(EditHitTest.HandleSide.OUT, hit.side)
    }

    @Test
    fun combinedHitTestFallsBackToAnchorWithoutHandleCandidates() {
        val path = editable("M0,0 L20,0 L20,20 Z")
        val hit = EditHitTest.hitTest(path, 20.5f, 0.5f, tolerance = tol(1f))
        assertTrue(hit is EditHitTest.Hit.Anchor)
        assertEquals("p.s0.a1", (hit as EditHitTest.Hit.Anchor).anchorId)
    }

    // ---- segments ----

    @Test
    fun hitsLineSegmentAtMidpointT() {
        val path = editable("M0,0 L20,0 L20,20 Z")
        // Midpoint of the first (top) edge a0→a1.
        val hit = EditHitTest.hitSegment(path, 10f, 0.3f, tolerance = 2f)
        assertNotNull(hit)
        hit!!
        assertEquals("p.s0", hit.subpathId)
        assertEquals(0, hit.segmentIndex)
        assertEquals(0.5f, hit.t, 1e-3f)
        assertEquals(10f, hit.x, 1e-3f)
        assertEquals(0f, hit.y, 1e-3f)
    }

    /** The diagonal of a closed triangle is the closing segment: index n-1, wraps to a0. */
    @Test
    fun hitsClosingSegmentWithWrappedIndex() {
        val path = editable("M0,0 L20,0 L20,20 Z")
        val hit = EditHitTest.hitSegment(path, 10f, 10f, tolerance = 2f)
        assertNotNull(hit)
        hit!!
        assertEquals(2, hit.segmentIndex) // closing span a2 → a0
        assertEquals(0.5f, hit.t, 1e-3f)
        assertEquals(10f, hit.x, 1e-3f)
        assertEquals(10f, hit.y, 1e-3f)
    }

    @Test
    fun openSubpathHasNoClosingSegment() {
        val path = editable("M0,0 L20,0 L20,20") // no Z
        // The point that would lie on a closing diagonal hits nothing now.
        assertNull(EditHitTest.hitSegment(path, 10f, 10f, tolerance = 2f))
        // But the real edges still pick up.
        assertNotNull(EditHitTest.hitSegment(path, 10f, 0f, tolerance = 2f))
    }

    @Test
    fun picksPointOnCubicViaDeCasteljau() {
        // Cubic (0,0)→(10,0), handles (0,10) & (10,10): the curve bulges to (5,7.5) at t=.5.
        val path = editable("M0,0 C0,10 10,10 10,0")
        val hit = EditHitTest.hitSegment(path, 5f, 8f, tolerance = 3f)
        assertNotNull(hit)
        hit!!
        assertEquals(0, hit.segmentIndex)
        assertEquals(0.5f, hit.t, 0.05f)
        // Returned point is on the curve (within refinement accuracy), not the chord.
        assertEquals(5f, hit.x, 0.2f)
        assertEquals(7.5f, hit.y, 0.2f)
        assertTrue("returned point is the nearest on-curve point",
            hypot(hit.x - 5f, hit.y - 8f) < 1f)
    }

    @Test
    fun segmentMissOutsideTolerance() {
        val path = editable("M0,0 L20,0")
        assertNull(EditHitTest.hitSegment(path, 10f, 30f, tolerance = 2f))
    }

    // ---- a handle directly on the model (in-handle side) ----

    @Test
    fun hitsInHandleSide() {
        val path = EditablePath(
            pathId = "p",
            subpaths = listOf(
                EditSubpath(
                    id = "p.s0",
                    closed = false,
                    anchors = listOf(
                        EditAnchor(id = "p.s0.a0", x = 0f, y = 0f, outHandle = ControlPoint(3f, 0f)),
                        EditAnchor(id = "p.s0.a1", x = 10f, y = 0f, inHandle = ControlPoint(7f, 0f)),
                    ),
                ),
            ),
            style = VectorStyle(),
        )
        val hit = EditHitTest.hitHandle(path, 7f, 0f, tolerance = 1f, candidates = setOf("p.s0.a1"))
        assertEquals("p.s0.a1", hit?.anchorId)
        assertEquals(EditHitTest.HandleSide.IN, hit?.side)
    }

    // ---- the segment result drives the reducer's insert ----

    @Test
    fun segmentHitFeedsReducerInsertExactly() {
        val reducer = VectorEditReducer()
        val path = editable("M0,0 C0,10 10,10 10,0")
        // Seed a state already in edit on this path.
        val seeded = VectorEditState(
            document = com.aichat.sandbox.data.vector.VectorDocument(
                viewport = com.aichat.sandbox.data.vector.VectorViewport(24f, 24f, 24f, 24f),
                root = com.aichat.sandbox.data.vector.VectorGroup(id = "root", children = emptyList()),
            ),
            editing = path,
        )
        val before = seeded.editing!!.subpaths.single().anchors.size

        val hit = EditHitTest.hitSegment(path, 5f, 8f, tolerance = 3f)!!
        val after = reducer.reduce(
            seeded,
            VectorEditAction.InsertAnchorOnSegment(hit.subpathId, hit.segmentIndex, hit.t),
        )
        val anchors = after.editing!!.subpaths.single().anchors
        assertEquals(before + 1, anchors.size)
        // The inserted node lands at the curve point the hit-test reported.
        val inserted = anchors.first { it.id.contains(".m") }
        assertEquals(hit.x, inserted.x, 1e-3f)
        assertEquals(hit.y, inserted.y, 1e-3f)
    }

    // ---- tolerance helper ----

    @Test
    fun worldToleranceScalesInversely() {
        assertEquals(22f, EditHitTest.worldTolerance(22f, 1f), 1e-6f)
        assertEquals(11f, EditHitTest.worldTolerance(22f, 2f), 1e-6f)
        assertEquals(88f, EditHitTest.worldTolerance(22f, 0.25f), 1e-6f)
        // Degenerate scale falls back to the screen value rather than dividing by zero.
        assertEquals(22f, EditHitTest.worldTolerance(22f, 0f), 1e-6f)
    }
}
