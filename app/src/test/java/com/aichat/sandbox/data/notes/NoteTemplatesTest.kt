package com.aichat.sandbox.data.notes

import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTemplatesTest {

    private fun build(template: NoteTemplate) =
        NoteTemplates.build(template, noteId = "note-1", layerId = "layer-1")

    @Test
    fun everyTemplateBuildsNonEmptyDecodableContent() {
        for (template in NoteTemplate.entries) {
            val content = build(template)
            assertTrue("${template.id} should have items", content.items.isNotEmpty())
            assertTrue("${template.id} should have a frame", content.frames.isNotEmpty())
            for (item in content.items) {
                assertEquals("note-1", item.noteId)
                assertEquals("layer-1", item.layerId)
                // Every payload must decode with its own codec — a template
                // that seeds undecodable bytes would wedge the editor open.
                when (item.kind) {
                    StickyCodec.KIND -> StickyCodec.decode(item.payload)
                    ConnectorCodec.KIND -> ConnectorCodec.decode(item.payload)
                    Shape.KIND -> ShapeCodec.decode(item.payload)
                    TextItemCodec.KIND -> TextItemCodec.decode(item.payload)
                    else -> throw AssertionError("unexpected kind ${item.kind}")
                }
            }
            for (frame in content.frames) {
                assertEquals("note-1", frame.noteId)
                assertTrue(frame.maxX > frame.minX && frame.maxY > frame.minY)
            }
        }
    }

    @Test
    fun rebuildingProducesFreshIds() {
        val first = build(NoteTemplate.BRAINSTORM)
        val second = build(NoteTemplate.BRAINSTORM)
        val firstIds = first.items.mapTo(HashSet()) { it.id } + first.frames.map { it.id }
        val secondIds = second.items.mapTo(HashSet()) { it.id } + second.frames.map { it.id }
        assertTrue("re-building must never collide ids", (firstIds intersect secondIds).isEmpty())
        assertEquals(first.items.size + first.frames.size, firstIds.size)
    }

    @Test
    fun brainstormHasSixStickies() {
        val stickies = build(NoteTemplate.BRAINSTORM).items
            .filter { it.kind == StickyCodec.KIND }
        assertEquals(6, stickies.size)
        // Fills come from the preset roster.
        for (item in stickies) {
            val fill = StickyCodec.decode(item.payload).fillArgb
            assertTrue(fill in StickyCodec.PRESET_FILLS)
        }
    }

    @Test
    fun mindMapConnectorsBindToRealEllipses() {
        val content = build(NoteTemplate.MIND_MAP)
        val itemIds = content.items.mapTo(HashSet()) { it.id }
        val connectors = content.items.filter { it.kind == ConnectorCodec.KIND }
        assertEquals(4, connectors.size)
        for (item in connectors) {
            val payload = ConnectorCodec.decode(item.payload)
            assertNotNull(payload.fromItemId)
            assertNotNull(payload.toItemId)
            assertTrue(payload.fromItemId in itemIds)
            assertTrue(payload.toItemId in itemIds)
        }
        // The spokes stack under the bubbles.
        val maxConnectorZ = connectors.maxOf { it.zIndex }
        val minEllipseZ = content.items
            .filter { it.kind == Shape.KIND }
            .minOf { it.zIndex }
        assertTrue(maxConnectorZ < minEllipseZ)
    }

    @Test
    fun unknownTemplateIdResolvesNull() {
        assertNull(NoteTemplate.fromId("not-a-template"))
        assertNull(NoteTemplate.fromId(null))
        assertEquals(NoteTemplate.KANBAN, NoteTemplate.fromId("kanban"))
    }
}
