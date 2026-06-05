package com.aichat.sandbox.data.vector

/**
 * Source-of-truth model for the Vector Art Tune-Up pipeline.
 *
 * A [VectorDocument] is the parsed, structured representation of an Android
 * `VectorDrawable` XML file. It is intentionally exact enough to round-trip the
 * subset of features Phase 1 supports (viewport, groups, paths, common path
 * commands, and the standard fill/stroke style attributes) so the writer can
 * regenerate equivalent XML, and lossy/unsupported bits are surfaced as
 * [VectorWarning]s rather than silently dropped.
 *
 * The whole drawing hangs off a synthetic [root] group with the stable id
 * `root`. Top-level `<path>`/`<group>` elements become its children, so callers
 * never have to special-case "is this the top level". The writer emits the
 * root's children directly under `<vector>` rather than wrapping them in a
 * `<group>`.
 */
data class VectorDocument(
    val viewport: VectorViewport,
    val root: VectorGroup,
    val warnings: List<VectorWarning> = emptyList(),
    val originalXmlBytes: Int? = null,
)

/**
 * The `<vector>` sizing box. [widthDp]/[heightDp] are the intrinsic dp size
 * (parsed from `android:width`/`android:height`); the viewport pair is the
 * coordinate space path data is expressed in.
 */
data class VectorViewport(
    val widthDp: Float,
    val heightDp: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
)

/**
 * A `<group>` (or the synthetic document root). Any transform attribute left
 * `null` is simply absent in the source and is not emitted by the writer.
 */
data class VectorGroup(
    val id: String,
    val name: String? = null,
    val rotation: Float? = null,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val translateX: Float? = null,
    val translateY: Float? = null,
    val children: List<VectorNode>,
)

/** A child of a [VectorGroup]: either a nested group or a path. */
sealed interface VectorNode {
    val id: String

    data class GroupNode(val group: VectorGroup) : VectorNode {
        override val id: String get() = group.id
    }

    data class PathNode(val path: VectorPath) : VectorNode {
        override val id: String get() = path.id
    }
}

/**
 * A `<path>`. [pathData] is the raw `android:pathData` string; [commands] is the
 * parsed form when the data could be understood. [commands] is null when the
 * path data was blank or could not be parsed at all, in which case the writer
 * falls back to emitting [pathData] verbatim to avoid losing the original.
 */
data class VectorPath(
    val id: String,
    val name: String? = null,
    val pathData: String,
    val commands: List<PathCommand>? = null,
    val style: VectorStyle,
)

/** The standard fill/stroke attributes on a `<path>`. Null = attribute absent. */
data class VectorStyle(
    val fillColor: String? = null,
    val fillAlpha: Float? = null,
    val fillType: String? = null,
    val strokeColor: String? = null,
    val strokeAlpha: Float? = null,
    val strokeWidth: Float? = null,
    val strokeLineCap: String? = null,
    val strokeLineJoin: String? = null,
    val strokeMiterLimit: Float? = null,
    // Phase 5 (sub-feature 1) — additive stroke styling. All nullable so every
    // existing construction compiles and every current document/writer test is
    // byte-identical (these only affect a path that opts in).
    /** On/off dash lengths in viewport units. SVG emits this natively; Android XML bakes it into geometry. */
    val strokeDashArray: List<Float>? = null,
    /** Dash phase (distance into the pattern at the path start). */
    val strokeDashOffset: Float? = null,
    /** Width-along-the-path profile. Has no native attribute in either format, so export bakes it to a filled outline. */
    val variableWidth: VariableWidthProfile? = null,
    // Phase 5 (sub-feature 2) — real fill model. Additive + nullable: when null the
    // scalar fillColor/fillAlpha path above is untouched (byte-identical for every
    // existing document); a non-null fill overrides the scalar fill and carries
    // solid or linear/radial/sweep gradients through writers + preview.
    /** Solid or gradient fill. Overrides [fillColor]/[fillAlpha] when present. */
    val fill: VectorFill? = null,
)

