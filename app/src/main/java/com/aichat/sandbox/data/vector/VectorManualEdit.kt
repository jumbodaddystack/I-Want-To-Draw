package com.aichat.sandbox.data.vector

/**
 * Phase 7 — a deterministic, user-driven edit over an existing vector version.
 *
 * Distinct from the AI [VectorEditPlan]: a [VectorManualEdit] is produced by app
 * UI (path selection + controls), not by a model, and it only ever references
 * existing path ids. [VectorManualEditApplier] validates every id/color/style and
 * produces a candidate document — the source is never mutated in place.
 */
sealed interface VectorManualEdit {

    /** Removes the named paths outright. */
    data class DeletePaths(
        val pathIds: List<String>,
    ) : VectorManualEdit

    /** Replaces stroke and/or fill color on the named paths. */
    data class RecolorPaths(
        val pathIds: List<String>,
        val strokeColor: String? = null,
        val fillColor: String? = null,
    ) : VectorManualEdit

    /** Adjusts stroke width / line cap / line join on the named paths. */
    data class RestylePaths(
        val pathIds: List<String>,
        val strokeWidth: Float? = null,
        val lineCap: String? = null,
        val lineJoin: String? = null,
    ) : VectorManualEdit

    /** Simplifies the geometry of the named paths via RDP at [tolerance]. */
    data class SimplifyPaths(
        val pathIds: List<String>,
        val tolerance: Float,
        val simplifyFills: Boolean = false,
    ) : VectorManualEdit
}

/**
 * Outcome of applying a list of [VectorManualEdit]s. The candidate [document]/
 * [xml] are derived from the original; the original is never mutated. [warnings]
 * surfaces unknown ids, invalid colors/styles, and no-op edits.
 */
data class VectorManualEditResult(
    val document: VectorDocument,
    val xml: String,
    val metrics: VectorMetrics,
    val warnings: List<VectorWarning>,
    val editedPathCount: Int,
    val deletedPathCount: Int,
    val summary: String,
)
