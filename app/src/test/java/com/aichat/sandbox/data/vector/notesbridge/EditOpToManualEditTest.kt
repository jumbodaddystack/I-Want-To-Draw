package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.vector.VectorManualEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditOpToManualEditTest {

    private val map = mapOf("s_001" to "note_a", "s_002" to "note_b")

    @Test
    fun recolorMapsToManualRecolorWithHexStroke() {
        val edit = EditOpToManualEdit.convertOne(
            EditOp.Recolor(ids = listOf("s_001"), colorArgb = 0xFF445566.toInt()),
            map,
        )
        assertTrue(edit is VectorManualEdit.RecolorPaths)
        edit as VectorManualEdit.RecolorPaths
        assertEquals(listOf("note_a"), edit.pathIds)
        assertEquals("#FF445566", edit.strokeColor)
    }

    @Test
    fun restyleMapsWidthAndSkipsWidthlessRestyle() {
        val withWidth = EditOpToManualEdit.convertOne(
            EditOp.Restyle(ids = listOf("s_001", "s_002"), width = 3.5f, opacity = null), map,
        )
        assertTrue(withWidth is VectorManualEdit.RestylePaths)
        assertEquals(3.5f, (withWidth as VectorManualEdit.RestylePaths).strokeWidth!!, 1e-3f)
        assertEquals(listOf("note_a", "note_b"), withWidth.pathIds)

        val noWidth = EditOpToManualEdit.convertOne(
            EditOp.Restyle(ids = listOf("s_001"), width = null, opacity = 0.5f), map,
        )
        assertEquals(null, noWidth)
    }

    @Test
    fun deleteAndSimplifyMap() {
        assertTrue(
            EditOpToManualEdit.convertOne(EditOp.Delete(listOf("s_001")), map)
                is VectorManualEdit.DeletePaths,
        )
        val simp = EditOpToManualEdit.convertOne(EditOp.Simplify(listOf("s_002"), tolerance = 1.2f), map)
        assertTrue(simp is VectorManualEdit.SimplifyPaths)
        assertEquals(1.2f, (simp as VectorManualEdit.SimplifyPaths).tolerance, 1e-3f)
    }

    @Test
    fun geometryOpsAndUnknownIdsAreSkipped() {
        // Geometry op → not expressible as a manual edit.
        assertEquals(null, EditOpToManualEdit.convertOne(EditOp.Smooth(listOf("s_001"), 0.5f), map))
        // Unknown id → no resolvable target.
        assertEquals(null, EditOpToManualEdit.convertOne(EditOp.Delete(listOf("nope")), map))
        // convert() filters the unsupported ones out of a mixed list.
        val edits = EditOpToManualEdit.convert(
            listOf(
                EditOp.Recolor(listOf("s_001"), 0xFF000000.toInt()),
                EditOp.Smooth(listOf("s_002"), 0.3f),
                EditOp.Delete(listOf("s_002")),
            ),
            map,
        )
        assertEquals(2, edits.size)
    }
}
