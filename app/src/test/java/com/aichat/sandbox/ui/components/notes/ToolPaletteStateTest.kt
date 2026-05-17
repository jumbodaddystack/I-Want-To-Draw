package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Lasso landed in sub-phase 1.8; text is still gated behind 1.9.
        assertTrue(Tool.LASSO.enabledInPalette)
        assertTrue(Tool.LASSO.isLasso)
        assertFalse(Tool.TEXT.enabledInPalette)
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
