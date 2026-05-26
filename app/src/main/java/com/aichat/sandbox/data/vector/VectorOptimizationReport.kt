package com.aichat.sandbox.data.vector

/**
 * Before/after summary of a [VectorDrawableOptimizer] run.
 *
 * [before] and [after] are full [VectorMetrics] snapshots so callers can show
 * byte/command/path deltas. The counts describe what the optimizer did:
 * [removedPathCount] tiny paths dropped, [simplifiedPathCount] paths whose
 * geometry was reduced, and [unsupportedPathCount] paths left untouched because
 * their command data could not be parsed. [warnings] aggregates parser,
 * validator, and optimizer-specific warnings.
 */
data class VectorOptimizationReport(
    val before: VectorMetrics,
    val after: VectorMetrics,
    val removedPathCount: Int,
    val simplifiedPathCount: Int,
    val unsupportedPathCount: Int,
    val warnings: List<VectorWarning>,
)

/**
 * Output of [VectorDrawableOptimizer.optimize]: the optimized [document], its
 * serialized [xml], and the [report] describing the changes.
 */
data class VectorOptimizationResult(
    val document: VectorDocument,
    val xml: String,
    val report: VectorOptimizationReport,
)
