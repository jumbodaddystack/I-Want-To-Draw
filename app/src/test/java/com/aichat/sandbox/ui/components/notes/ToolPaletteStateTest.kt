package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPaletteStateTest {

    @Test
    fun toolFlagsClassifyEachEntry() {
        assertTrue(Tool.PEN.isInk)
        assertTrue(Tool.HIGHLIGHTER.isInk)
        assertTrue(Tool.PENCIL.isInk)
        assertFalse(Tool.ERASER_STROKE.isInk)
        assertFalse(Tool.ERASER_AREA.isInk)
        assertTrue(Tool.ERASER_STROKE.isEraser)
        assertTrue(Tool.ERASER_AREA.isEraser)
        assertFalse(Tool.PEN.isEraser)
        // Lasso landed in sub-phase 1.8, text in sub-phase 1.9.
        assertTrue(Tool.LASSO.enabledInPalette)
        assertTrue(Tool.LASSO.isLasso)
        assertTrue(Tool.TEXT.enabledInPalette)
        assertTrue(Tool.TEXT.isText)
    }

    @Test
    fun fromIdRoundTripsEveryTool() {
        // Palette persistence stores `Tool.id` strings; every id must resolve
        // back to its enum or the restored selection silently falls through.
        Tool.entries.forEach { tool ->
            assertEquals(tool, Tool.fromId(tool.id))
        }
    }

    @Test
    fun fromIdRejectsUnknownIds() {
        assertNull(Tool.fromId(null))
        assertNull(Tool.fromId(""))
        assertNull(Tool.fromId("marker_3000"))
    }

    @Test
    fun inkToolIdsMatchPalettePrefsStoreContract() {
        // ToolPalettePrefsStore persists per-ink-tool colour/width slots keyed
        // by these ids; drift between the enum and the store's set would drop
        // a tool's persistence on the floor.
        val inkIds = Tool.entries.filter { it.isInk }.map { it.id }.toSet()
        assertEquals(
            com.aichat.sandbox.data.notes.ToolPalettePrefsStore.INK_TOOL_IDS,
            inkIds,
        )
    }

    @Test
    fun toolIdsMatchStrokeRendererConstants() {
        // The codec persists `tool` as a string; renderer constants must match
        // the enum ids so per-tool paint configuration finds the right branch.
        assertEquals(StrokeRenderer.TOOL_PEN, Tool.PEN.id)
        assertEquals(StrokeRenderer.TOOL_HIGHLIGHTER, Tool.HIGHLIGHTER.id)
        assertEquals(StrokeRenderer.TOOL_PENCIL, Tool.PENCIL.id)
    }
}
