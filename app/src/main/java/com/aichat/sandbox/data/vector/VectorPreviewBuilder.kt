package com.aichat.sandbox.data.vector

/**
 * Phase 8 — builds a [VectorPreviewModel] from a parsed [VectorDocument].
 *
 * Walks the document depth-first (root first), keeping only paths that actually
 * parsed into geometry. Unparsed paths and unsupported features (gradients, clip
 * paths, unknown tags) are surfaced as preview warnings rather than dropped
 * silently or rendered unsafely. It never mutates the document and never throws —
 * a malformed document simply yields an empty preview with a [PREVIEW_EMPTY]
 * warning.
 *
 * Bounds are reused from the deterministic [VectorPathCatalog] estimator so the
 * preview, metrics, and editing UIs all agree on path geometry.
 */
object VectorPreviewBuilder {

    /** Document warnings that mean "the preview will be visually incomplete". */
    private val UNSUPPORTED_CODES = setOf(
        VectorWarning.Codes.GRADIENT_NOT_SUPPORTED,
        VectorWarning.Codes.CLIP_PATH_NOT_SUPPORTED,
        VectorWarning.Codes.UNSUPPORTED_TAG,
    )

    fun build(document: VectorDocument): VectorPreviewModel {
        val paths = ArrayList<VectorPreviewPath>()
        val warnings = ArrayList<VectorWarning>()

        val boundsById: Map<String, VectorBounds?> = runCatching {
            VectorPathCatalog.catalog(document).associate { it.id to it.bounds }
        }.getOrDefault(emptyMap())

        fun walk(group: VectorGroup) {
            for (child in group.children) {
                when (child) {
                    is VectorNode.GroupNode -> walk(child.group)
                    is VectorNode.PathNode -> {
                        val path = child.path
                        val commands = path.commands
                        if (commands.isNullOrEmpty()) {
                            warnings += VectorWarning(
                                VectorWarning.Codes.PREVIEW_SKIPPED_UNPARSED_PATH,
                                "Skipped \"${path.name ?: path.id}\": no parsable geometry to preview.",
                                path.id,
                            )
                        } else {
                            paths += VectorPreviewPath(
                                id = path.id,
                                name = path.name,
                                commands = commands,
                                style = path.style.toPreviewStyle(),
                                bounds = boundsById[path.id],
                            )
                        }
                    }
                }
            }
        }

        runCatching { walk(document.root) }

        val unsupported = document.warnings.filter { it.code in UNSUPPORTED_CODES }
        if (unsupported.isNotEmpty()) {
            warnings += VectorWarning(
                VectorWarning.Codes.PREVIEW_UNSUPPORTED_FEATURE,
                "Preview omits ${unsupported.size} unsupported feature(s) " +
                    "(gradients, clip paths, or unknown tags).",
            )
        }

        if (paths.isEmpty()) {
            warnings += VectorWarning(
                VectorWarning.Codes.PREVIEW_EMPTY,
                "Nothing to preview: no parsable paths were found.",
            )
        } else if (warnings.isNotEmpty()) {
            warnings += VectorWarning(
                VectorWarning.Codes.PREVIEW_COMPILED_WITH_WARNINGS,
                "Preview rendered with ${warnings.size} note(s); some content may be missing.",
            )
        }

        return VectorPreviewModel(
            viewport = document.viewport,
            paths = paths,
            warnings = warnings,
        )
    }

    private fun VectorStyle.toPreviewStyle(): VectorPreviewStyle = VectorPreviewStyle(
        fillColor = fillColor,
        fillAlpha = fillAlpha,
        strokeColor = strokeColor,
        strokeAlpha = strokeAlpha,
        strokeWidth = strokeWidth,
        strokeLineCap = strokeLineCap,
        strokeLineJoin = strokeLineJoin,
        fillType = fillType,
        fill = fill,
    )
}
