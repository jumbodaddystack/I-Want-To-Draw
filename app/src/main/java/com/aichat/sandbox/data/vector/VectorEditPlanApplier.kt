package com.aichat.sandbox.data.vector

/**
 * Outcome of applying a [VectorEditPlan] to a [VectorDocument].
 *
 * [document]/[xml] are the candidate produced from the original plus the plan;
 * the original document is never mutated. The counts describe what actually
 * happened so the UI can show an operation report, and [warnings] surfaces any
 * operations that matched nothing or were skipped.
 */
data class VectorEditPlanApplyResult(
    val document: VectorDocument,
    val xml: String,
    val metrics: VectorMetrics,
    val simplifiedPathCount: Int,
    val removedPathCount: Int,
    val restyledPathCount: Int,
    val recoloredPathCount: Int,
    val skippedOperationCount: Int,
    val warnings: List<VectorWarning>,
    val summary: String,
)

/**
 * Phase 4 — deterministic applier for a validated [VectorEditPlan].
 *
 * The model proposed; the parser validated; this applies. Operations run in
 * order against the document tree, each resolving its own [VectorPathTarget] to
 * a set of matched paths. Paths that no valid target matched are never touched.
 * Geometry simplification reuses the Phase 2 [VectorPathSampler] /
 * [VectorPathSimplifier] / [SimplifiedPathBuilder] utilities so output always
 * re-parses through [AndroidVectorDrawableParser]. The viewport and group
 * hierarchy are preserved exactly.
 */
object VectorEditPlanApplier {

    private const val DECIMAL_PLACES = 2

    fun apply(
        document: VectorDocument,
        originalXml: String,
        plan: VectorEditPlan,
    ): VectorEditPlanApplyResult {
        if (plan.operations.isEmpty()) {
            val metrics = VectorMetricsAnalyzer.analyze(document, originalXml)
            return VectorEditPlanApplyResult(
                document = document,
                xml = originalXml,
                metrics = metrics,
                simplifiedPathCount = 0,
                removedPathCount = 0,
                restyledPathCount = 0,
                recoloredPathCount = 0,
                skippedOperationCount = 0,
                warnings = listOf(
                    VectorWarning(
                        VectorWarning.Codes.AI_PLAN_EMPTY,
                        "The plan proposed no changes",
                    ),
                ),
                summary = plan.summary.ifBlank { "No changes proposed." },
            )
        }

        val ctx = Context()
        var root = document.root
        for (op in plan.operations) {
            ctx.matchedThisOp = 0
            root = applyOperation(root, op, ctx)
            if (ctx.matchedThisOp == 0) {
                ctx.skippedOperationCount++
                ctx.warnings += VectorWarning(
                    VectorWarning.Codes.AI_PLAN_NO_MATCHING_PATHS,
                    "Operation '${opName(op)}' matched no paths and was skipped",
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
                    VectorWarning.Codes.AI_PLAN_APPLIED_WITH_WARNINGS,
                    "AI plan applied with ${ctx.warnings.size} warning(s)",
                ),
            )
        }

        return VectorEditPlanApplyResult(
            document = candidate,
            xml = xml,
            metrics = metrics,
            simplifiedPathCount = ctx.simplifiedPathCount,
            removedPathCount = ctx.removedPathCount,
            restyledPathCount = ctx.restyledPathCount,
            recoloredPathCount = ctx.recoloredPathCount,
            skippedOperationCount = ctx.skippedOperationCount,
            warnings = ctx.warnings,
            summary = buildSummary(plan, ctx),
        )
    }

    private class Context {
        val warnings = ArrayList<VectorWarning>()
        var simplifiedPathCount = 0
        var removedPathCount = 0
        var restyledPathCount = 0
        var recoloredPathCount = 0
        var skippedOperationCount = 0
        var matchedThisOp = 0
    }

    private fun applyOperation(
        group: VectorGroup,
        op: VectorEditOperation,
        ctx: Context,
    ): VectorGroup {
        val newChildren = ArrayList<VectorNode>(group.children.size)
        for (child in group.children) {
            when (child) {
                is VectorNode.InstanceNode -> newChildren += child // unresolved instance passes through
                is VectorNode.GroupNode ->
                    newChildren += VectorNode.GroupNode(applyOperation(child.group, op, ctx))
                is VectorNode.PathNode -> {
                    val path = child.path
                    if (!matches(path, op.target)) {
                        newChildren += child
                        continue
                    }
                    ctx.matchedThisOp++
                    when (op) {
                        is VectorEditOperation.RemovePaths -> {
                            ctx.removedPathCount++
                            // Drop the node.
                        }
                        is VectorEditOperation.RestylePaths -> {
                            newChildren += VectorNode.PathNode(restyle(path, op))
                            ctx.restyledPathCount++
                        }
                        is VectorEditOperation.RecolorPaths -> {
                            newChildren += VectorNode.PathNode(recolor(path, op))
                            ctx.recoloredPathCount++
                        }
                        is VectorEditOperation.SimplifyPaths -> {
                            when (val outcome = simplify(path, op)) {
                                SimplifyOutcome.Remove -> ctx.removedPathCount++
                                is SimplifyOutcome.Keep -> newChildren += VectorNode.PathNode(outcome.path)
                                is SimplifyOutcome.Replace -> {
                                    newChildren += VectorNode.PathNode(outcome.path)
                                    ctx.simplifiedPathCount++
                                }
                            }
                        }
                    }
                }
            }
        }
        return group.copy(children = newChildren)
    }

