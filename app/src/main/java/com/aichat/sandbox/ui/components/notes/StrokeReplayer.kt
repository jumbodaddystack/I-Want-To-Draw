package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Sub-phase 9.4 — clips stroke samples against a playback head.
 *
 * Given a v2 stroke (timestamps stored alongside each sample) and a
 * `currentPositionMs`, this returns the subset of samples whose `t` is
 * `<= currentPositionMs`. Strokes whose first sample's `t` is past the
 * playback head are invisible (`isVisible = false`); strokes drawn before
 * the recording started (no v2 timestamps) bypass replay entirely — they
 * stay statically rendered.
 *
 * Filtering is O(N) over points and runs each frame; for a 5-minute
 * session with ~10k samples that's ~10k float comparisons, well under
 * one frame's budget on any device that can run the rest of the app.
 */
object StrokeReplayer {

    /** Returns null if the stroke has no v2 timestamps (caller draws normally). */
    fun clipStroke(item: NoteItem, currentPositionMs: Long): ClipResult? {
        if (item.kind != NoteItem.KIND_STROKE) return null
        if (!StrokeCodec.isV2(item.payload)) return null
        val samples = StrokeCodec.decodeWithT(item.payload)
        val sampleCount = samples.size / StrokeCodec.FLOATS_PER_SAMPLE_V2
        if (sampleCount == 0) return ClipResult.HIDDEN
        // First-sample window: if t0 is in the future, hide; if past, clip.
        val t0 = samples[4]
        if (t0 > currentPositionMs) return ClipResult.HIDDEN
        // Find the last sample with t <= position. Linear scan from the end.
        var lastVisibleSample = sampleCount - 1
        while (lastVisibleSample >= 0) {
            val t = samples[lastVisibleSample * StrokeCodec.FLOATS_PER_SAMPLE_V2 + 4]
            if (t <= currentPositionMs) break
            lastVisibleSample--
        }
        if (lastVisibleSample < 0) return ClipResult.HIDDEN
        val visibleCount = lastVisibleSample + 1
        // Build a v1-shape FloatArray (4 floats per sample) for the
        // renderer — it doesn't care about timestamps.
        val out = FloatArray(visibleCount * StrokeCodec.FLOATS_PER_SAMPLE)
        var src = 0
        var dst = 0
        var i = 0
        while (i < visibleCount) {
            out[dst] = samples[src]
            out[dst + 1] = samples[src + 1]
            out[dst + 2] = samples[src + 2]
            out[dst + 3] = samples[src + 3]
            src += StrokeCodec.FLOATS_PER_SAMPLE_V2
            dst += StrokeCodec.FLOATS_PER_SAMPLE
            i++
        }
        return ClipResult(visible = true, clippedSamples = out)
    }

    /**
     * Identify the subset of items associated with an audio recording.
     * A stroke "belongs to" a recording if it's v2 (so it has timestamps)
     * and its first sample's `t` is in `[0, durationMs]`.
     *
     * Strokes drawn between recordings (or before the first one) are
     * v1 and excluded — they render statically.
     */
    fun itemsForRecording(items: List<NoteItem>, durationMs: Long): List<NoteItem> =
        items.filter { item ->
            if (item.kind != NoteItem.KIND_STROKE) return@filter false
            if (!StrokeCodec.isV2(item.payload)) return@filter false
            val samples = StrokeCodec.decodeWithT(item.payload)
            if (samples.isEmpty()) return@filter false
            val t0 = samples[4]
            t0 in 0f..durationMs.toFloat()
        }

    /**
     * Result of [clipStroke]:
     *  - `isVisible = true` + a populated `clippedSamples` → render this
     *    stroke at the clipped sample count.
     *  - `isVisible = false` → don't render at all (stroke is in the
     *    future).
     */
    data class ClipResult(
        val visible: Boolean,
        val clippedSamples: FloatArray = FLOAT_ARRAY_EMPTY,
    ) {
        companion object {
            val HIDDEN = ClipResult(visible = false)
        }

        @Suppress("EqualsOrHashCode")
        override fun equals(other: Any?): Boolean =
            this === other || (other is ClipResult && other.visible == visible &&
                other.clippedSamples.contentEquals(clippedSamples))

        override fun hashCode(): Int =
            visible.hashCode() * 31 + clippedSamples.contentHashCode()
    }
}

private val FLOAT_ARRAY_EMPTY: FloatArray = FloatArray(0)
