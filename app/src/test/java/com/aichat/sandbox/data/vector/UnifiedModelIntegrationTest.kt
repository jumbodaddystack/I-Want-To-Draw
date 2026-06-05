package com.aichat.sandbox.data.vector

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.vector.edit.EditablePathFactory
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import com.aichat.sandbox.data.vector.notesbridge.EditOpToManualEdit
import com.aichat.sandbox.data.vector.notesbridge.NoteVectorBridge
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 — end-to-end proof that ink → canonical [VectorDocument] flows through
 * the *existing* node-edit / AI / writer machinery once the bridge has run, i.e.
 * the notes canvas and the Tune-Up workspace are two views of one model.
 */
class UnifiedModelIntegrationTest {

    private val viewport = VectorViewport(100f, 100f, 100f, 100f)

    private fun strokeItem(id: String): NoteItem {
        val arr = floatArrayOf(5f, 5f, 1f, 0f, 30f, 40f, 1f, 0f, 60f, 10f, 1f, 0f, 90f, 50f, 1f, 0f)
        return NoteItem(id, "n", 0, NoteItem.KIND_STROKE, "pen", 0xFF202020.toInt(), 3f, StrokeCodec.encode(arr))
    }

    private fun rectItem(id: String): NoteItem =
        NoteItem(id, "n", 1, NoteItem.KIND_SHAPE, null, 0xFF202020.toInt(), 2f,
            ShapeCodec.encode(Shape.Rect(10f, 10f, 40f, 40f)))

    @Test
    fun bridgedStrokeAndShapeExportLosslesslyThroughDocumentWriter() {
        val result = NoteVectorBridge.toDocument(listOf(strokeItem("a"), rectItem("b")), viewport)
        val xml = AndroidVectorDrawableWriter.write(result.document)
        val reparsed = AndroidVectorDrawableParser.parse(xml)

        // Same number of paths, same order, byte-identical path data — lossless
        // with respect to the canonical document. (VectorDrawable XML carries no
        // id attribute, so the parser reissues ids; we compare by position.)
        val orig = result.document.allPaths()
        val back = reparsed.allPaths()
        assertEquals(orig.size, back.size)
        for ((o, b) in orig.zip(back)) {
            assertEquals(o.pathData, b.pathData)
            assertTrue(b.commands?.isNotEmpty() == true)
        }
    }

    @Test
    fun bridgedDocumentIsNodeEditableAndStaysStableAcrossWriteParse() {
        val result = NoteVectorBridge.toDocument(listOf(strokeItem("a")), viewport)
        val path = result.document.allPaths().first()

        // Enter the Phase-1 editable model, nudge the first anchor, write back.
        val editable = EditablePathFactory.fromPath(path)
        val sub = editable.subpaths.first()
        val moved = sub.copy(
            anchors = sub.anchors.mapIndexed { i, a ->
                if (i == 0) a.copy(x = a.x + 4f, y = a.y - 2f) else a
            },
        )
        val editedPath = EditablePathSerializer.toVectorPath(
            editable.copy(subpaths = listOf(moved)),
        )
        val editedDoc = result.document.replacePath(path.id, editedPath)

        // Round-trip through the writers and confirm the geometry is stable.
        // (The parser reissues ids from XML, so match by position — there is one path.)
        val xml = AndroidVectorDrawableWriter.write(editedDoc)
        val back = AndroidVectorDrawableParser.parse(xml).allPaths().first()
        assertEquals(editedPath.pathData, back.pathData)
    }

    @Test
    fun aiEditPlanAppliedToBridgedDocumentProducesValidDocument() {
        val result = NoteVectorBridge.toDocument(listOf(strokeItem("a")), viewport)
        val xml = AndroidVectorDrawableWriter.write(result.document)
        val pathId = result.itemToPathId["a"]!!

        val plan = VectorEditPlan(
            schema = 1,
            mode = VectorEditPlan.Mode.TUNE_UP,
            summary = "tidy",
            operations = listOf(
                VectorEditOperation.RecolorPaths(
                    target = VectorPathTarget(pathIds = listOf(pathId)),
                    strokeColor = "#FF00FF00",
                    fillColor = null,
                ),
            ),
        )
        val applied = VectorEditPlanApplier.apply(result.document, xml, plan)
        assertEquals(1, applied.recoloredPathCount)
        assertEquals("#FF00FF00", applied.document.allPaths().first { it.id == pathId }.style.strokeColor)

        // No error-level structural warnings on the resulting document.
        val errorCodes = setOf(
            VectorWarning.Codes.MISSING_PATH_DATA,
            VectorWarning.Codes.NO_FILL_OR_STROKE,
            VectorWarning.Codes.NON_POSITIVE_VIEWPORT,
            VectorWarning.Codes.NEGATIVE_STROKE_WIDTH,
        )
        val warnings = VectorDocumentValidator.validate(applied.document)
        assertFalse(warnings.any { it.code in errorCodes })
    }

    @Test
    fun noteEditOpRecolorViaBridgeMatchesManualEdit() {
        val result = NoteVectorBridge.toDocument(listOf(strokeItem("a")), viewport)
        val xml = AndroidVectorDrawableWriter.write(result.document)
        val pathId = result.itemToPathId["a"]!!

        // The notes op references a short id; compose short→pathId via the bridge map.
        val shortToPath = mapOf("s_001" to pathId)
        val edits = EditOpToManualEdit.convert(
            listOf(EditOp.Recolor(ids = listOf("s_001"), colorArgb = 0xFFFF0000.toInt())),
            shortToPath,
        )
        val manual = VectorManualEditApplier.apply(result.document, xml, edits)
        assertEquals("#FFFF0000", manual.document.allPaths().first { it.id == pathId }.style.strokeColor)
    }
}
