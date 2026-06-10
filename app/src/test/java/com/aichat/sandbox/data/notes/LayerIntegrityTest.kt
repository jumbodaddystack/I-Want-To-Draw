package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerIntegrityTest {

    @Test
    fun `consistent aggregate reports no drift`() {
        val note = note()
        val layers = listOf(layer(note.id, id = "layer-a"), layer(note.id, id = "layer-b"))
        val items = listOf(
            item(note.id, layerId = "layer-a"),
            item(note.id, layerId = "layer-b"),
            item(note.id, layerId = null), // default layer — always legal
        )

        assertTrue(LayerIntegrity.findDrift(note, items, layers).isEmpty())
    }

    @Test
    fun `empty aggregate reports no drift`() {
        assertTrue(LayerIntegrity.findDrift(note(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `item referencing missing layer is flagged`() {
        val note = note()
        val layers = listOf(layer(note.id, id = "layer-a"))
        val items = listOf(item(note.id, id = "item-1", layerId = "layer-gone"))

        val drift = LayerIntegrity.findDrift(note, items, layers)
        assertEquals(1, drift.size)
        assertTrue(drift.single().contains("item-1"))
        assertTrue(drift.single().contains("layer-gone"))
    }

    @Test
    fun `null layerId is never flagged even with no layers`() {
        val note = note()
        val items = listOf(item(note.id, layerId = null))

        assertTrue(LayerIntegrity.findDrift(note, items, emptyList()).isEmpty())
    }

    @Test
    fun `item belonging to another note is flagged`() {
        val note = note(id = "note-1")
        val items = listOf(item(noteId = "note-other", id = "item-1", layerId = null))

        val drift = LayerIntegrity.findDrift(note, items, emptyList())
        assertEquals(1, drift.size)
        assertTrue(drift.single().contains("item-1"))
        assertTrue(drift.single().contains("note-other"))
    }

    @Test
    fun `layer belonging to another note is flagged`() {
        val note = note(id = "note-1")
        val layers = listOf(layer(noteId = "note-other", id = "layer-a"))

        val drift = LayerIntegrity.findDrift(note, emptyList(), layers)
        assertEquals(1, drift.size)
        assertTrue(drift.single().contains("layer-a"))
        assertTrue(drift.single().contains("note-other"))
    }

    @Test
    fun `multiple problems are all reported`() {
        val note = note(id = "note-1")
        val layers = listOf(layer(noteId = "note-other", id = "layer-a"))
        val items = listOf(
            item(noteId = "note-other", id = "item-1", layerId = null),
            item(note.id, id = "item-2", layerId = "layer-gone"),
        )

        assertEquals(3, LayerIntegrity.findDrift(note, items, layers).size)
    }

    private fun note(id: String = "note-1") = Note(
        id = id,
        title = "t",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f,
        minY = 0f,
        maxX = 0f,
        maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )

    private fun layer(noteId: String, id: String) = NoteLayer(
        id = id,
        noteId = noteId,
        name = "Layer $id",
        opacityPercent = 100,
        visible = true,
        locked = false,
        ordinal = 0,
    )

    private fun item(noteId: String, id: String = "item-1", layerId: String?) = NoteItem(
        id = id,
        noteId = noteId,
        zIndex = 0,
        kind = NoteItem.KIND_STROKE,
        tool = "pen",
        colorArgb = 0,
        baseWidthPx = 1f,
        payload = byteArrayOf(1),
        layerId = layerId,
    )
}
