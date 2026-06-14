package com.aichat.sandbox.data.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Phase I0 — the bidirectional **sample ↔ ink** conversion seam.
 *
 * This is the one new primitive every later ink phase composes on top of
 * (see `docs/ANDROIDX_INK_MIGRATION_PLAN.md`). It converts between the
 * project's canonical [StrokeCodec] float payloads and AndroidX Ink's
 * [StrokeInputBatch] / [Stroke] types, and maps a user-facing [BrushPreset]
 * onto an ink [Brush] using **stable 1.0.0 APIs only**.
 *
 * ## Hard invariants this seam preserves
 *  - [StrokeCodec] stays canonical. ink is a live-authoring / rendering /
 *    derived-geometry layer; an ink-authored stroke is converted back to a
 *    [StrokeCodec] payload the instant the pen lifts, so a committed stroke is
 *    byte-indistinguishable from one drawn today and the AI edit-ops pipeline
 *    never sees ink. Nothing here ever becomes on-disk truth.
 *
 * ## Two-clock timestamp reconciliation (part of this seam, not deferred)
 * Our v2 lane stores time as **milliseconds relative to the audio recording's
 * `recordingStartedAt`** (recording-relative). ink's
 * [StrokeInput.elapsedTimeMillis] is **stroke-relative** (since the start of
 * the stroke). These are two different clocks, reconciled explicitly:
 *  - [toInputBatch] subtracts the stroke's first-sample time to produce
 *    stroke-relative `elapsedTimeMillis` for ink smoothing/playback, and
 *    returns the stroke origin so it can be restored.
 *  - [fromInputBatch] / [fromStroke] re-add that recording-relative origin so
 *    committed payloads keep the v2 contract (audio sync intact).
 *  - **v1 strokes** (no timestamps) feed ink with a synthesized uniform
 *    cadence ([SYNTHETIC_CADENCE_MS]) and round-trip back to v1 (timestamps
 *    dropped), since they were never recorded with audio.
 *
 * ## What is intentionally *not* mapped yet
 * The [BrushPreset] → [Brush] adapter here covers the stable, renderable brush
 * identity: brush family (from tool), colour (with opacity folded into alpha),
 * and width. Richer preset semantics — taper, jitter, pressure-curve remap, and
 * procedural textures — require custom `BrushTip` / `BrushBehavior` construction
 * (and, for AI-authored brushes, the 1.1-alpha programmable API). Those are
 * deferred to phase I4 by design, so the alpha API never blocks this stable
 * seam.
 */
object InkInterop {

    /** Upper bound ink enforces on [StrokeInput.tiltRadians] (perpendicular). */
    private val MAX_TILT_RADIANS = (PI / 2.0).toFloat()

    /**
     * Uniform per-sample spacing (ms) synthesized when feeding a timestamp-less
     * v1 stroke to ink. ink wants monotonic input times for its smoothing /
     * prediction; v1 strokes carry none, so we fabricate an even ~125 Hz
     * cadence. This time is never persisted — v1 strokes round-trip back to v1.
     */
    const val SYNTHETIC_CADENCE_MS: Long = 8L

    // Brush sizing guards — ink requires a finite size > 0 and epsilon > 0.
    private const val MIN_BRUSH_SIZE = 0.01f
    private const val MIN_EPSILON = 0.001f
    private const val MAX_EPSILON = 0.1f

    /**
     * Result of [toInputBatch]: the ink batch plus the recording-relative
     * origin needed to reconstruct v2 timestamps on the way back.
     *
     * @property recordingOriginMillis recording-relative ms of the stroke's
     *   first sample (the offset to re-add in [fromInputBatch]), or `null` for
     *   a v1 stroke that should round-trip back to v1 with no timestamps.
     */
    data class BatchConversion(
        val batch: ImmutableStrokeInputBatch,
        val recordingOriginMillis: Long?,
    )