    // ---- target resolution ----

    private fun matches(path: VectorPath, target: VectorPathTarget): Boolean {
        val selected = path.id in target.pathIds ||
            target.colors.any { colorMatches(path, it) } ||
            target.all
        if (!selected) return false
        if (target.strokedOnly && !isStroked(path)) return false
        if (target.filledOnly && !isFilled(path)) return false
        return true
    }

    private fun colorMatches(path: VectorPath, color: String): Boolean {
        val c = color.lowercase()
        return path.style.fillColor?.lowercase() == c || path.style.strokeColor?.lowercase() == c
    }

    private fun isStroked(path: VectorPath): Boolean {
        val s = path.style.strokeColor ?: return false
        return !isTransparent(s)
    }

    private fun isFilled(path: VectorPath): Boolean {
        val f = path.style.fillColor ?: return false
        return !isTransparent(f)
    }

    // ---- per-operation transforms ----

    private fun restyle(path: VectorPath, op: VectorEditOperation.RestylePaths): VectorPath {
        val style = path.style.copy(
            strokeWidth = op.strokeWidth ?: path.style.strokeWidth,
            strokeLineCap = op.lineCap ?: path.style.strokeLineCap,
            strokeLineJoin = op.lineJoin ?: path.style.strokeLineJoin,
        )
        return path.copy(style = style)
    }

    private fun recolor(path: VectorPath, op: VectorEditOperation.RecolorPaths): VectorPath {
        val style = path.style.copy(
            strokeColor = op.strokeColor ?: path.style.strokeColor,
            fillColor = op.fillColor ?: path.style.fillColor,
        )
        return path.copy(style = style)
    }

    private sealed interface SimplifyOutcome {
        data class Keep(val path: VectorPath) : SimplifyOutcome
        data class Replace(val path: VectorPath) : SimplifyOutcome
        data object Remove : SimplifyOutcome
    }

    private fun simplify(path: VectorPath, op: VectorEditOperation.SimplifyPaths): SimplifyOutcome {
        val commands = path.commands
        if (commands.isNullOrEmpty()) return SimplifyOutcome.Keep(path)

        val stroked = isStroked(path) && (path.style.strokeWidth == null || path.style.strokeWidth > 0f)
        val filled = isFilled(path)

        val sampled = VectorPathSampler.sample(commands)
        val points = sampled.points

        val minLen = op.minPathLength
        if (minLen != null && stroked && !filled) {
            val length = VectorPathSimplifier.pathLength(points, sampled.closed)
            if (length < minLen) return SimplifyOutcome.Remove
        }

        val shouldSimplify = when {
            filled -> op.simplifyFills
            stroked -> true
            else -> false
        }
        if (!shouldSimplify) return SimplifyOutcome.Keep(path)

        // Flattening multiple subpaths into one polyline would bridge their
        // gaps; leave such paths alone (matches the Phase 2 optimizer).
        if (commands.count { it is PathCommand.MoveTo } > 1) return SimplifyOutcome.Keep(path)
        if (points.size < 3) return SimplifyOutcome.Keep(path)

        val deduped = VectorPathSimplifier.removeConsecutiveDuplicates(points)
        val simplified = VectorPathSimplifier.simplify(deduped, op.tolerance)
        if (simplified.size < 2 || simplified.size >= points.size) return SimplifyOutcome.Keep(path)

        val newData = SimplifiedPathBuilder.buildPolylinePath(
            points = simplified,
            closed = sampled.closed,
            decimalPlaces = DECIMAL_PLACES,
        )
        val reparsed = PathDataParser.parse(newData, path.id).commands
        return SimplifyOutcome.Replace(path.copy(pathData = newData, commands = reparsed))
    }

    // ---- helpers ----

    private fun isTransparent(color: String): Boolean {
        val hex = color.trim().removePrefix("#")
        return when (hex.length) {
            8 -> hex.substring(0, 2).equals("00", ignoreCase = true)
            4 -> hex[0] == '0'
            else -> false
        }
    }

    private fun opName(op: VectorEditOperation): String = when (op) {
        is VectorEditOperation.SimplifyPaths -> "simplify_paths"
        is VectorEditOperation.RemovePaths -> "remove_paths"
        is VectorEditOperation.RestylePaths -> "restyle_paths"
        is VectorEditOperation.RecolorPaths -> "recolor_paths"
    }

    private fun buildSummary(plan: VectorEditPlan, ctx: Context): String = buildString {
        append(plan.summary.ifBlank { "Applied AI tune-up plan." })
        append('\n')
        append(
            "Simplified ${ctx.simplifiedPathCount}, removed ${ctx.removedPathCount}, " +
                "restyled ${ctx.restyledPathCount}, recolored ${ctx.recoloredPathCount} path(s)",
        )
        if (ctx.skippedOperationCount > 0) {
            append(", skipped ${ctx.skippedOperationCount} operation(s)")
        }
    }
}
