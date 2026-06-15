package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Phase **I8 — timelapse export framing (N4, idea #7)**: the pure-JVM plan a
 * timelapse encoder renders frame-by-frame.
 *
 * Export (c) reuses [NoteRasterizer] frames → video / GIF, but the *encoding*
 * (`MediaCodec` for video, a GIF encoder) needs a real device and a `Bitmap`
 * pipeline, so it can't run on the headless host. What **can** run headless —
 * and is what actually decides whether the timelapse is correct — is the
 * **framing**: which playhead positions to sample, which (clipped) items are
 * visible at each, and the single shared viewport every frame shares so the
 * camera doesn't jump. This object produces exactly that, leaving only the
 * per-frame `NoteRasterizer.render(frame.items, plan.bounds, …)` bitmap call
 * and the codec to the device wrapper.
 *
 * The per-frame [Frame.items] are ready to hand straight to
 * [NoteRasterizer.render]: fully-drawn items as-is and the in-progress stroke
 * already clipped to its drawn prefix by [ReplayTimeline].
 */
object TimelapseFramePlan {

    /** Frames per second a maintainer is most likely to want for a share clip. */
    const val DEFAULT_FPS: Int = 30

    /** One rendered frame: a playhead position and the items visible at it. */
    data class Frame(
        val index: Int,
        val positionMs: Long,
        val items: List<NoteItem>,
    )

    /**
     * A complete, ordered render plan.
     *
     * @property frames the per-frame item sets, in playback order.
     * @property bounds the shared world viewport `[minX, minY, maxX, maxY]` for
     *   every frame (union of all items), or `null` when nothing has geometry.
     * @property fps frames per second the [frames] were sampled at.
     * @property durationMs total timelapse length (mirrors the timeline).
     */
    data class Plan(
        val frames: List<Frame>,
        val bounds: FloatArray?,
        val fps: Int,
        val durationMs: Long,
    ) {
        val frameCount: Int get() = frames.size
    }

    /**
     * Build a frame plan from [timeline] at [fps]. A `holdFrames` tail repeats
     * the final fully-drawn frame so the finished drawing lingers on screen at
     * the end of the clip (0 = no hold).
     */
    fun build(
        timeline: ReplayTimeline,
        fps: Int = DEFAULT_FPS,
        holdFrames: Int = 0,
    ): Plan {
        require(fps > 0) { "fps must be > 0 (got $fps)" }
        require(holdFrames >= 0) { "holdFrames must be ≥ 0 (got $holdFrames)" }
        val positions = timeline.framePositions(fps)
        val bounds = NoteRasterizer.computeBounds(timeline.allItems())
        val frames = ArrayList<Frame>(positions.size + holdFrames)
        var index = 0
        for (pos in positions) {
            frames += Frame(index++, pos, timeline.itemsAt(pos))
        }
        if (holdFrames > 0 && frames.isNotEmpty()) {
            val last = frames.last()
            repeat(holdFrames) {
                frames += last.copy(index = index++)
            }
        }
        return Plan(
            frames = frames,
            bounds = bounds,
            fps = fps,
            durationMs = timeline.totalDurationMs,
        )
    }
}