    /**
     * Decode a [StrokeCodec] payload (v1 or v2) into an ink
     * [ImmutableStrokeInputBatch] with **stroke-relative** timestamps.
     *
     * Pressure is clamped to `[0, 1]` and tilt to `[0, π/2]` to satisfy ink's
     * input invariants; captured S-Pen data already lies in range, so the
     * round-trip is exact for real strokes.
     */
    fun toInputBatch(
        payload: ByteArray,
        toolType: InputToolType = InputToolType.STYLUS,
    ): BatchConversion {
        val v2 = StrokeCodec.decodeWithT(payload) // [x,y,pressure,tilt,t]*
        val sampleCount = v2.size / StrokeCodec.FLOATS_PER_SAMPLE_V2
        val batch = MutableStrokeInputBatch()
        val isV2 = StrokeCodec.isV2(payload)

        if (sampleCount == 0) {
            return BatchConversion(batch.toImmutable(), if (isV2) 0L else null)
        }

        // v2: stroke-relative time = sample_t - first_sample_t; remember the
        // first-sample time as the recording origin to restore later.
        // v1: no real time, so synthesize a uniform cadence and signal "no
        // origin" (null) so the stroke round-trips back to v1.
        val firstT = v2[StrokeCodec.FLOATS_PER_SAMPLE]  // index 4: t of sample 0
        val recordingOrigin: Long? = if (isV2) firstT.roundToLong() else null

        var base = 0
        var i = 0
        while (i < sampleCount) {
            val x = v2[base]
            val y = v2[base + 1]
            val pressure = v2[base + 2].coerceIn(0f, 1f)
            val tilt = v2[base + 3].coerceIn(0f, MAX_TILT_RADIANS)
            val elapsed: Long = if (isV2) {
                (v2[base + 4] - firstT).roundToLong().coerceAtLeast(0L)
            } else {
                i * SYNTHETIC_CADENCE_MS
            }
            batch.add(
                StrokeInput.create(
                    x,
                    y,
                    elapsed,
                    toolType,
                    StrokeInput.NO_STROKE_UNIT_LENGTH,
                    pressure,
                    tilt,
                    StrokeInput.NO_ORIENTATION,
                ),
            )
            base += StrokeCodec.FLOATS_PER_SAMPLE_V2
            i++
        }
        return BatchConversion(batch.toImmutable(), recordingOrigin)
    }

    /**
     * Decode a [StrokeCodec] payload into a renderable ink [Stroke] using the
     * given [brush]. The stroke's mesh geometry is computed by ink natively.
     *
     * The recording origin is *not* carried on the returned [Stroke] (ink has
     * no slot for it); callers that need to re-commit the stroke should keep
     * the [BatchConversion.recordingOriginMillis] from [toInputBatch] and pass
     * it to [fromStroke].
     */
    fun toStroke(
        payload: ByteArray,
        brush: Brush,
        toolType: InputToolType = InputToolType.STYLUS,
    ): Stroke = Stroke(brush, toInputBatch(payload, toolType).batch)

