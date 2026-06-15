package com.aichat.sandbox.data.ink

import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase **I7 — select-similar + snapping (N2, idea #8)**: the local, pure-JVM
 * similarity metric the magic-wand "select similar" builds on (capability area B
 * in `docs/ANDROIDX_INK_MIGRATION_PLAN.md`).
 *
 * ## Local-first, offline, deterministic
 * Per the N2 pipeline note ("geometry runs locally — fast, offline — and the AI
 * only optionally ranks which of these belong together"), the *candidate
 * proposal* is computed here with no model call and no native work: a stroke is
 * reduced to a small **translation- and scale-invariant** shape descriptor plus
 * its simple style features (tool / width / colour), and two strokes are scored
 * in `[0, 1]`. Scale/translation invariance is what makes "select all the tick
 * marks / leaves / arrowheads" work regardless of where each one sits or how big
 * it is.
 *
 * ## Why descriptors (and not per-pair mesh overlap) in production
 * The I6 [StrokeMeshCache] exposes ink's robust `PartitionedMesh` overlap, but a
 * true shape-overlap (IoU) needs the two meshes *aligned* into a common frame and
 * an O(samples) point/coverage sweep **per pair** — too costly to run against
 * every stroke on a tap. These descriptors are O(samples) **once per stroke** and
 * then O(1) per comparison, so select-similar stays interactive on a large note.
 * The descriptor ordering is pinned against ink's real mesh-overlap metric in
 * `data.ink.parity.SelectSimilarSnapParityTest`, so the cheap proxy is held
 * honest against the engine it stands in for.
 *
 * `StrokeCodec` stays canonical — these features are derived from the canonical
 * payload and never persisted (Adoption principle 2).
 */
object StrokeSimilarity {

    private const val EPS = 1e-4f
    private const val MAX_RGB_DISTANCE = 441.6729f // sqrt(255^2 * 3)

    /** Two full revolutions; clamps the unbounded turning sum into `[0, 1]`. */
    private const val TURN_NORMALIZER = (4.0 * Math.PI).toFloat()

    /**
     * Per-component weights for [similarity]. Tool and shape dominate (they're
     * the strongest "is this the same kind of mark" signals); colour and width
     * refine. Weights need not sum to 1 — [similarity] normalises by their total.
     */
    data class Weights(
        val tool: Float = 0.28f,
        val color: Float = 0.16f,
        val width: Float = 0.10f,
        val aspect: Float = 0.16f,
        val straightness: Float = 0.14f,
        val turning: Float = 0.16f,
    ) {
        val total: Float get() = tool + color + width + aspect + straightness + turning
    }

    /**
     * The reduced, comparison-ready signature of one stroke. Shape fields are all
     * scale/translation invariant and bounded to `[0, 1]`.
     *
     * @property aspect bbox `min(w,h)/max(w,h)` — proportion (1 = square extent).
     * @property straightness endpoint distance / path length (1 = straight,
     *   ~0 = tightly looping / closed).
     * @property turning total absolute turning angle, normalised by two full
     *   revolutions and clamped — a "how much does it wiggle / curl" signal.
     * @property sizeDiag bbox diagonal in world units (not a shape feature; kept
     *   so callers can optionally gate on absolute size).
     */
    data class Features(
        val tool: String,
        val colorArgb: Int,
        val width: Float,
        val aspect: Float,
        val straightness: Float,
        val turning: Float,
        val sizeDiag: Float,
    )

    /**
     * Reduce a canonical [StrokeCodec] payload + its style to a [Features], or
     * `null` when the payload carries no usable samples.
     */
    fun featuresOf(payload: ByteArray, tool: String, colorArgb: Int, width: Float): Features? {
        val samples = StrokeCodec.decode(payload)
        val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
        if (count < 1) return null
        val b = HitTest.boundsOf(samples, count) ?: return null
        return featuresOf(samples, count, b, tool, colorArgb, width)
    }

    /** Same as [featuresOf] but on already-decoded samples (avoids re-decoding). */
    fun featuresOf(
        samples: FloatArray,
        count: Int,
        bounds: FloatArray,
        tool: String,
        colorArgb: Int,
        width: Float,
    ): Features {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        val diag = hypot(w, h)
        val aspect = if (max(w, h) <= EPS) 1f else min(w, h) / max(w, h)

        var pathLength = 0f
        var totalTurn = 0f
        var prevDx = 0f
        var prevDy = 0f
        var havePrevDir = false
        for (i in 1 until count) {
            val a = (i - 1) * s
            val c = i * s
            val dx = samples[c] - samples[a]
            val dy = samples[c + 1] - samples[a + 1]
            val segLen = hypot(dx, dy)
            if (segLen <= EPS) continue
            pathLength += segLen
            if (havePrevDir) {
                // Signed turn between consecutive segment directions; take |.| so
                // an S-curve and a C-curve of the same magnitude read alike.
                val cross = prevDx * dy - prevDy * dx
                val dot = prevDx * dx + prevDy * dy
                totalTurn += abs(atan2(cross, dot))
            }
            prevDx = dx
            prevDy = dy
            havePrevDir = true
        }

        val straightness = if (pathLength <= EPS) {
            1f
        } else {
            val ex = samples[(count - 1) * s] - samples[0]
            val ey = samples[(count - 1) * s + 1] - samples[1]
            (hypot(ex, ey) / pathLength).coerceIn(0f, 1f)
        }
        val turning = (totalTurn / TURN_NORMALIZER).coerceIn(0f, 1f)

        return Features(
            tool = tool,
            colorArgb = colorArgb,
            width = width,
            aspect = aspect,
            straightness = straightness,
            turning = turning,
            sizeDiag = diag,
        )
    }

    /**
     * Similarity of two strokes in `[0, 1]` (1 = identical style + shape). A
     * weighted blend of: tool match, colour proximity, width ratio, and the three
     * scale-invariant shape descriptors. Symmetric in [a]/[b].
     */
    fun similarity(a: Features, b: Features, weights: Weights = Weights()): Float {
        val toolSim = if (a.tool == b.tool) 1f else 0f
        val colorSim = 1f - rgbDistance(a.colorArgb, b.colorArgb) / MAX_RGB_DISTANCE
        val widthSim = ratio(a.width, b.width)
        val aspectSim = 1f - abs(a.aspect - b.aspect)
        val straightSim = 1f - abs(a.straightness - b.straightness)
        val turnSim = 1f - abs(a.turning - b.turning)

        val w = weights
        val sum = w.tool * toolSim +
            w.color * colorSim +
            w.width * widthSim +
            w.aspect * aspectSim +
            w.straightness * straightSim +
            w.turning * turnSim
        val total = w.total
        return if (total <= EPS) 0f else (sum / total).coerceIn(0f, 1f)
    }

    /** `min/max` ratio in `[0, 1]`; 1 when equal, robust to zero. */
    private fun ratio(x: Float, y: Float): Float {
        val ax = abs(x)
        val ay = abs(y)
        val hi = max(ax, ay)
        if (hi <= EPS) return 1f
        return min(ax, ay) / hi
    }

    private fun rgbDistance(c1: Int, c2: Int): Float {
        val dr = ((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF)
        val dg = ((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF)
        val db = (c1 and 0xFF) - (c2 and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
}
