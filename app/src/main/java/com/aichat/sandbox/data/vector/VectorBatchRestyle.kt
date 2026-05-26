package com.aichat.sandbox.data.vector

/**
 * Phase 7 — a higher-level "restyle a group of paths at once" instruction.
 *
 * The UI uses it for common actions ("make all green strokes 1.2", "round all
 * caps/joins", "recolor all red fills"). It resolves [target] to a set of path
 * ids against the current document, then lowers to [VectorManualEdit]s so the
 * same validation/clamping/simplification guarantees apply.
 */
data class VectorBatchRestyle(
    val target: Target,
    val strokeColor: String? = null,
    val fillColor: String? = null,
    val strokeWidth: Float? = null,
    val lineCap: String? = null,
    val lineJoin: String? = null,
) {
    sealed interface Target {
        /** Every path with a (non-transparent) stroke. */
        data object AllStroked : Target

        /** Every path with a (non-transparent) fill. */
        data object AllFilled : Target

        /** Paths whose fill or stroke matches one of [colors] (case-insensitive). */
        data class ByColor(val colors: List<String>) : Target

        /** Exactly the named path ids. */
        data class ByPathIds(val pathIds: List<String>) : Target
    }
}

/**
 * Resolves a [VectorBatchRestyle] to concrete path ids and delegates to
 * [VectorManualEditApplier]. The result is a normal [VectorManualEditResult] so
 * the workspace can persist it as a `MANUAL_EDIT` child version exactly like a
 * per-path edit.
 */
object VectorBatchRestyleApplier {

    fun apply(
        document: VectorDocument,
        originalXml: String,
        restyle: VectorBatchRestyle,
    ): VectorManualEditResult {
        val ids = resolveTargetIds(document, restyle.target)
        val edits = ArrayList<VectorManualEdit>(2)

        if (restyle.strokeColor != null || restyle.fillColor != null) {
            edits += VectorManualEdit.RecolorPaths(
                pathIds = ids,
                strokeColor = restyle.strokeColor,
                fillColor = restyle.fillColor,
            )
        }
        if (restyle.strokeWidth != null || restyle.lineCap != null || restyle.lineJoin != null) {
            edits += VectorManualEdit.RestylePaths(
                pathIds = ids,
                strokeWidth = restyle.strokeWidth,
                lineCap = restyle.lineCap,
                lineJoin = restyle.lineJoin,
            )
        }

        return VectorManualEditApplier.apply(document, originalXml, edits)
    }

    private fun resolveTargetIds(
        document: VectorDocument,
        target: VectorBatchRestyle.Target,
    ): List<String> {
        val paths = document.allPaths()
        return when (target) {
            is VectorBatchRestyle.Target.AllStroked ->
                paths.filter { isStroked(it) }.map { it.id }
            is VectorBatchRestyle.Target.AllFilled ->
                paths.filter { isFilled(it) }.map { it.id }
            is VectorBatchRestyle.Target.ByColor -> {
                val wanted = target.colors.map { it.lowercase() }.toSet()
                paths.filter { matchesColor(it, wanted) }.map { it.id }
            }
            is VectorBatchRestyle.Target.ByPathIds -> target.pathIds
        }
    }

    private fun matchesColor(path: VectorPath, wanted: Set<String>): Boolean {
        val fill = path.style.fillColor?.lowercase()
        val stroke = path.style.strokeColor?.lowercase()
        return (fill != null && fill in wanted) || (stroke != null && stroke in wanted)
    }

    private fun isStroked(path: VectorPath): Boolean {
        val s = path.style.strokeColor ?: return false
        return !isTransparent(s)
    }

    private fun isFilled(path: VectorPath): Boolean {
        val f = path.style.fillColor ?: return false
        return !isTransparent(f)
    }

    private fun isTransparent(color: String): Boolean {
        val hex = color.trim().removePrefix("#")
        return when (hex.length) {
            8 -> hex.substring(0, 2).equals("00", ignoreCase = true)
            4 -> hex[0] == '0'
            else -> false
        }
    }
}
