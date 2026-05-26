package com.aichat.sandbox.data.vector

/**
 * Phase 7 — deterministic applier for [VectorManualEdit]s.
 *
 * Validates every path id, color, and style value before touching the tree:
 *  - unknown path ids are dropped with a [VectorWarning.Codes.MANUAL_EDIT_UNKNOWN_PATH] warning;
 *  - colors must be `#RGB`, `#RRGGBB`, or `#AARRGGBB` (else dropped + warning);
 *  - stroke width is clamped to [MIN_STROKE_WIDTH]..[MAX_STROKE_WIDTH];
 *  - line caps/joins are restricted to the Android-supported values;
 *  - an edit that matches no path becomes a no-op warning, not a failure.
 *
 * Geometry simplification reuses the Phase 2 sampler/simplifier utilities so the
 * output always re-parses through [AndroidVectorDrawableParser]. The viewport and
 * group hierarchy are preserved; the input document is never mutated.
 */
object VectorManualEditApplier {

    const val MIN_STROKE_WIDTH = 0.1f
    const val MAX_STROKE_WIDTH = 64f
    private const val DECIMAL_PLACES = 2

    private val VALID_LINE_CAPS = setOf("butt", "round", "square")
    private val VALID_LINE_JOINS = setOf("miter", "round", "bevel")
    private val COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    fun apply(
        document: VectorDocument,
        originalXml: String,
        edits: List<VectorManualEdit>,
    ): VectorManualEditResult {
        if (edits.isEmpty()) {
            return noOp(
                document,
                originalXml,
                VectorWarning(
                    VectorWarning.Codes.MANUAL_EDIT_EMPTY,
                    "No edits were provided.",
                ),
            )
        }

        val known = collectIds(document.root)
        val ctx = Context()
        var root = document.root

        for (edit in edits) {
            val prepared = prepare(edit, known, ctx) ?: continue
            ctx.matchedThisEdit = 0
            root = applyEdit(root, prepared, ctx)
            if (ctx.matchedThisEdit == 0) {
                ctx.warnings += VectorWarning(
                    VectorWarning.Codes.MANUAL_EDIT_NO_MATCHING_PATHS,
                    "Edit '${editName(edit)}' matched no paths.",
                )
            }
        }

        val candidate = document.copy(root = root)
        val xml = AndroidVectorDrawableWriter.write(candidate)
        val metrics = VectorMetricsAnalyzer.analyze(candidate, xml)

        if (ctx.warnings.isNotEmpty()) {
            ctx.warnings.add(
                0,
                VectorWarning(
                    VectorWarning.Codes.MANUAL_EDIT_APPLIED_WITH_WARNINGS,
                    "Manual edit applied with ${ctx.warnings.size} warning(s).",
                ),
            )
        }

        return VectorManualEditResult(
            document = candidate,
            xml = xml,
            metrics = metrics,
            warnings = ctx.warnings,
            editedPathCount = ctx.editedPathCount,
            deletedPathCount = ctx.deletedPathCount,
            summary = buildSummary(ctx),
        )
    }

    private fun noOp(
        document: VectorDocument,
        originalXml: String,
        warning: VectorWarning,
    ): VectorManualEditResult = VectorManualEditResult(
        document = document,
        xml = originalXml,
        metrics = VectorMetricsAnalyzer.analyze(document, originalXml),
        warnings = listOf(warning),
        editedPathCount = 0,
        deletedPathCount = 0,
        summary = "No changes applied.",
    )

    private class Context {
        val warnings = ArrayList<VectorWarning>()
        var editedPathCount = 0
        var deletedPathCount = 0
        var matchedThisEdit = 0
    }

    /** A validated edit ready to apply, or null if it has no usable target. */
    private sealed interface Prepared {
        val ids: Set<String>

