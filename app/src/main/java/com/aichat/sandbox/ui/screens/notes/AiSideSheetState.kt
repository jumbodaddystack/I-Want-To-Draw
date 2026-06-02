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
     * clears in the background. Cleared mid-sheet only via the explicit
     * scope chip tap (see sub-phase 2.7).
     */
    val pendingSelection: List<NoteItem>? = null,
    val turns: List<AskTurn> = emptyList(),
    val inputText: String = "",
    val activeModelId: String = "",
    /**
     * Whether the footer's text box submits a question (ASK, prose reply) or
     * an edit instruction (EDIT, staged preview). Icons default to EDIT since
     * they are design surfaces; notes default to ASK.
     */
    val footerMode: AiFooterMode = AiFooterMode.ASK,
    /**
     * True when the edited resource is an icon (`Note.isIcon`). Selects the
     * design-oriented quick actions and edit-first copy instead of the
     * note-centric ask prompts. Kept in sync with the note in the view model.
     */
    val isIcon: Boolean = false,
) {
    /** True while any turn is in flight. Drives the Send / Cancel buttons. */
    val isStreaming: Boolean get() = turns.any { it.state is TurnState.Streaming }

    /**
     * Human-readable scope description rendered above the canned-prompt row
     * (sub-phase 2.7). Either the frozen selection summary, "Whole note", or
     * "Whole icon" for icon resources.
     */
    val scopeLabel: String
        get() = pendingSelection?.let { summarizeSelectionForScope(it) }
            ?: if (isIcon) "Whole icon" else "Whole note"
}

/** Footer submit mode for the AI side sheet — see [AiSideSheetState.footerMode]. */
enum class AiFooterMode { ASK, EDIT }

/**
 * One-tap design actions shown in the icon variant of the canned-action row.
 * Each maps to an edit in the view model: the first four route through the
 * model-backed EDIT pipeline (see [com.aichat.sandbox.data.notes.CannedEditAction]);
 * [RECOLOR] opens the colour picker and applies an AI recolor with the chosen
 * colour.
 */
enum class IconQuickAction(val label: String) {
    SIMPLIFY("Simplify"),
    FLAT_STYLE("Flat style"),
    ADD_DETAIL("Add detail"),
    AUTO_SHAPE("Auto-shape"),
    RECOLOR("Recolor"),
}

data class AskTurn(
    val id: String,
    val prompt: String,
    /** Short label like "3 strokes selected" or null for whole-note scope. */
    val selectionSummary: String?,
    val replyBuffer: String,
    val state: TurnState,
    /**
     * Marker for the Convert-to-text fast path. Convert-to-text bypasses
     * [NoteAiService] and runs ML Kit Digital Ink directly; sub-phase 2.7
     * surfaces a one-off "Insert as text box" action on these turns as a
     * preview of the general reply-action row that lands in sub-phase 2.8.
     */
    val isConvertResult: Boolean = false,
)

sealed interface TurnState {
    /** Stream is in flight; buffer may still grow. */
    data object Streaming : TurnState

    /** Stream completed successfully. Reply buffer is final. */
    data object Done : TurnState

    /** Stream failed. [message] is the surfaced reason. */
    data class Error(val message: String) : TurnState
}

/**
 * Canned prompt templates surfaced as `AssistChip`s above the input field
 * (sub-phase 2.7). Each is a one-tap, single-line template — tapping fires
 * immediately rather than only populating the input.
 *
 * `Convert to text` is special: it bypasses [NoteAiService] entirely and
 * runs handwriting OCR on the in-scope strokes, then renders the recognized
 * text as a finished `Done` turn (no API spend). Available only when a
 * selection is active.
 */
enum class CannedPrompt(val label: String, val template: String) {
    EXPLAIN("Explain", "Explain this in plain English."),
    EXPAND("Expand", "Expand on the ideas in this note. Suggest additional points."),
    CONVERT_TO_TEXT("Convert to text", ""),
    SUMMARIZE("Summarize", "Summarize this note in 3–5 bullet points."),
    CONTINUE("Continue this", "Continue the thought naturally from where it leaves off.");

    companion object {
        /** The four prompts that route through the standard ask pipeline. */
        val ASK_PROMPTS: List<CannedPrompt> = entries - CONVERT_TO_TEXT
    }
}

internal fun summarizeSelectionForScope(selection: List<NoteItem>): String {
    val strokes = selection.count { it.kind == "stroke" }
    val texts = selection.count { it.kind == "text" }
    return when {
        strokes > 0 && texts > 0 -> "$strokes strokes, $texts text selected"
        strokes > 0 -> if (strokes == 1) "1 stroke selected" else "$strokes strokes selected"
        texts > 0 -> if (texts == 1) "1 text selected" else "$texts text items selected"
        else -> "${selection.size} items selected"
    }
}
