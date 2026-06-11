package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 10.4 — `groupId` on the persisted undo log. The field is optional
 * JSON, so logs written before the field existed (and logs written by
 * future builds with extra fields) keep decoding.
 */
class EditorActionCodecGroupTest {

    private fun item(id: String, groupId: String?) = NoteItem(
        id = id,
        noteId = "note-1",
        zIndex = 3,
        kind = NoteItem.KIND_STROKE,
        tool = "pen",
        colorArgb = 0xFF112233.toInt(),
        baseWidthPx = 4f,
        payload = byteArrayOf(1, 2, 3),
        layerId = "layer-1",
        groupId = groupId,
    )

    @Test
    fun groupIdRoundTripsThroughCompositeEdit() {
        val before = item("a", groupId = null)
        val after = item("a", groupId = "g1")
        val action = EditorAction.CompositeEdit(
            description = "Group",
            added = emptyList(),
            removed = emptyList(),
            modified = listOf(before to after),
        )
        val encoded = EditorActionCodec.encode(listOf(action), emptyList())
        val decoded = EditorActionCodec.decode(encoded)
        val roundTripped = decoded.past.single() as EditorAction.CompositeEdit
        val (b, a) = roundTripped.modified.single()
        assertNull(b.groupId)
        assertEquals("g1", a.groupId)
        assertEquals(before, b)
        assertEquals(after, a)
    }

    @Test
    fun groupIdRoundTripsThroughAddItems() {
        val action = EditorAction.AddItems(listOf(item("a", groupId = "g7")))
        val decoded = EditorActionCodec.decode(
            EditorActionCodec.encode(listOf(action), emptyList())
        )
        val roundTripped = decoded.past.single() as EditorAction.AddItems
        assertEquals("g7", roundTripped.items.single().groupId)
    }

    @Test
    fun legacyJsonWithoutGroupIdDecodesNull() {
        // Hand-written pre-Phase-10 log: no groupId property on the item.
        val json = """
            {"schema":1,"future":[],"past":[{"type":"AddItems","items":[
              {"id":"a","noteId":"note-1","zIndex":0,"kind":"stroke",
               "tool":"pen","colorArgb":-1,"baseWidthPx":4.0,"payload":"AQID"}
            ]}]}
        """.trimIndent()
        val decoded = EditorActionCodec.decode(json)
        val action = decoded.past.single() as EditorAction.AddItems
        assertNull(action.items.single().groupId)
    }
}