/**
 * A recoverable problem found while parsing/validating a document. [code] is a
 * stable machine-readable token (see [VectorWarning.Codes]); [nodeId] points at
 * the offending path/group when known.
 */
data class VectorWarning(
    val code: String,
    val message: String,
    val nodeId: String? = null,
) {
    /** Stable warning codes shared by the parser, validator, and metrics. */
    object Codes {
        const val MALFORMED_XML = "MALFORMED_XML"
        const val UNSUPPORTED_TAG = "UNSUPPORTED_TAG"
        const val UNSUPPORTED_ATTRIBUTE = "UNSUPPORTED_ATTRIBUTE"
        const val MALFORMED_PATH_DATA = "MALFORMED_PATH_DATA"
        const val MISSING_VIEWPORT = "MISSING_VIEWPORT"
        const val MISSING_PATH_DATA = "MISSING_PATH_DATA"
        const val NON_POSITIVE_VIEWPORT = "NON_POSITIVE_VIEWPORT"
        const val NO_FILL_OR_STROKE = "NO_FILL_OR_STROKE"
        const val NEGATIVE_STROKE_WIDTH = "NEGATIVE_STROKE_WIDTH"
        const val GRADIENT_NOT_SUPPORTED = "GRADIENT_NOT_SUPPORTED"
        const val CLIP_PATH_NOT_SUPPORTED = "CLIP_PATH_NOT_SUPPORTED"

        // Phase 5 — stroke styling. Android VectorDrawable has no dash attribute,
        // so a dashed stroke is baked into chopped geometry on export and flagged.
        const val STROKE_DASH_BAKED = "STROKE_DASH_BAKED"

        // Phase 5 — bitmap auto-trace.
        const val TRACE_EMPTY = "TRACE_EMPTY"
        const val TRACE_FELL_BACK_TO_LOCAL = "TRACE_FELL_BACK_TO_LOCAL"

        // Phase 2 — faithful deterministic optimizer.
        const val OPTIMIZER_SKIPPED_UNPARSED_PATH = "OPTIMIZER_SKIPPED_UNPARSED_PATH"
        const val OPTIMIZER_SKIPPED_FILLED_PATH = "OPTIMIZER_SKIPPED_FILLED_PATH"
        const val OPTIMIZER_REMOVED_TINY_PATH = "OPTIMIZER_REMOVED_TINY_PATH"
        const val OPTIMIZER_EMPTY_SIMPLIFIED_PATH = "OPTIMIZER_EMPTY_SIMPLIFIED_PATH"

        // Phase 4 — model-guided tune-up plans.
        const val SUMMARY_PATHS_DROPPED = "SUMMARY_PATHS_DROPPED"
        const val AI_PLAN_PARSE_FAILED = "AI_PLAN_PARSE_FAILED"
        const val AI_PLAN_EMPTY = "AI_PLAN_EMPTY"
        const val AI_PLAN_UNKNOWN_PATH = "AI_PLAN_UNKNOWN_PATH"
        const val AI_PLAN_UNSUPPORTED_OPERATION = "AI_PLAN_UNSUPPORTED_OPERATION"
        const val AI_PLAN_NO_MATCHING_PATHS = "AI_PLAN_NO_MATCHING_PATHS"
        const val AI_PLAN_SKIPPED_INVALID_OPERATION = "AI_PLAN_SKIPPED_INVALID_OPERATION"
        const val AI_PLAN_APPLIED_WITH_WARNINGS = "AI_PLAN_APPLIED_WITH_WARNINGS"

        // Phase 5 — semantic redraw mode.
        const val SCENE_PARSE_FAILED = "SCENE_PARSE_FAILED"
        const val SCENE_EMPTY = "SCENE_EMPTY"
        const val SCENE_OBJECT_REJECTED = "SCENE_OBJECT_REJECTED"
        const val SCENE_UNSUPPORTED_OBJECT = "SCENE_UNSUPPORTED_OBJECT"
        const val SCENE_OUT_OF_BOUNDS = "SCENE_OUT_OF_BOUNDS"
        const val SCENE_MALFORMED_PATH = "SCENE_MALFORMED_PATH"
        const val SCENE_COMPILED_WITH_WARNINGS = "SCENE_COMPILED_WITH_WARNINGS"
        const val REDRAW_STREAM_FAILED = "REDRAW_STREAM_FAILED"
        const val REDRAW_PARSE_FAILED = "REDRAW_PARSE_FAILED"
        const val REDRAW_COMPILE_FAILED = "REDRAW_COMPILE_FAILED"

        // Phase 7 — manual per-path editing and batch restyle.
        const val MANUAL_EDIT_EMPTY = "MANUAL_EDIT_EMPTY"
        const val MANUAL_EDIT_UNKNOWN_PATH = "MANUAL_EDIT_UNKNOWN_PATH"
        const val MANUAL_EDIT_INVALID_COLOR = "MANUAL_EDIT_INVALID_COLOR"
        const val MANUAL_EDIT_INVALID_STYLE = "MANUAL_EDIT_INVALID_STYLE"
        const val MANUAL_EDIT_NO_MATCHING_PATHS = "MANUAL_EDIT_NO_MATCHING_PATHS"
        const val MANUAL_EDIT_APPLIED_WITH_WARNINGS = "MANUAL_EDIT_APPLIED_WITH_WARNINGS"

        // Phase 8 — safe preview rendering and visual diff.
        const val PREVIEW_SKIPPED_UNPARSED_PATH = "PREVIEW_SKIPPED_UNPARSED_PATH"
        const val PREVIEW_UNSUPPORTED_FEATURE = "PREVIEW_UNSUPPORTED_FEATURE"
        const val PREVIEW_EMPTY = "PREVIEW_EMPTY"
        const val PREVIEW_COMPILED_WITH_WARNINGS = "PREVIEW_COMPILED_WITH_WARNINGS"

        // Phase 9 — SVG interoperability and portable export.
        const val SVG_UNSUPPORTED_TAG = "SVG_UNSUPPORTED_TAG"
        const val SVG_UNSUPPORTED_ATTRIBUTE = "SVG_UNSUPPORTED_ATTRIBUTE"
        const val SVG_MALFORMED = "SVG_MALFORMED"
        const val SVG_MISSING_VIEWBOX = "SVG_MISSING_VIEWBOX"
        const val SVG_EXTERNAL_RESOURCE_IGNORED = "SVG_EXTERNAL_RESOURCE_IGNORED"
        const val SVG_STYLE_UNSUPPORTED = "SVG_STYLE_UNSUPPORTED"
        const val SVG_TRANSFORM_UNSUPPORTED = "SVG_TRANSFORM_UNSUPPORTED"
        const val SVG_GRADIENT_UNSUPPORTED = "SVG_GRADIENT_UNSUPPORTED"
        const val SVG_IMPORT_PARTIAL = "SVG_IMPORT_PARTIAL"
        const val SVG_EXPORT_PARTIAL = "SVG_EXPORT_PARTIAL"
        const val BUNDLE_EXPORT_FAILED = "BUNDLE_EXPORT_FAILED"
        const val IMPORT_UNKNOWN_FORMAT = "IMPORT_UNKNOWN_FORMAT"

        // Phase 10 — portable bundle import and version-graph management.
        const val BUNDLE_PARSE_FAILED = "BUNDLE_PARSE_FAILED"
        const val BUNDLE_UNSUPPORTED_SCHEMA = "BUNDLE_UNSUPPORTED_SCHEMA"
        const val BUNDLE_WRONG_KIND = "BUNDLE_WRONG_KIND"
        const val BUNDLE_EMPTY = "BUNDLE_EMPTY"
        const val BUNDLE_VERSION_INVALID = "BUNDLE_VERSION_INVALID"
        const val BUNDLE_VERSION_XML_INVALID = "BUNDLE_VERSION_XML_INVALID"
        const val BUNDLE_PARENT_REPAIRED = "BUNDLE_PARENT_REPAIRED"
        const val BUNDLE_ID_REMAPPED = "BUNDLE_ID_REMAPPED"
        const val BUNDLE_IMPORTED_WITH_WARNINGS = "BUNDLE_IMPORTED_WITH_WARNINGS"
        const val VERSION_DELETE_BLOCKED = "VERSION_DELETE_BLOCKED"
        const val VERSION_DELETE_FAILED = "VERSION_DELETE_FAILED"
        const val VERSION_DUPLICATE_FAILED = "VERSION_DUPLICATE_FAILED"
    }
}

