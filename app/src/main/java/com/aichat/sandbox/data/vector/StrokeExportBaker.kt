package com.aichat.sandbox.data.vector

/**
 * Phase 5 (sub-feature 1) — pre-export geometry baking for stroke styling.
 *
 * The writers stay thin: a [VectorDocument] is run through this baker first so
 * features without a native attribute in the target format become plain
 * geometry. All transforms only touch paths that opt in (a non-null
 * [VectorStyle.variableWidth] / [VectorStyle.strokeDashArray]), so every existing
 * document is returned structurally unchanged and current writer tests stay
 * byte-identical. Pure — no Android imports.
 *
 * - [bakeVariableWidth] applies to **both** writers (neither format has a
 *   width-along-path attribute): a profiled stroke becomes a filled outline.
 * - [bakeDashes] applies to the **Android** writer only (SVG emits
 *   `stroke-dasharray` natively): a dashed stroke is chopped into "on" runs.
 */
object StrokeExportBaker {

    /** Replace every variable-width stroked path with its filled outline. */
    fun bakeVariableWidth(document: VectorDocument): VectorDocument =
        mapPaths(document) { path ->
            val profile = path.style.variableWidth ?: return@mapPaths path
            val commands = path.commands?.takeIf { it.isNotEmpty() } ?: return@mapPaths path
            val baseWidth = path.style.strokeWidth ?: 1f
            val outline = VariableWidthOutliner.outline(commands, profile, baseWidth)
            if (outline.isEmpty()) return@mapPaths path.copy(style = path.style.copy(variableWidth = null))
            path.copy(
                commands = outline,
                pathData = PathDataFormatter.format(outline),
                style = VectorStyle(
                    fillColor = path.style.strokeColor ?: "#000000",
                    fillAlpha = path.style.strokeAlpha,
                    fillType = path.style.fillType,
                ),
            )
        }

    /**
     * Replace every dashed stroked path's geometry with its baked "on" runs
     * (concatenated as multiple subpaths in one path, keeping the original stroke
     * style). Returns the baked document plus one [VectorWarning.Codes.STROKE_DASH_BAKED]
     * note per affected path so the caller can tell the user the geometry grew.
     */
    fun bakeDashes(document: VectorDocument): Pair<VectorDocument, List<VectorWarning>> {
        val warnings = ArrayList<VectorWarning>()
        val baked = mapPaths(document) { path ->
            val dash = path.style.strokeDashArray?.takeIf { it.isNotEmpty() } ?: return@mapPaths path
            val commands = path.commands?.takeIf { it.isNotEmpty() } ?: return@mapPaths path
            val runs = StrokeDashBaker.bake(commands, dash, path.style.strokeDashOffset ?: 0f)
            // A single run that is the original path means nothing was cut — leave it.
            if (runs.size == 1 && runs.first() === commands) return@mapPaths path
            val flattened = runs.flatten()
            if (flattened.isEmpty()) return@mapPaths path
            warnings += VectorWarning(
                VectorWarning.Codes.STROKE_DASH_BAKED,
                "Dashed stroke baked into ${runs.size} segment(s) for VectorDrawable export (no native dash attribute).",
                path.id,
            )
            path.copy(
                commands = flattened,
                pathData = PathDataFormatter.format(flattened),
                style = path.style.copy(strokeDashArray = null, strokeDashOffset = null),
            )
        }
        return baked to warnings
    }

    /** Map every leaf path in the tree through [fn], preserving structure. */
    private fun mapPaths(document: VectorDocument, fn: (VectorPath) -> VectorPath): VectorDocument {
        fun mapGroup(group: VectorGroup): VectorGroup =
            group.copy(
                children = group.children.map { child ->
                    when (child) {
                        is VectorNode.PathNode -> VectorNode.PathNode(fn(child.path))
                        is VectorNode.GroupNode -> VectorNode.GroupNode(mapGroup(child.group))
                        is VectorNode.InstanceNode -> child // unresolved instance passes through
                    }
                },
            )
        return document.copy(root = mapGroup(document.root))
    }
}
