package com.aichat.sandbox.ui.components.notes

import kotlin.math.pow
import kotlin.math.sin

/**
 * Per-tool pressure / tilt → width + alpha curves (sub-phase 5.1).
 *
 * Pure functions, no Android dependencies, so the curves can be pinned by
 * JVM unit tests without spinning up a Robolectric host. The renderer
 * ([StrokeRenderer]) consumes [SegmentStyle] per sample at draw time.
 *
 * Conventions:
 *  - `pressure` is the normalized 0..1 value reported by `MotionEvent.getPressure`;
 *    we clamp defensively in case a payload predates the clamping in
 *    `DrawingSurface` (older notes occasionally landed > 1.0).
 *  - `tiltRadians` is the value reported by `MotionEvent.AXIS_TILT` — **radians**,
 *    range `[0, π/2]` with 0 = stylus vertical. We normalize to `[0, 1]` via
 *    `tilt / (π/2)` before feeding the curve.
 *  - Width output is in CSS-px world space; the caller multiplies by
 *    `baseWidthPx` and may clamp to the viewport-aware min/max.
 *  - Alpha is `[0, 1]` linear; the caller converts to 0..255 for [android.graphics.Paint].
 *
 * Both output channels are clamped to defensive ranges so a corrupt payload
 * (or a freak MotionEvent) can't blow up to a 1000-px-wide invisible stroke.
 */
object ToolDynamics {

    /** Output of every curve — width in px, alpha in `[0, 1]`. */
    data class SegmentStyle(val widthPx: Float, val alpha: Float)

    /** Minimum stroke width below which anti-aliased lines disappear. */
    const val MIN_WIDTH_PX: Float = 0.5f

    /** Sanity cap so a runaway value doesn't render a screen-filling blob. */
    const val MAX_WIDTH_PX: Float = 64f

    /** Below this the stroke is visually indistinguishable from no stroke. */
    const val MIN_ALPHA: Float = 0.05f

    /** Maximum alpha. */
    const val MAX_ALPHA: Float = 1f

    private val HALF_PI: Float = (Math.PI / 2.0).toFloat()

    /**
     * Pen: pressure modulates width across `0.35×–1.15×` the base width using
     * a `pressure^0.7` ease so light strokes still register and very firm
     * strokes don't overshoot perceptually. Tilt is ignored — most users
     * write with the pen nearly vertical and tilt feedback feels like noise.
     */
    fun pen(baseWidthPx: Float, pressure: Float, tiltRadians: Float): SegmentStyle {
        val p = pressure.coerceIn(0f, 1f).toDouble().pow(0.7).toFloat()
        val width = baseWidthPx * lerp(0.35f, 1.15f, p)
        return SegmentStyle(
            widthPx = width.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
            alpha = MAX_ALPHA,
        )
    }

    /**
     * Pencil: width broadens with tilt (so a flat-held pencil shades wider),
     * alpha tracks pressure (light strokes ghost, firm strokes are dense).
     * The `sin(tilt)` factor gives a natural ease toward the cap rather than
     * the linear ramp the previous renderer used.
     */
    fun pencil(baseWidthPx: Float, pressure: Float, tiltRadians: Float): SegmentStyle {
        val tiltN = (tiltRadians / HALF_PI).coerceIn(0f, 1f)
        val tiltGain = sin(tiltN * HALF_PI)  // 0 → 0, π/2 → 1
        val width = baseWidthPx * lerp(0.7f, 1.6f, tiltGain)
        val p = pressure.coerceIn(0f, 1f).toDouble().pow(0.5).toFloat()
        val alpha = lerp(0.35f, 1.0f, p)
        return SegmentStyle(
            widthPx = width.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
            alpha = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA),
        )
    }

    /**
     * Highlighter: constant width, constant 35% alpha. Pressure / tilt are
     * deliberately ignored so a single pass produces an even fill — the
     * "feel" of a chisel-tip highlighter comes from its uniformity. Users
     * who want variation can recolor / change width per stroke.
     */
    fun highlighter(baseWidthPx: Float, pressure: Float, tiltRadians: Float): SegmentStyle {
        return SegmentStyle(
            widthPx = baseWidthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
            alpha = HIGHLIGHTER_ALPHA,
        )
    }

    /**
     * Marker (Phase 6.6 placeholder): firm wide stroke, slight pressure-on-
     * alpha modulation, no tilt. Wired here so the brush-texture phase only
     * has to add a texture and not rewrite the dynamics.
     */
    fun marker(baseWidthPx: Float, pressure: Float, tiltRadians: Float): SegmentStyle {
        val p = pressure.coerceIn(0f, 1f)
        val width = baseWidthPx * lerp(0.9f, 1.15f, p)
        val alpha = lerp(0.85f, 1.0f, p)
        return SegmentStyle(
            widthPx = width.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
            alpha = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA),
        )
    }

    /**
     * Dispatch by tool id (the string stored on `NoteItem.tool`). Falls back
     * to [pen] for unknown / null tools so a forward-compatible payload
     * doesn't render as a missing stroke.
     */
    fun forTool(
        tool: String?,
        baseWidthPx: Float,
        pressure: Float,
        tiltRadians: Float,
    ): SegmentStyle = when (tool) {
        StrokeRenderer.TOOL_PEN -> pen(baseWidthPx, pressure, tiltRadians)
        StrokeRenderer.TOOL_PENCIL -> pencil(baseWidthPx, pressure, tiltRadians)
        StrokeRenderer.TOOL_HIGHLIGHTER -> highlighter(baseWidthPx, pressure, tiltRadians)
        else -> pen(baseWidthPx, pressure, tiltRadians)
    }

    /** Highlighter base alpha. 0.35 ≈ 89/255 — slightly heavier than the
     *  pre-5.1 paint's 76 (~0.30) so a single highlight reads clearly on
     *  white paper without obscuring the ink underneath. */
    const val HIGHLIGHTER_ALPHA: Float = 0.35f

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
