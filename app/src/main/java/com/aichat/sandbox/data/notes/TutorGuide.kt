package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import kotlin.math.ceil

/**
 * Phase **I8 — tutor "draw with me" (N4, idea #7)**: the pure-JVM logic behind
 * the guide layer and the step-by-step reveal.
 *
 * The hard part of N4 is *content quality* — whether the model's construction
 * strokes are simple, ordered, and worth tracing — and that is the AI's job,
 * produced through the **existing, inviolable** GENERATE edit-ops pipeline
 * (`add_path` / `add_shape` → [EditPreviewController] → canonical [NoteItem]s,
 * accepted through the same `pendingEdit` surface as any AI edit). This object
 * owns only the plumbing around those canonical items:
 *
 *  - a **dedicated guide layer** — an ordinary [NoteLayer], ghosted (low
 *    opacity) so it reads as something to trace over, **editable** (unlocked,
 *    visible) and on top, never on-disk truth beyond a normal layer;
 *  - **low-clutter step planning** — the authored construction items are split
 *    into a bounded number of ordered steps ([MAX_STEPS]) so a model that emits
 *    fifty tiny paths still teaches in digestible beats;
 *  - and the [TutorSession] state machine for step / skip / back / redo.
 *
 * `StrokeCodec` stays canonical and the mesh/derived layers are untouched — a
 * tutor stroke is byte-indistinguishable from a hand-drawn one (the parity test
 * builds a real ink `Stroke` from a tutor payload to prove it).
 */
object TutorGuide {

    /** Name of the dedicated guide layer the tutor draws construction strokes on. */
    const val GUIDE_LAYER_NAME: String = "Guide"

    /** Ghosted opacity so the guide reads as a trace-over layer, not final ink. */
    const val GUIDE_OPACITY_PERCENT: Int = 45

    /** Upper bound on teaching steps — the low-clutter guard (idea #7's risk). */
    const val MAX_STEPS: Int = 12

    /**
     * Find the note's existing guide layer (by [GUIDE_LAYER_NAME]) so repeated
     * "draw with me" runs reuse one editable layer rather than stacking new ones.
     */
    fun findGuideLayer(layers: List<NoteLayer>): NoteLayer? =
        layers.firstOrNull { it.name == GUIDE_LAYER_NAME }

    /**
     * Build a fresh, ghosted guide [NoteLayer] for [noteId], ordered on top of
     * [existing] layers. Unlocked + visible so the user can erase / tweak the
     * guide as they trace it.
     */
    fun buildGuideLayer(noteId: String, existing: List<NoteLayer>): NoteLayer {
        val nextOrdinal = (existing.maxOfOrNull { it.ordinal } ?: -1) + 1
        return NoteLayer(
            noteId = noteId,
            name = GUIDE_LAYER_NAME,
            opacityPercent = GUIDE_OPACITY_PERCENT,
            visible = true,
            locked = false,
            ordinal = nextOrdinal,
        )
    }

    /**
     * Reparent model-authored construction [items] onto the guide layer so they
     * render ghosted and group with the guide. Pure `copy` — the payloads (the
     * canonical geometry the AI pipeline produced) are untouched.
     */
    fun assignToGuide(items: List<NoteItem>, guideLayerId: String): List<NoteItem> =
        items.map { it.copy(layerId = guideLayerId) }

    /**
     * Split [items] into ordered teaching steps. Items are ordered by
     * [NoteItem.zIndex] (draw order). With at most [MAX_STEPS] items each gets
     * its own step (one construction mark at a time — the clearest teaching
     * unit); beyond that they are chunked evenly so the number of steps never
     * exceeds [MAX_STEPS], keeping the tutor low-clutter. Empty input → no steps.
     */
    fun planSteps(items: List<NoteItem>, maxSteps: Int = MAX_STEPS): List<TutorStep> {
        require(maxSteps >= 1) { "maxSteps must be ≥ 1 (got $maxSteps)" }
        if (items.isEmpty()) return emptyList()
        val ordered = items.sortedBy { it.zIndex }
        val perStep = ceil(ordered.size.toDouble() / maxSteps).toInt().coerceAtLeast(1)
        val steps = ArrayList<TutorStep>()
        var index = 0
        var start = 0
        while (start < ordered.size) {
            val end = (start + perStep).coerceAtMost(ordered.size)
            val chunk = ordered.subList(start, end).toList()
            steps += TutorStep(
                index = index,
                items = chunk,
                instruction = "Step ${index + 1}",
            )
            index++
            start = end
        }
        return steps
    }
}

/**
 * One teaching beat: the construction [items] to reveal/trace at this step and
 * a short [instruction] label. Items are canonical [NoteItem]s on the guide
 * layer.
 */
data class TutorStep(
    val index: Int,
    val items: List<NoteItem>,
    val instruction: String,
)

/**
 * Immutable state machine for a "draw with me" walkthrough. The UI advances a
 * ghost guide one beat at a time; every transition returns a new [TutorSession]
 * (so it drops straight into a `StateFlow`).
 *
 *  - [cursor] is the furthest-advanced step index (`-1` = nothing revealed yet).
 *  - [skipped] holds steps the user stepped past without tracing; their items
 *    stay hidden even though the cursor moved beyond them.
 *
 * `next` reveals the following step, `skip` advances past it without revealing
 * it, `back` steps one beat back (clearing a skip on the step left behind), and
 * `redo` re-surfaces the current step for re-animation without moving the
 * cursor. [reset] returns to the start.
 */
data class TutorSession(
    val steps: List<TutorStep>,
    val cursor: Int = -1,
    val skipped: Set<Int> = emptySet(),
) {
    val size: Int get() = steps.size

    /** True once the cursor has reached (or passed) the final step. */
    val isComplete: Boolean get() = size == 0 || cursor >= size - 1

    /** Fraction of steps walked, in `[0, 1]`. */
    val progress: Float get() = if (size == 0) 1f else ((cursor + 1).toFloat() / size)

    /** The step the cursor currently sits on, or null before the first `next`. */
    val currentStep: TutorStep? get() = steps.getOrNull(cursor)

    /** Items revealed so far: every stepped-to step that wasn't skipped. */
    fun revealedItems(): List<NoteItem> =
        steps.filterIndexed { i, _ -> i <= cursor && i !in skipped }
            .flatMap { it.items }

    /** Ids of all tutor items not yet revealed (the canvas suppresses these). */
    fun hiddenItemIds(): Set<String> =
        steps.filterIndexed { i, _ -> i > cursor || i in skipped }
            .flatMap { step -> step.items.map { it.id } }
            .toHashSet()

    /** Reveal the next step. No-op once [isComplete]. */
    fun next(): TutorSession =
        if (isComplete) this else copy(cursor = cursor + 1, skipped = skipped - (cursor + 1))

    /** Advance past the next step without revealing it. No-op once [isComplete]. */
    fun skip(): TutorSession =
        if (isComplete) this else copy(cursor = cursor + 1, skipped = skipped + (cursor + 1))

    /** Step one beat back, un-hiding the step we leave so it can be re-walked. */
    fun back(): TutorSession =
        if (cursor < 0) this else copy(cursor = cursor - 1, skipped = skipped - cursor)

    /**
     * Re-surface the current step for re-animation ("redo this instruction").
     * State is unchanged — the UI replays [currentStep]'s reveal.
     */
    fun redo(): TutorStep? = currentStep

    /** Back to the start, nothing revealed. */
    fun reset(): TutorSession = copy(cursor = -1, skipped = emptySet())
}
