package com.aichat.sandbox.ui.components.notes

import kotlin.math.round

/**
 * Phase 3 — integer / pixel-grid quantization, a thin sibling of [Snap].
 *
 * [Snap] already owns angle / world-grid / endpoint *magnetism* (snap only when
 * within a tolerance window). The icon production pipeline needs the opposite: an
 * **unconditional** quantize that lands every placed/dragged anchor exactly on the
 * integer icon grid. Rather than reopen the well-tested [Snap], this adds a new
 * mask bit and a pure quantizer that reuses [Snap.SnapResult]. The reducer runs a
 * coordinate through the existing `Snap.*` steps first, then — when
 * [MASK_PIXEL] is set — through [quantizeInBounds] as the final step, so the
 * persisted anchor is integer-aligned and never leaves the artboard.
 *
 * `step` is `viewportWidth / targetDp` for a true device-pixel grid (= `1f` when
 * authoring directly on the 24/48/108 viewport).
 */
object EditSnap {

    /** Bit 3 of `snapMask` — quantize anchors to the integer icon grid. */
    const val MASK_PIXEL: Int = 0x8

    /**
     * Snap ([x], [y]) to the nearest multiple of [step] unconditionally (no
     * tolerance window — this is quantization, not magnetism). A non-positive
     * [step] is a no-op.
     */
    fun quantize(x: Float, y: Float, step: Float = 1f): Snap.SnapResult {
        if (step <= 0f) return Snap.SnapResult(x, y, false)
        return Snap.SnapResult(round(x / step) * step, round(y / step) * step, true)
    }

    /**
     * Quantize then clamp into [bounds] (`[minX, minY, maxX, maxY]`) so a snapped
     * anchor can never leave the artboard. Returns the input unclamped when
     * [bounds] is malformed.
     */
    fun quantizeInBounds(x: Float, y: Float, bounds: FloatArray, step: Float = 1f): Snap.SnapResult {
        val q = quantize(x, y, step)
        if (bounds.size < 4) return q
        return Snap.SnapResult(
            q.x.coerceIn(bounds[0], bounds[2]),
            q.y.coerceIn(bounds[1], bounds[3]),
            true,
        )
    }
}
