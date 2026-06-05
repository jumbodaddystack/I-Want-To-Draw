package com.aichat.sandbox.data.vector

/**
 * Phase 2 faithful deterministic optimizer for the Vector Art Tune-Up pipeline.
 *
 * Takes VectorDrawable XML (or an already-parsed [VectorDocument]) and produces
 * a cleaned version that preserves visual intent: the viewport, group hierarchy
 * and transforms, path styles, and path names are all kept untouched. Only path
 * geometry changes — noisy stroked polylines are simplified via
 * [VectorPathSampler] + [VectorPathSimplifier], and tiny stroke-only paths can
 * be dropped. Filled shapes are left alone unless explicitly opted in.
 *
 * The optimizer is conservative by design (see [VectorOptimizeOptions]) and
 * never performs semantic interpretation of the artwork. Output always parses
 * again through [AndroidVectorDrawableParser].
 */
object VectorDrawableOptimizer {

    /** Parses [xml] then optimizes it. */
    fun optimize(
        xml: String,
        options: VectorOptimizeOptions = VectorOptimizeOptions(),
    ): VectorOptimizationResult {
        val document = AndroidVectorDrawableParser.parse(xml)
        return optimize(document, originalXml = xml, options = options)
    }

    /**
     * Optimizes an already-parsed [document]. [originalXml] is used for accurate
     * "before" byte metrics when available; otherwise the document is
     * re-serialized to measure it.
     */
    fun optimize(
        document: VectorDocument,
        originalXml: String? = null,
        options: VectorOptimizeOptions = VectorOptimizeOptions(),
    ): VectorOptimizationResult {
        val beforeXml = originalXml ?: AndroidVectorDrawableWriter.write(document)
        val before = VectorMetricsAnalyzer.analyze(document, beforeXml)

        val ctx = Context(options)
        val optimizedRoot = optimizeGroup(document.root, ctx)
        val optimizedDoc = document.copy(root = optimizedRoot)

        val afterXml = AndroidVectorDrawableWriter.write(optimizedDoc)
        val after = VectorMetricsAnalyzer.analyze(optimizedDoc, afterXml)

        val combinedWarnings = buildList {
            addAll(document.warnings)
            addAll(VectorDocumentValidator.validate(document))
            addAll(ctx.warnings)
        }.distinct()

        val report = VectorOptimizationReport(
            before = before,
            after = after,
            removedPathCount = ctx.removedPathCount,
            simplifiedPathCount = ctx.simplifiedPathCount,
            unsupportedPathCount = ctx.unsupportedPathCount,
            warnings = combinedWarnings,
        )
        return VectorOptimizationResult(
            document = optimizedDoc,
            xml = afterXml,
            report = report,
        )
    }

    /** Mutable per-run accumulators. */
    private class Context(val options: VectorOptimizeOptions) {
        val warnings = ArrayList<VectorWarning>()
        var removedPathCount = 0
        var simplifiedPathCount = 0
        var unsupportedPathCount = 0
    }

    private fun optimizeGroup(group: VectorGroup, ctx: Context): VectorGroup {
        val newChildren = ArrayList<VectorNode>(group.children.size)
        for (child in group.children) {
            when (child) {
                is VectorNode.InstanceNode -> newChildren += child // unresolved instance passes through
                is VectorNode.GroupNode ->
                    newChildren += VectorNode.GroupNode(optimizeGroup(child.group, ctx))
                is VectorNode.PathNode -> {
                    val optimized = optimizePath(child.path, ctx)
                    if (optimized != null) newChildren += VectorNode.PathNode(optimized)
                }
            }
        }
        return group.copy(children = newChildren)
    }

    /** Returns the optimized path, or null when the path was removed. */
    private fun optimizePath(path: VectorPath, ctx: Context): VectorPath? {
        val options = ctx.options
        val commands = path.commands

        if (commands.isNullOrEmpty()) {
            // Geometry we couldn't parse: keep it verbatim, never touch it.
            if (path.pathData.isNotBlank()) {
                ctx.unsupportedPathCount++
                ctx.warnings += VectorWarning(
                    VectorWarning.Codes.OPTIMIZER_SKIPPED_UNPARSED_PATH,
                    "Path geometry could not be parsed; left unchanged",
                    path.id,
                )
            }
            return path
        }

        val style = path.style
        val stroked = style.strokeColor != null &&
            style.strokeWidth != null && style.strokeWidth > 0f
        val filled = style.fillColor != null && style.fillColor != "#00000000"

        val sampled = VectorPathSampler.sample(commands)
        val points = sampled.points

        // Tiny stroke-only paths can be removed outright. Filled content is
        // never removed, so we don't silently lose shapes.
        if (options.removeTinyPaths && stroked && !filled) {
            val length = VectorPathSimplifier.pathLength(points, sampled.closed)
            if (length < options.minPathLength) {
                ctx.removedPathCount++
                ctx.warnings += VectorWarning(
                    VectorWarning.Codes.OPTIMIZER_REMOVED_TINY_PATH,
                    "Removed tiny stroke path (length ${length} < ${options.minPathLength})",
                    path.id,
                )
                return null
            }
        }

        val shouldSimplify = when {
            filled -> options.simplifyFills
            stroked -> options.simplifyStrokes
            else -> false
        }
        if (!shouldSimplify) return path

        // Flattening curves into polylines would change their shape; honor the
        // opt-out for paths whose source contained curves or arcs.
        if (options.preserveCurves && sampled.sourceHadCurves) return path

        // Multiple subpaths flatten into one polyline here, so simplifying would
        // bridge the gaps between them. Leave such paths untouched.
        if (commands.count { it is PathCommand.MoveTo } > 1) return path

        if (points.size < 3) return path

        val deduped = VectorPathSimplifier.removeConsecutiveDuplicates(points)
        val simplified = VectorPathSimplifier.simplify(deduped, options.tolerance)

        if (simplified.size < 2) {
            ctx.warnings += VectorWarning(
                VectorWarning.Codes.OPTIMIZER_EMPTY_SIMPLIFIED_PATH,
                "Simplification collapsed the path; kept the original geometry",
                path.id,
            )
            return path
        }

        if (simplified.size >= points.size) {
            // Nothing to gain — leave the original geometry as-is.
            return path
        }

        val newData = SimplifiedPathBuilder.buildPolylinePath(
            points = simplified,
            closed = sampled.closed,
            decimalPlaces = options.decimalPlaces,
        )
        val reparsed = PathDataParser.parse(newData, path.id).commands
        ctx.simplifiedPathCount++
        return path.copy(pathData = newData, commands = reparsed)
    }
}
