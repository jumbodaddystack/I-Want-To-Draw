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