/**
 * A single parsed path command. Mirrors the Android/SVG path grammar. The
 * [relative] flag records whether the source used the lowercase (relative)
 * form; Phase 1 preserves the distinction but does not normalize to absolute.
 */
sealed interface PathCommand {
    val relative: Boolean

    data class MoveTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class LineTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class HorizontalTo(val x: Float, override val relative: Boolean = false) : PathCommand
    data class VerticalTo(val y: Float, override val relative: Boolean = false) : PathCommand
    data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x: Float, val y: Float,
        override val relative: Boolean = false,
    ) : PathCommand
    data class SmoothCubicTo(
        val x2: Float, val y2: Float,
        val x: Float, val y: Float,
        override val relative: Boolean = false,
    ) : PathCommand
    data class QuadTo(
        val x1: Float, val y1: Float,
        val x: Float, val y: Float,
        override val relative: Boolean = false,
    ) : PathCommand
    data class SmoothQuadTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class ArcTo(
        val rx: Float, val ry: Float,
        val xAxisRotation: Float,
        val largeArc: Boolean, val sweep: Boolean,
        val x: Float, val y: Float,
        override val relative: Boolean = false,
    ) : PathCommand
    data class Close(override val relative: Boolean = false) : PathCommand
}

/** Depth-first list of every path in the document, root first. */
fun VectorDocument.allPaths(): List<VectorPath> {
    val out = ArrayList<VectorPath>()
    fun walk(group: VectorGroup) {
        for (child in group.children) {
            when (child) {
                is VectorNode.PathNode -> out += child.path
                is VectorNode.GroupNode -> walk(child.group)
            }
        }
    }
    walk(root)
    return out
}

