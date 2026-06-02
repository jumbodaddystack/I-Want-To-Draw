package com.aichat.sandbox.data.notes

/**
 * Sub-phase 7.5 — canned AI EDIT prompts for the lasso menu and the AI
 * side sheet.
 *
 * Each entry pairs a short user-facing label, a description that becomes
 * the undo entry name on accept, and the natural-language brief sent to
 * the model. `local` entries are handled directly by the view model
 * (Chaikin smoothing, 15° rotation snap); model-backed entries fire an
 * `AskMode.EDIT` request.
 *
 * Recolor is intentionally absent from this list — it opens the colour
 * picker and dispatches a synthesised prompt with the chosen hex, see
 * the editor's `applyAiRecolor` entry point.
 */
enum class CannedEditAction(
    val label: String,
    val undoDescription: String,
    val prompt: String,
    val local: Boolean,
) {
    /** Chaikin smoothing — purely local, no model call. */
    CLEAN_UP(
        label = "Clean up",
        undoDescription = "Clean up",
        prompt = "",
        local = true,
    ),
    /** Rotation snap to nearest 15° — purely local, no model call. */
    STRAIGHTEN(
        label = "Straighten",
        undoDescription = "Straighten",
        prompt = "",
        local = true,
    ),
    /** "AI Clean up" — sends to the model for context-aware smoothing. */
    AI_CLEAN_UP(
        label = "AI Clean up",
        undoDescription = "AI Clean up",
        prompt = "Smooth the selected strokes. Use the `smooth` op with an amount around 0.4. Don't change the strokes' position, size, or colour.",
        local = false,
    ),
    /** Replace freehand shape-like strokes with clean shapes. */
    AUTO_SHAPE(
        label = "Auto-shape",
        undoDescription = "Auto-shape",
        prompt = "Identify any selected strokes that approximate a clean geometric shape (line, rectangle, ellipse, arrow, or polygon) and replace each one with a `replace_with_shape` op that matches the stroke's bounds. Leave anything that doesn't read as a shape alone.",
        local = false,
    ),
    /**
     * Continue a pattern. The model gets the selection and is asked to
     * extend any repeating motif by replacing the trailing strokes with a
     * polyline shape that carries the pattern forward. Phase 7's
     * "no-add-from-scratch" rule means it can only ever substitute the
     * existing strokes; we revisit this if the constraint proves too tight.
     */
    CONTINUE(
        label = "Continue",
        undoDescription = "Continue",
        prompt = "Extend the visible repeating pattern in the selection. Use `replace_with_shape` to substitute the trailing strokes with a polyline that carries the pattern forward by one cycle. Do not add new strokes from scratch.",
        local = false,
    ),

    /**
     * Icon design action — reduce the artwork to its essential strokes.
     * Op-oriented so the model substitutes/smooths rather than inventing
     * detail (which Phase 7's edit schema can't add from scratch).
     */
    SIMPLIFY(
        label = "Simplify",
        undoDescription = "Simplify",
        prompt = "Simplify the selected icon to its essential strokes. Use `replace_with_shape` to swap busy freehand strokes for clean geometric shapes and `smooth` to tidy the rest. Preserve the subject and its colours; remove only redundant or noisy strokes.",
        local = false,
    ),
    /** Icon design action — restyle into a clean flat-design icon. */
    FLAT_STYLE(
        label = "Flat style",
        undoDescription = "Flat style",
        prompt = "Restyle the selected strokes into a clean flat-design icon: even stroke weights, simple geometric shapes via `replace_with_shape`, and smoothed contours via `smooth`. Keep the subject and palette; do not add new elements from scratch.",
        local = false,
    ),
    /** Icon design action — sharpen readable detail without adding clutter. */
    ADD_DETAIL(
        label = "Add detail",
        undoDescription = "Add detail",
        prompt = "Sharpen the selected icon's existing detail so it reads well at small sizes. Use `replace_with_shape` to crisp-up shape-like strokes and `smooth` to refine others. Keep it minimal — do not add new strokes from scratch.",
        local = false,
    ),
}

/**
 * Sub-phase 7.5 — prompt template for [CannedEditAction.AI_CLEAN_UP]-style
 * variants that take a user-supplied colour. The hex is interpolated into
 * the standard recolor brief so the model only has to copy the value back.
 */
fun aiRecolorPrompt(hex: String): String =
    "Set the colour of every selected item to $hex via the `recolor` op."
