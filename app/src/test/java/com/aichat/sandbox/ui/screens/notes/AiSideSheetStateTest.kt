package com.aichat.sandbox.ui.screens.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun turn(id: String, state: TurnState): AskTurn = AskTurn(
        id = id,
        prompt = "p",
        selectionSummary = null,
        replyBuffer = "",
        state = state,
    )
}
