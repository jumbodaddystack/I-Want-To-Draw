package com.aichat.sandbox.data.vector

/**
 * Tuning knobs for [VectorDrawableOptimizer].
 *
 * Defaults are deliberately conservative: only stroked paths are touched,
 * filled shapes are left alone, and the tolerance is small enough that
 * simplification removes obvious point noise without visibly altering the
 * artwork. Callers opt into more aggressive behavior explicitly.
 *
 * @property tolerance Ramer–Douglas–Peucker distance threshold in viewport
 *   units. Larger values drop more interior points. `<= 0` disables
 *   simplification entirely.
 * @property minPathLength Sampled length (viewport units) below which a path is
 *   considered tiny and may be removed when [removeTinyPaths] is set.
 * @property decimalPlaces Coordinate precision used when rebuilding path data.
 * @property removeTinyPaths Drop stroke-only paths whose sampled length is below
 *   [minPathLength].
 * @property simplifyStrokes Simplify stroked (stroke-only) paths.
 * @property simplifyFills Simplify filled paths (including fill+stroke paths).
 *   Off by default because fills are more sensitive to point removal.
 * @property preserveCurves Skip simplification for paths whose source geometry
 *   contained curves/arcs, so they are not flattened into polylines.
 */
data class VectorOptimizeOptions(
    val tolerance: Float = 0.25f,
    val minPathLength: Float = 0.01f,
    val decimalPlaces: Int = 2,
    val removeTinyPaths: Boolean = true,
    val simplifyStrokes: Boolean = true,
    val simplifyFills: Boolean = false,
    val preserveCurves: Boolean = false,
)