        data class Delete(override val ids: Set<String>) : Prepared
        data class Recolor(
            override val ids: Set<String>,
            val strokeColor: String?,
            val fillColor: String?,
        ) : Prepared
        data class Restyle(
            override val ids: Set<String>,
            val strokeWidth: Float?,
            val lineCap: String?,
            val lineJoin: String?,
        ) : Prepared
        data class Simplify(
            override val ids: Set<String>,
            val tolerance: Float,
            val simplifyFills: Boolean,
        ) : Prepared
    }

    private fun prepare(edit: VectorManualEdit, known: Set<String>, ctx: Context): Prepared? {
        val rawIds = edit.pathIds()
        val valid = LinkedHashSet<String>()
        for (id in rawIds) {
            if (id in known) {
                valid += id
            } else {
                ctx.warnings += VectorWarning(
                    VectorWarning.Codes.MANUAL_EDIT_UNKNOWN_PATH,
                    "Unknown path id '$id' was ignored.",
                    id,
                )
            }
        }
        if (valid.isEmpty()) {
            ctx.warnings += VectorWarning(
                VectorWarning.Codes.MANUAL_EDIT_NO_MATCHING_PATHS,
                "Edit '${editName(edit)}' had no known target paths.",
            )
            return null
        }

        return when (edit) {
            is VectorManualEdit.DeletePaths -> Prepared.Delete(valid)
            is VectorManualEdit.RecolorPaths -> Prepared.Recolor(
                ids = valid,
                strokeColor = validColor(edit.strokeColor, ctx),
                fillColor = validColor(edit.fillColor, ctx),
            )
            is VectorManualEdit.RestylePaths -> Prepared.Restyle(
                ids = valid,
                strokeWidth = edit.strokeWidth?.let { clampStrokeWidth(it) },
                lineCap = validLineCap(edit.lineCap, ctx),
                lineJoin = validLineJoin(edit.lineJoin, ctx),
            )
            is VectorManualEdit.SimplifyPaths -> Prepared.Simplify(
                ids = valid,
                tolerance = edit.tolerance,
                simplifyFills = edit.simplifyFills,
            )
        }
    }

    private fun applyEdit(group: VectorGroup, prepared: Prepared, ctx: Context): VectorGroup {
        val newChildren = ArrayList<VectorNode>(group.children.size)
        for (child in group.children) {
            when (child) {
                is VectorNode.GroupNode ->
                    newChildren += VectorNode.GroupNode(applyEdit(child.group, prepared, ctx))
                is VectorNode.PathNode -> {
                    val path = child.path
                    if (path.id !in prepared.ids) {
                        newChildren += child
                        continue
                    }
                    ctx.matchedThisEdit++
                    when (prepared) {
                        is Prepared.Delete -> ctx.deletedPathCount++ // drop the node
                        is Prepared.Recolor -> {
                            newChildren += VectorNode.PathNode(recolor(path, prepared))
                            ctx.editedPathCount++
                        }
                        is Prepared.Restyle -> {
                            newChildren += VectorNode.PathNode(restyle(path, prepared))
                            ctx.editedPathCount++
                        }
                        is Prepared.Simplify -> {
                            newChildren += VectorNode.PathNode(simplify(path, prepared))
                            ctx.editedPathCount++
                        }
                    }
                }
            }
        }
        return group.copy(children = newChildren)
    }

    private fun recolor(path: VectorPath, op: Prepared.Recolor): VectorPath = path.copy(
        style = path.style.copy(
            strokeColor = op.strokeColor ?: path.style.strokeColor,
            fillColor = op.fillColor ?: path.style.fillColor,
        ),
    )

    private fun restyle(path: VectorPath, op: Prepared.Restyle): VectorPath = path.copy(
        style = path.style.copy(
            strokeWidth = op.strokeWidth ?: path.style.strokeWidth,
            strokeLineCap = op.lineCap ?: path.style.strokeLineCap,
            strokeLineJoin = op.lineJoin ?: path.style.strokeLineJoin,
        ),
    )