/**
 * Return a copy of this document with the path identified by [pathId] replaced by
 * [newPath], preserving tree position (group nesting and sibling order). When no
 * path matches, the document is returned unchanged. Used by the Phase 1 node
 * editor to write an edited [VectorPath] back into the immutable tree.
 */
fun VectorDocument.replacePath(pathId: String, newPath: VectorPath): VectorDocument {
    fun mapGroup(group: VectorGroup): VectorGroup =
        group.copy(
            children = group.children.map { child ->
                when (child) {
                    is VectorNode.PathNode ->
                        if (child.path.id == pathId) VectorNode.PathNode(newPath) else child
                    is VectorNode.GroupNode ->
                        VectorNode.GroupNode(mapGroup(child.group))
                }
            },
        )
    return copy(root = mapGroup(root))
}

/**
 * Return a copy of this document with [newPath] written in: it **replaces** the
 * existing path with the same id (preserving tree position, see [replacePath]), or
 * — when no path matches — is **appended** to the root group. The node editor uses
 * this for write-back so it serves both editing an existing path and committing a
 * brand-new one (drawn with the pen tool) through the same call.
 */
fun VectorDocument.upsertPath(pathId: String, newPath: VectorPath): VectorDocument {
    if (allPaths().any { it.id == pathId }) return replacePath(pathId, newPath)
    return copy(root = root.copy(children = root.children + VectorNode.PathNode(newPath)))
}

/** Depth-first list of every non-root group in the document. */
fun VectorDocument.allGroups(): List<VectorGroup> {
    val out = ArrayList<VectorGroup>()
    fun walk(group: VectorGroup) {
        for (child in group.children) {
            if (child is VectorNode.GroupNode) {
                out += child.group
                walk(child.group)
            }
        }
    }
    walk(root)
    return out
}
