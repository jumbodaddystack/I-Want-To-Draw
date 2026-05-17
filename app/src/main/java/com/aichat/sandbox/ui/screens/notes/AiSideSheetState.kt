package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * In-memory state for the editor's AI side sheet (sub-phase 2.6 of
 * `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * The sheet shows a list of [AskTurn]s — each turn is one user prompt plus
 * the (possibly still-streaming) model reply. Turns are intentionally one-
 * shot: prior turns are NOT packed into subsequent requests; multi-turn
 * context packing is a deliberate follow-up (see 2.6 risks in the phase doc).
 *
 * Lives in [NoteEditorViewModel] so it survives configuration changes and is
 * cleared when the editor is left. Persisting across editor exit is out of
 * scope for v1.
 */
data class AiSideSheetState(
    val isOpen: Boolean = false,
    /**
     * Selection captured at [openSheet] time. Frozen for the lifetime of the
     * sheet so the displayed scope chip doesn't drift if the user lasso-
     * clears in the background.
     */
    val pendingSelection: List<NoteItem>? = null,
    val turns: List<AskTurn> = emptyList(),
    val inputText: String = "",
    val activeModelId: String = "",
) {
    /** True while any turn is in flight. Drives the Send / Cancel buttons. */
    val isStreaming: Boolean get() = turns.any { it.state is TurnState.Streaming }
}

data class AskTurn(
    val id: String,
    val prompt: String,
    /** Short label like "3 strokes selected" or null for whole-note scope. */
    val selectionSummary: String?,
    val replyBuffer: String,
    val state: TurnState,
)

sealed interface TurnState {
    /** Stream is in flight; buffer may still grow. */
    data object Streaming : TurnState

    /** Stream completed successfully. Reply buffer is final. */
    data object Done : TurnState

    /** Stream failed. [message] is the surfaced reason. */
    data class Error(val message: String) : TurnState
}
