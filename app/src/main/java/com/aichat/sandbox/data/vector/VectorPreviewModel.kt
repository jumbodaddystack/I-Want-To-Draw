package com.aichat.sandbox.data.vector

import kotlin.math.max
import kotlin.math.min

/**
 * Phase 8 — a pure, render-agnostic description of a [VectorDocument] suitable
 * for drawing a safe preview.
 *
 * This model is deliberately free of Android/Compose graphics types so it stays
 * JVM-testable and bounded by the same parser/validator that powers metrics,
 * optimization, edits, and AI flows. The UI ([VectorPreviewCanvas]) is the only
 * layer that turns these typed commands into actual draw calls, so untrusted XML
 * is never inflated as a real Android `VectorDrawable`.
 *
 * @property viewport The coordinate space the [paths] are expressed in.
 * @property paths The renderable paths (only those with parsed geometry).
 * @property warnings Preview-specific notes (skipped paths, unsupported features).
 */
data class VectorPreviewModel(
    val viewport: VectorViewport,
    val paths: List<VectorPreviewPath>,
    val warnings: List<VectorWarning> = emptyList(),
)

/**
 * One renderable path in a [VectorPreviewModel]. [commands] is always non-empty
 * (unparsed paths are dropped by the builder and surfaced as a warning).
 */
data class VectorPreviewPath(
    val id: String,
    val name: String?,
    val commands: List<PathCommand>,
    val style: VectorPreviewStyle,
    val bounds: VectorBounds?,
)

/**
 * The subset of [VectorStyle] the preview renderer understands. Mirrors the
 * stroke/fill attributes. Phase 5 adds [fill]: a non-null gradient/solid fill the
 * canvas maps to a Compose `Brush`, overriding the scalar [fillColor].
 */
data class VectorPreviewStyle(
    val fillColor: String?,
    val fillAlpha: Float?,
    val strokeColor: String?,
    val strokeAlpha: Float?,
    val strokeWidth: Float?,
    val strokeLineCap: String?,
    val strokeLineJoin: String?,
    val fillType: String?,
    val fill: VectorFill? = null,
)

/**
 * The union of every path's estimated bounds, or null when nothing was parsed.
 * Used by the visual-diff overlay to draw a bounding box per version.
 */
fun VectorPreviewModel.contentBounds(): VectorBounds? {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var saw = false
    for (p in paths) {
        val b = p.bounds ?: continue
        minX = min(minX, b.minX); minY = min(minY, b.minY)
        maxX = max(maxX, b.maxX); maxY = max(maxY, b.maxY)
        saw = true
    }
    return if (saw) VectorBounds(minX, minY, maxX, maxY) else null
}
