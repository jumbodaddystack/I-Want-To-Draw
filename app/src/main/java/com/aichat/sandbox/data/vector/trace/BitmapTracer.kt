package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * Phase 5 (sub-feature 5) — turns a raster bitmap into editable cubic vector
 * paths feeding the unified editable model.
 *
 * The bitmap is passed as a width/height + ARGB `IntArray` (row-major), which
 * keeps the contract Android-free and JVM-testable. Two interchangeable backends
 * sit behind this interface: the deterministic [LocalBitmapTracer] (always
 * available) and a semantic AI backend that falls back to the local tracer.
 */
interface BitmapTracer {
    suspend fun trace(pixels: IntArray, width: Int, height: Int, options: TraceOptions): TraceResult
}

/** How to interpret the mask: trace region [OUTLINE]s or thin to a [CENTERLINE]. */
enum class TraceMode { OUTLINE, CENTERLINE }

/**
 * @property mode outline vs. centerline tracing.
 * @property threshold luminance cutoff (0..255); pixels darker than this (and
 *   sufficiently opaque) are foreground.
 * @property simplifyTolerance RDP tolerance (viewport units) applied before curve fitting.
 * @property maxError max cubic-fit error (viewport units).
 */
data class TraceOptions(
    val mode: TraceMode = TraceMode.OUTLINE,
    val threshold: Int = 128,
    val simplifyTolerance: Float = 1.5f,
    val maxError: Float = 2f,
)

data class TraceResult(
    val paths: List<VectorPath>,
    val viewport: VectorViewport,
    val warnings: List<VectorWarning> = emptyList(),
)