    /**
     * Encode an ink [StrokeInputBatch] back into a [StrokeCodec] payload.
     *
     * @param recordingOriginMillis when non-null, re-add this recording-relative
     *   origin to each input's stroke-relative time and emit a **v2** payload
     *   (preserving the audio-sync contract). When null, emit a **v1** payload
     *   with no timestamps.
     */
    fun fromInputBatch(batch: StrokeInputBatch, recordingOriginMillis: Long?): ByteArray {
        val n = batch.size
        return if (recordingOriginMillis == null) {
            val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE)
            var dst = 0
            var i = 0
            while (i < n) {
                val input = batch.get(i)
                out[dst] = input.x
                out[dst + 1] = input.y
                out[dst + 2] = if (input.hasPressure) input.pressure else 0f
                out[dst + 3] = if (input.hasTilt) input.tiltRadians else 0f
                dst += StrokeCodec.FLOATS_PER_SAMPLE
                i++
            }
            StrokeCodec.encode(out)
        } else {
            val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE_V2)
            var dst = 0
            var i = 0
            while (i < n) {
                val input = batch.get(i)
                out[dst] = input.x
                out[dst + 1] = input.y
                out[dst + 2] = if (input.hasPressure) input.pressure else 0f
                out[dst + 3] = if (input.hasTilt) input.tiltRadians else 0f
                out[dst + 4] = (recordingOriginMillis + input.elapsedTimeMillis).toFloat()
                dst += StrokeCodec.FLOATS_PER_SAMPLE_V2
                i++
            }
            StrokeCodec.encodeV2(out)
        }
    }

    /**
     * Encode an ink [Stroke]'s inputs back into a [StrokeCodec] payload. The
     * brush is intentionally dropped — brush identity lives in the project's
     * `NoteItem` (tool / colour / width) and [BrushPreset], not in the codec.
     *
     * @param recordingOriginMillis see [fromInputBatch].
     */
    fun fromStroke(stroke: Stroke, recordingOriginMillis: Long?): ByteArray =
        fromInputBatch(stroke.inputs, recordingOriginMillis)

    /**
     * Map a user-facing [BrushPreset] onto an ink [Brush] using stable 1.0.0
     * APIs: a [StockBrushes] family chosen by tool, the preset colour with
     * [BrushPreset.opacity] folded into the alpha channel, and
     * [BrushPreset.baseWidthPx] as the brush size. (Taper / jitter /
     * pressure-curve / texture are deferred to phase I4 — see the class KDoc.)
     */
    fun toBrush(preset: BrushPreset): Brush =
        brushForTool(
            toolId = preset.tool,
            colorArgb = applyOpacityToArgb(preset.colorArgb, preset.opacity),
            baseWidthPx = preset.baseWidthPx,
        )

    /**
     * Build an ink [Brush] straight from a tool id, an already-resolved ARGB
     * colour (opacity folded in), and a world-space width — the shape the live
     * authoring path ([com.aichat.sandbox.ui.components.notes.DrawingSurface])
     * carries while a stroke is in flight, where there isn't a [BrushPreset] to
     * hand. [toBrush] delegates here after folding preset opacity into the
     * colour, so both routes share one stable-API brush identity (family + size
     * + epsilon). Width is a world value because the authoring path stores the
     * stroke in world coordinates; the same value lands in `NoteItem.baseWidthPx`.
     */
    fun brushForTool(toolId: String, colorArgb: Int, baseWidthPx: Float): Brush {
        val family = brushFamilyForTool(toolId)
        val size = baseWidthPx.coerceAtLeast(MIN_BRUSH_SIZE)
        val epsilon = (size / 100f).coerceIn(MIN_EPSILON, MAX_EPSILON)
        return Brush.createWithColorIntArgb(family, colorArgb, size, epsilon)
    }

    /**
     * Choose a stable [StockBrushes] family for a tool id. `pencil` maps to the
     * stable round [StockBrushes.marker] family rather than the experimental
     * textured pencil; a richer mapping arrives with phase I4.
     */
    fun brushFamilyForTool(tool: String): BrushFamily = when (tool.lowercase()) {
        "pen" -> StockBrushes.pressurePen()
        "highlighter" -> StockBrushes.highlighter()
        "marker" -> StockBrushes.marker()
        "pencil" -> StockBrushes.marker()
        else -> StockBrushes.marker()
    }

    /**
     * Fold a preset's `[0, 1]` opacity into the alpha channel of an ARGB colour,
     * matching the project's "opacity multiplied into colour alpha at paint
     * time" semantics.
     */
    fun applyOpacityToArgb(colorArgb: Int, opacity: Float): Int {
        val alpha = (colorArgb ushr 24) and 0xFF
        val scaled = (alpha * opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (scaled shl 24) or (colorArgb and 0x00FFFFFF)
    }
}
