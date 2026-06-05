package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.IconTarget
import com.aichat.sandbox.data.vector.OpticalAdjust
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.ui.components.notes.EditSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 3 — pixel snap, keyline toggle, and optical-adjust at the reducer level. */
class VectorEditReducerPixelSnapTest {

    private val reducer = VectorEditReducer()

    private fun emptyDoc(edge: Float = 24f) = VectorDocument(
        viewport = VectorViewport(edge, edge, edge, edge),
        root = VectorGroup(id = "root", children = emptyList()),
    )

    private fun penState(snapMask: Int): VectorEditState = VectorEditState(
        document = emptyDoc(),
        activeTool = EditTool.PEN,
        pendingPen = PenDraft(),
        snapMask = snapMask,
    )

    @Test
    fun placeAnchor_withPixelSnap_quantizesToIntegerGrid() {
        val state = penState(EditSnap.MASK_PIXEL)
        val after = reducer.reduce(state, VectorEditAction.PlaceAnchor(12.7f, 3.2f))
        val a = after.pendingPen!!.anchors.single()
        assertEquals(13f, a.x, EPS)
        assertEquals(3f, a.y, EPS)
    }

    @Test
    fun placeAnchor_withoutPixelSnap_keepsFloatCoords() {
        val state = penState(snapMask = 0)
        val after = reducer.reduce(state, VectorEditAction.PlaceAnchor(12.7f, 3.2f))
        val a = after.pendingPen!!.anchors.single()
        assertEquals(12.7f, a.x, EPS)
        assertEquals(3.2f, a.y, EPS)
    }

    @Test
    fun placeAnchor_withPixelSnap_clampsIntoArtboard() {
        val state = penState(EditSnap.MASK_PIXEL)
        val after = reducer.reduce(state, VectorEditAction.PlaceAnchor(30f, -5f))
        val a = after.pendingPen!!.anchors.single()
        assertEquals(24f, a.x, EPS)
        assertEquals(0f, a.y, EPS)
    }

    @Test
    fun moveSelection_singleAnchor_withPixelSnap_landsOnGrid() {
        val path = EditablePath(
            pathId = "p",
            subpaths = listOf(EditSubpath("p.s0", listOf(EditAnchor("p.s0.a0", 5.0f, 5.0f)), closed = false)),
            style = VectorStyle(fillColor = "#000000"),
        )
        val state = VectorEditState(
            document = emptyDoc(),
            editing = path,
            selection = Selection(setOf("p.s0.a0")),
            snapMask = EditSnap.MASK_PIXEL,
        )
        val after = reducer.reduce(state, VectorEditAction.MoveSelection(2.6f, 0.1f))
        val a = after.editing!!.subpaths.single().anchors.single()
        assertEquals(8f, a.x, EPS) // 5 + 2.6 = 7.6 → 8
        assertEquals(5f, a.y, EPS) // 5 + 0.1 = 5.1 → 5
    }

    @Test
    fun setPixelSnap_togglesBitWithoutDisturbingOthers() {
        val base = VectorEditState(document = emptyDoc(), snapMask = 0x2 /* grid */)
        val on = reducer.reduce(base, VectorEditAction.SetPixelSnap(true))
        assertEquals(0x2 or EditSnap.MASK_PIXEL, on.snapMask)
        val off = reducer.reduce(on, VectorEditAction.SetPixelSnap(false))
        assertEquals(0x2, off.snapMask)
    }

    @Test
    fun toggleKeyline_addsThenClearsOverlay() {
        val base = VectorEditState(document = emptyDoc())
        assertNull(base.keyline)
        val on = reducer.reduce(base, VectorEditAction.ToggleKeyline)
        assertNotNull(on.keyline)
        assertEquals(24f, on.keyline!!.edge, EPS)
        val off = reducer.reduce(on, VectorEditAction.ToggleKeyline)
        assertNull(off.keyline)
    }

    @Test
    fun setOpticalAdjust_updatesSizeSet_withoutTouchingMasterGeometry() {
        val base = VectorEditState(document = emptyDoc())
        val after = reducer.reduce(
            base,
            VectorEditAction.SetOpticalAdjust(IconTarget.ADAPTIVE_108, OpticalAdjust(scale = 0.9f)),
        )
        assertEquals(0.9f, after.sizeSet!!.adjust[IconTarget.ADAPTIVE_108]!!.scale, EPS)
        // Master document is untouched; this is not an undoable geometry change.
        assertEquals(base.document, after.sizeSet!!.master)
        assertEquals(base.document, after.document)
        assertEquals(0, after.undoStack.size)
    }

    companion object {
        private const val EPS = 1e-4f
    }
}
