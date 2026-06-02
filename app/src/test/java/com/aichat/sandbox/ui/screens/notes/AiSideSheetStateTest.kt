package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-level coverage of the sub-phase 2.6 [AiSideSheetState] helpers. The
 * full streaming round-trip lives in [com.aichat.sandbox.data.notes.NoteAiServiceTest];
 * here we just lock down the small predicates the UI uses to drive button
 * visibility.
 */
class AiSideSheetStateTest {

    @Test
    fun isStreamingReturnsTrueWhenAnyTurnStreaming() {
        val state = AiSideSheetState(
            turns = listOf(
                turn("a", TurnState.Done),
                turn("b", TurnState.Streaming),
            )
        )
        assertTrue(state.isStreaming)
    }

    @Test
    fun isStreamingFalseWhenAllTurnsTerminal() {
        val state = AiSideSheetState(
            turns = listOf(
                turn("a", TurnState.Done),
                turn("b", TurnState.Error("boom")),
            )
        )
        assertFalse(state.isStreaming)
    }

    @Test
    fun isStreamingFalseOnEmptyConversation() {
        assertFalse(AiSideSheetState().isStreaming)
    }

    @Test
    fun defaultStateIsClosed() {
        val state = AiSideSheetState()
        assertFalse(state.isOpen)
        assertEquals(0, state.turns.size)
        assertEquals("", state.inputText)
    }

    @Test
    fun scopeLabelIsWholeNoteWithoutSelection() {
        assertEquals("Whole note", AiSideSheetState().scopeLabel)
    }

    @Test
    fun scopeLabelIsWholeIconForIconWithoutSelection() {
        assertEquals("Whole icon", AiSideSheetState(isIcon = true).scopeLabel)
    }

    @Test
    fun scopeLabelPrefersSelectionOverIconLabel() {
        val state = AiSideSheetState(isIcon = true, pendingSelection = listOf(item("a", "stroke")))
        assertEquals("1 stroke selected", state.scopeLabel)
    }

    @Test
    fun defaultFooterModeIsAsk() {
        assertEquals(AiFooterMode.ASK, AiSideSheetState().footerMode)
    }

    @Test
    fun iconQuickActionsCoverDesignActionsNotConvert() {
        val labels = IconQuickAction.entries.map { it.label }
        assertTrue(labels.contains("Simplify"))
        assertTrue(labels.contains("Recolor"))
        assertTrue(labels.contains("Auto-shape"))
        assertFalse(labels.contains("Convert to text"))
    }

    @Test
    fun scopeLabelSingularStroke() {
        val state = AiSideSheetState(pendingSelection = listOf(item("a", "stroke")))
        assertEquals("1 stroke selected", state.scopeLabel)
    }

    @Test
    fun scopeLabelMultipleStrokes() {
        val state = AiSideSheetState(
            pendingSelection = listOf(
                item("a", "stroke"),
                item("b", "stroke"),
                item("c", "stroke"),
            )
        )
        assertEquals("3 strokes selected", state.scopeLabel)
    }

    @Test
    fun scopeLabelMixedSelection() {
        val state = AiSideSheetState(
            pendingSelection = listOf(
                item("a", "stroke"),
                item("b", "stroke"),
                item("c", "text"),
            )
        )
        assertEquals("2 strokes, 1 text selected", state.scopeLabel)
    }

    @Test
    fun cannedAskPromptsExcludeConvertToText() {
        val labels = CannedPrompt.ASK_PROMPTS.map { it.label }
        assertTrue(labels.contains("Explain"))
        assertTrue(labels.contains("Summarize"))
        assertTrue(labels.contains("Continue this"))
        assertFalse(labels.contains("Convert to text"))
    }

    @Test
    fun cannedPromptTemplatesNonEmptyExceptConvert() {
        for (prompt in CannedPrompt.entries) {
            if (prompt == CannedPrompt.CONVERT_TO_TEXT) {
                assertEquals("", prompt.template)
            } else {
                assertTrue(prompt.template.isNotBlank())
            }
        }
    }

    @Test
    fun convertResultTurnIsMarked() {
        val turn = AskTurn(
            id = "x",
            prompt = "Convert to text",
            selectionSummary = null,
            replyBuffer = "hello",
            state = TurnState.Done,
            isConvertResult = true,
        )
        assertTrue(turn.isConvertResult)
    }

    @Test
    fun pendingSelectionPersistsThroughCopy() {
        val state = AiSideSheetState(pendingSelection = listOf(item("a", "stroke")))
        val copied = state.copy(inputText = "hi")
        assertNotNull(copied.pendingSelection)
        val cleared = state.copy(pendingSelection = null)
        assertNull(cleared.pendingSelection)
        assertEquals("Whole note", cleared.scopeLabel)
    }

    private fun item(id: String, kind: String): NoteItem = NoteItem(
        id = id,
        noteId = "note",
        zIndex = 0,
        kind = kind,
        tool = null,
        colorArgb = 0,
        baseWidthPx = 0f,
        payload = ByteArray(0),
    )

    private fun turn(id: String, state: TurnState): AskTurn = AskTurn(
        id = id,
        prompt = "p",
        selectionSummary = null,
        replyBuffer = "",
        state = state,
    )
}
