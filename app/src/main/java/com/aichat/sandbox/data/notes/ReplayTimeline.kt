package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Phase **I8 — timestamp-driven replay (N4, idea #7)**: the pure-JVM frame
 * timeline that turns a static note into an ordered "timelapse" animation.
 *
 * This is the headless core of replay (a): given a list of [NoteItem]s it
 * builds a deterministic **draw-order timeline** and answers "what is visible
 * at playhead position `t`?" with the strokes already drawn fully, the stroke
 * currently being drawn clipped to its drawn prefix, and everything later
 * hidden. It composes on the canonical [StrokeCodec] payloads and the v2
 * per-sample `t` lane (already synced to audio — see [InkInterop]); nothing
 * here renders or persists, so it runs on the plain JVM test host.
 *
 * ## How the v2 `t` lane drives playback
 *  - **Order** follows the canvas's single source of truth, [NoteItem.zIndex]
 *    (creation / paint order). For strokes drawn during a recording this is the
 *    same order the v2 first-sample timestamps ascend in — the parity test
 *    ([com.aichat.sandbox.data.ink.parity] `ReplayTutorParityTest`) pins that
 *    against the real ink engine's `StrokeInput.elapsedTimeMillis`.
 *  - **Per-stroke duration** is the stroke's *real* drawing span
 *    (`lastT - firstT` from the v2 lane), clamped to a teaching-friendly window
 *    so a 4-minute pause between marks doesn't stall the timelapse and a flick
 *    isn't invisible. v1 strokes (no timestamps) and non-stroke kinds get a
 *    fixed [Config.defaultStrokeMs].
 *  - **Partial-stroke animation** samples the v2 `t` lane: at a local fraction
 *    `f` of the stroke's segment we reveal exactly the samples whose
 *    (stroke-relative) time is `<= f · span`, so a stroke that paused mid-draw
 *    shows that pause. v1 strokes fall back to a uniform sample-index reveal.
 *
 * The result is independent of the (gappy, recording-relative) audio clock, so
 * a "draw-with-me" replay can run at teaching pace with no audio at all.
 */
class ReplayTimeline private constructor(
    /** One entry per input item, in playback (draw) order. */
    val segments: List<Segment>,
    /** Total wall-clock length of the timelapse in ms (≥ 0). */
    val totalDurationMs: Long,
) {

    /**
     * One item's slot on the timeline. [startMs] is when it begins to appear,
     * [endMs] when it is fully drawn; for a stroke the interval in between is
     * the partial-draw animation. Non-stroke kinds pop in fully at [startMs].
     */
    data class Segment(
        val item: NoteItem,
        val startMs: Long,
        val endMs: Long,
        /** Sample count for a stroke (0 for non-stroke kinds). */
        val sampleCount: Int,
        /** True when the payload carries a real v2 per-sample `t` lane. */
        val timed: Boolean,
    ) {
        val isStroke: Boolean get() = item.kind == NoteItem.KIND_STROKE && sampleCount > 0
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    /**
     * Pacing knobs for [build]. Defaults are tuned for a watchable timelapse:
     * short strokes get a visible minimum, long strokes a sane maximum, and a
     * small inter-stroke gap reads as "pen lifted".
     */
    data class Config(
        /** Playback speed multiplier; `2f` plays twice as fast. Clamped > 0. */
        val speed: Float = 1f,
        /** Floor on a single stroke's draw time (so a flick is still seen). */
        val strokeMinMs: Long = 140L,
        /** Ceiling on a single stroke's draw time (so a slow mark isn't a stall). */
        val strokeMaxMs: Long = 1600L,
        /** Time a v1 stroke / non-stroke kind occupies (no real span to use). */
        val defaultStrokeMs: Long = 360L,
        /** Pause inserted between consecutive segments ("pen lifted"). */
        val gapMs: Long = 90L,
    )

    /**
     * Render-ready items visible at [positionMs]: fully-drawn items as-is, the
     * in-progress stroke clipped to its drawn prefix (a fresh `NoteItem` whose
     * payload is the clipped samples — never persisted), later items omitted.
     * Returned in draw order; callers that paint by z-index can re-sort.
     */
    fun itemsAt(positionMs: Long): List<NoteItem> {
        val out = ArrayList<NoteItem>(segments.size)
        for (seg in segments) {
            if (positionMs < seg.startMs) continue
            if (seg.isStroke && positionMs < seg.endMs) {
                clippedStroke(seg, positionMs)?.let { out += it }
            } else {
                out += seg.item
            }
        }
        return out
    }

    /**
     * Playhead positions (ms) for an [fps]-frame timelapse covering the whole
     * timeline, always including `0` and [totalDurationMs]. Deterministic and
     * monotonic; consumed by [TimelapseFramePlan].
     */
    fun framePositions(fps: Int): LongArray {
        require(fps > 0) { "fps must be > 0 (got $fps)" }
        if (totalDurationMs <= 0L) return longArrayOf(0L)
        val frameCount = max(1, ceil(totalDurationMs / 1000.0 * fps).toInt())
        val out = LongArray(frameCount + 1)
        for (i in 0..frameCount) {
            out[i] = (i.toLong() * 1000L / fps).coerceAtMost(totalDurationMs)
        }
        out[frameCount] = totalDurationMs
        return out
    }

    /** All items on the timeline, in draw order (for bounds / export viewport). */
    fun allItems(): List<NoteItem> = segments.map { it.item }

    /**
     * Build the clipped prefix of the in-progress [seg] at [positionMs]. The
     * v2 `t` lane paces the reveal (samples with stroke-relative time ≤ the
     * local fraction of the real span); v1 strokes reveal a uniform fraction of
     * their samples. Returns null when fewer than one sample is visible.
     */
    private fun clippedStroke(seg: Segment, positionMs: Long): NoteItem? {
        val v2 = StrokeCodec.decodeWithT(seg.item.payload) // [x,y,p,tilt,t]*
        val count = seg.sampleCount
        if (count < 1) return null
        val span = (seg.endMs - seg.startMs).coerceAtLeast(1L)
        val fraction = ((positionMs - seg.startMs).toFloat() / span).coerceIn(0f, 1f)

        val visible: Int = if (seg.timed) {
            val firstT = v2[StrokeCodec.FLOATS_PER_SAMPLE] // t of sample 0
            val lastT = v2[(count - 1) * StrokeCodec.FLOATS_PER_SAMPLE_V2 + 4]
            val realSpan = lastT - firstT
            if (realSpan <= 0f) {
                // Degenerate timing — fall back to uniform index reveal.
                max(1, ceil(fraction * count).toInt())
            } else {
                val cutoff = firstT + fraction * realSpan
                var n = 0
                var i = 0
                while (i < count) {
                    if (v2[i * StrokeCodec.FLOATS_PER_SAMPLE_V2 + 4] <= cutoff) n = i + 1 else break
                    i++
                }
                n.coerceAtLeast(1)
            }
        } else {
            max(1, ceil(fraction * count).toInt())
        }.coerceAtMost(count)

        // Emit a v1-shape [x,y,p,tilt] payload of the visible prefix — the
        // renderer ignores timestamps and this is never persisted.
        val out = FloatArray(visible * StrokeCodec.FLOATS_PER_SAMPLE)
        var src = 0
        var dst = 0
        var i = 0
        while (i < visible) {
            out[dst] = v2[src]
            out[dst + 1] = v2[src + 1]
            out[dst + 2] = v2[src + 2]
            out[dst + 3] = v2[src + 3]
            src += StrokeCodec.FLOATS_PER_SAMPLE_V2
            dst += StrokeCodec.FLOATS_PER_SAMPLE
            i++
        }
        return seg.item.copy(payload = StrokeCodec.encode(out))
    }

    companion object {
        /**
         * Build a timeline from [items]. Items are ordered by [NoteItem.zIndex]
         * (the canvas's canonical draw order) and laid out back-to-back with a
         * gap between each; stroke durations come from the v2 `t` span (clamped),
         * everything else from [Config.defaultStrokeMs]. An empty / geometry-less
         * list yields a zero-length timeline.
         */
        fun build(items: List<NoteItem>, config: Config = Config()): ReplayTimeline {
            val speed = config.speed.coerceAtLeast(0.01f)
            val gap = (config.gapMs / speed).roundToLong().coerceAtLeast(0L)
            val ordered = items.sortedBy { it.zIndex }
            val segments = ArrayList<Segment>(ordered.size)
            var cursor = 0L
            for (item in ordered) {
                val (sampleCount, timed, rawDur) = measure(item, config)
                val dur = (rawDur / speed).roundToLong().coerceAtLeast(1L)
                val start = cursor
                val end = start + dur
                segments += Segment(item, start, end, sampleCount, timed)
                cursor = end + gap
            }
            val total = segments.lastOrNull()?.endMs ?: 0L
            return ReplayTimeline(segments, total)
        }

        /** (sampleCount, timed, rawDurationMs-before-speed) for one item. */
        private fun measure(item: NoteItem, config: Config): Triple<Int, Boolean, Long> {
            if (item.kind != NoteItem.KIND_STROKE) {
                return Triple(0, false, config.defaultStrokeMs)
            }
            val timed = StrokeCodec.isV2(item.payload)
            val v2 = StrokeCodec.decodeWithT(item.payload)
            val count = v2.size / StrokeCodec.FLOATS_PER_SAMPLE_V2
            if (count < 1) return Triple(0, false, config.defaultStrokeMs)
            if (!timed) return Triple(count, false, config.defaultStrokeMs)
            val firstT = v2[StrokeCodec.FLOATS_PER_SAMPLE]
            val lastT = v2[(count - 1) * StrokeCodec.FLOATS_PER_SAMPLE_V2 + 4]
            val span = (lastT - firstT).roundToInt().toLong()
            val dur = span.coerceIn(config.strokeMinMs, config.strokeMaxMs)
            return Triple(count, true, dur)
        }
    }
}