    private fun simplify(path: VectorPath, op: Prepared.Simplify): VectorPath {
        val commands = path.commands
        if (commands.isNullOrEmpty()) return path

        val style = path.style
        val filled = style.fillColor != null && !isTransparent(style.fillColor)
        val stroked = style.strokeColor != null && !isTransparent(style.strokeColor)
        val shouldSimplify = when {
            filled -> op.simplifyFills
            stroked -> true
            else -> false
        }
        if (!shouldSimplify) return path

        // Flattening multiple subpaths into one polyline would bridge their gaps.
        if (commands.count { it is PathCommand.MoveTo } > 1) return path

        val sampled = VectorPathSampler.sample(commands)
        val points = sampled.points
        if (points.size < 3) return path

        val deduped = VectorPathSimplifier.removeConsecutiveDuplicates(points)
        val simplified = VectorPathSimplifier.simplify(deduped, op.tolerance)
        if (simplified.size < 2 || simplified.size >= points.size) return path

        val newData = SimplifiedPathBuilder.buildPolylinePath(
            points = simplified,
            closed = sampled.closed,
            decimalPlaces = DECIMAL_PLACES,
        )
        val reparsed = PathDataParser.parse(newData, path.id).commands
        return path.copy(pathData = newData, commands = reparsed)
    }

    // ---- validation helpers ----

    private fun validColor(color: String?, ctx: Context): String? {
        if (color == null) return null
        if (COLOR_REGEX.matches(color.trim())) return color.trim()
        ctx.warnings += VectorWarning(
            VectorWarning.Codes.MANUAL_EDIT_INVALID_COLOR,
            "Ignored invalid color '$color'.",
        )
        return null
    }

    private fun validLineCap(cap: String?, ctx: Context): String? {
        if (cap == null) return null
        if (cap.lowercase() in VALID_LINE_CAPS) return cap.lowercase()
        ctx.warnings += VectorWarning(
            VectorWarning.Codes.MANUAL_EDIT_INVALID_STYLE,
            "Ignored invalid line cap '$cap'.",
        )
        return null
    }

    private fun validLineJoin(join: String?, ctx: Context): String? {
        if (join == null) return null
        if (join.lowercase() in VALID_LINE_JOINS) return join.lowercase()
        ctx.warnings += VectorWarning(
            VectorWarning.Codes.MANUAL_EDIT_INVALID_STYLE,
            "Ignored invalid line join '$join'.",
        )
        return null
    }

    private fun clampStrokeWidth(width: Float): Float =
        width.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)

    private fun isTransparent(color: String): Boolean {
        val hex = color.trim().removePrefix("#")
        return when (hex.length) {
            8 -> hex.substring(0, 2).equals("00", ignoreCase = true)
            4 -> hex[0] == '0'
            else -> false
        }
    }

    private fun collectIds(group: VectorGroup): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(g: VectorGroup) {
            for (child in g.children) {
                when (child) {
                    is VectorNode.GroupNode -> walk(child.group)
                    is VectorNode.PathNode -> out += child.path.id
                }
            }
        }
        walk(group)
        return out
    }

    private fun VectorManualEdit.pathIds(): List<String> = when (this) {
        is VectorManualEdit.DeletePaths -> pathIds
        is VectorManualEdit.RecolorPaths -> pathIds
        is VectorManualEdit.RestylePaths -> pathIds
        is VectorManualEdit.SimplifyPaths -> pathIds
    }

    private fun editName(edit: VectorManualEdit): String = when (edit) {
        is VectorManualEdit.DeletePaths -> "delete"
        is VectorManualEdit.RecolorPaths -> "recolor"
        is VectorManualEdit.RestylePaths -> "restyle"
        is VectorManualEdit.SimplifyPaths -> "simplify"
    }

    private fun buildSummary(ctx: Context): String = buildString {
        append("Edited ${ctx.editedPathCount}, deleted ${ctx.deletedPathCount} path(s)")
        if (ctx.warnings.isNotEmpty()) append(" with ${ctx.warnings.size} warning(s)")
        append('.')
    }
}
