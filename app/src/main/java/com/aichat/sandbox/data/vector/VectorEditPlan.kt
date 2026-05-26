package com.aichat.sandbox.data.vector

/**
 * Phase 4 — typed, validated representation of an AI-proposed tune-up plan.
 *
 * The model proposes; the app validates and applies. A [VectorEditPlan] is the
 * already-validated output of [VectorEditPlanParser]: every operation it
 * contains targets known path IDs/colors with clamped, safe values. Operations
 * the parser could not accept are preserved in [rejected] (with a reason) rather
 * than thrown away or thrown — the UI surfaces them as warnings.
 *
 * The plan never carries raw XML or geometry the model invented; it only
 * references existing paths. [VectorEditPlanApplier] turns it into a candidate
 * document deterministically.
 */
data class VectorEditPlan(
    val schema: Int,
    val mode: Mode,
    val summary: String,
    val operations: List<VectorEditOperation>,
    val rejected: List<RejectedOperation> = emptyList(),
) {
    enum class Mode {
        FAITHFUL_CLEANUP,
        TUNE_UP,
    }

    data class RejectedOperation(
        val raw: String,
        val reason: String,
    )

    /** True when there is nothing for the applier to do (a valid outcome). */
    val isEmpty: Boolean get() = operations.isEmpty()

    companion object {
        const val SCHEMA: Int = 1
        val EMPTY = VectorEditPlan(
            schema = SCHEMA,
            mode = Mode.TUNE_UP,
            summary = "",
            operations = emptyList(),
        )
    }
}

/**
 * One validated edit the applier knows how to perform. Each operation resolves
 * the paths it affects via its [target]; the applier never touches a path that
 * a valid target did not match.
 */
sealed interface VectorEditOperation {
    val target: VectorPathTarget

    data class SimplifyPaths(
        override val target: VectorPathTarget,
        val tolerance: Float,
        val minPathLength: Float?,
        val simplifyFills: Boolean,
    ) : VectorEditOperation

    data class RemovePaths(
        override val target: VectorPathTarget,
    ) : VectorEditOperation

    data class RestylePaths(
        override val target: VectorPathTarget,
        val strokeWidth: Float?,
        val lineCap: String?,
        val lineJoin: String?,
    ) : VectorEditOperation

    data class RecolorPaths(
        override val target: VectorPathTarget,
        val strokeColor: String?,
        val fillColor: String?,
    ) : VectorEditOperation
}

/**
 * How an operation selects the paths it applies to. A path matches when it is
 * named in [pathIds], or carries one of [colors] as fill/stroke, or when [all]
 * is set — optionally narrowed to [strokedOnly]/[filledOnly]. A target that
 * matches nothing makes its operation a no-op (reported as a warning).
 */
data class VectorPathTarget(
    val pathIds: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
    val all: Boolean = false,
    val strokedOnly: Boolean = false,
    val filledOnly: Boolean = false,
) {
    /** True when the target can never match anything (no selector at all). */
    val isUnconstrained: Boolean
        get() = pathIds.isEmpty() && colors.isEmpty() && !all
}
