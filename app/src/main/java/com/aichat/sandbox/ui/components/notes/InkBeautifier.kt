package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import kotlin.math.hypot

/**
 * Sub-phase 14.1 — opt-in ink beautification on pen lift.
 *
 * Runs the same Chaikin pass the selection-menu "Clean up" applies, preceded
 * by an RDP de-noise step so micro-jitter doesn't survive the corner cutting.
 * Pure and stride-agnostic: samples arrive as a packed float array whose
 * first two lanes are x/y and whose remaining lanes (pressure, tilt, and the
 * v2 codec's per-sample timestamp) interpolate alongside them, so both
 * [StrokeCodec] layouts beautify identically.
 *
 * Unlike "Clean up" this runs *before* the stroke is committed — the
 * beautified stroke is the item, so one undo removes it entirely and the
 * surface's instant-feedback copy, the persisted payload and the
 * hold-recognizer's input all agree byte-for-byte.
 */
object InkBeautifier {

    /** Chaikin iterations — matches the local Clean-up pass. */
    private const val SMOOTH_ITERATIONS = 2

    /** RDP tolerance as a fraction of the stroke's bbox diagonal. */
    private const val DENOISE_TOLERANCE_FRACTION = 0.005f

    /** Strokes shorter than this keep their raw samples (dots, ticks). */
    private const val MIN_SAMPLES = 6

    /** Output cap — mirrors [EditPreviewController]'s smooth cap. */
    private const val MAX_SAMPLES = 1024

    /**
     * Beautify [samples] (packed, [stride] floats per sample, x/y first).
     * Returns the input array unchanged when the stroke is too short to
     * meaningfully smooth.
     */
    fun beautify(samples: FloatArray, stride: Int): FloatArray {
        require(stride >= 2) { "InkBeautifier: stride must include x and y" }
        val count = samples.size / stride
        if (count < MIN_SAMPLES) return samples
        val denoised = denoise(samples, stride, count)
        var out = denoised
        repeat(SMOOTH_ITERATIONS) {
            if (out.size / stride >= MAX_SAMPLES) return@repeat
            out = chaikin(out, stride)
        }
        return out
    }

    /**
     * RDP on the x/y centerline, keeping every lane of the surviving
     * samples — the same shape as [EditPreviewController.simplifyStroke],
     * with tolerance scaled to the stroke's own bbox diagonal so zoom level
     * and stroke size don't change the outcome.
     */
    private fun denoise(samples: FloatArray, stride: Int, count: Int): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = samples[i * stride]
            val y = samples[i * stride + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val tolerance = hypot(maxX - minX, maxY - minY) * DENOISE_TOLERANCE_FRACTION
        if (tolerance <= 0f) return samples
        val centerline = ArrayList<VectorPoint>(count)
        for (i in 0 until count) {
            centerline += VectorPoint(samples[i * stride], samples[i * stride + 1])
        }
        val keep = PolylineSimplify.keepMask(centerline, tolerance)
        val kept = (0 until count).count { keep[it] }
        if (kept == count) return samples
        val out = FloatArray(kept * stride)
        var w = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            System.arraycopy(samples, i * stride, out, w * stride, stride)
            w++
        }
        return out
    }

    /**
     * One Chaikin corner-cutting pass — numerically identical to the
     * Clean-up implementation: interior segments split 0.75/0.25, original
     * endpoints preserved, every lane interpolated uniformly (which keeps
     * monotone timestamp lanes monotone).
     */
    private fun chaikin(samples: FloatArray, stride: Int): FloatArray {
        val count = samples.size / stride
        if (count < 2) return samples
        val out = FloatArray((2 * (count - 1)) * stride)
        var write = 0
        for (i in 0 until count - 1) {
            val a = i * stride
            val b = (i + 1) * stride
            for (k in 0 until stride) {
                out[write + k] = samples[a + k] * 0.75f + samples[b + k] * 0.25f
                out[write + stride + k] = samples[a + k] * 0.25f + samples[b + k] * 0.75f
            }
            write += 2 * stride
        }
        for (k in 0 until stride) {
            out[k] = samples[k]
            out[out.size - stride + k] = samples[samples.size - stride + k]
        }
        return out
    }
}
