package com.aichat.sandbox.data.vector

import kotlin.math.max
import kotlin.math.min

/**
 * A flattened, UI-friendly description of one `<path>` in a [VectorDocument].
 *
 * @property id Stable parser-assigned path id (e.g. `p_001`).
 * @property name The `android:name`, if any.
 * @property index Depth-first position of this path within the document.
 * @property groupId The id of the immediate parent group, or null at the root.
 * @property commandCount Parsed command count (0 for unparsed paths).
 * @property estimatedPointCount Conservative point estimate over the commands.
 * @property bounds Estimated geometry bounds, or null when no geometry was parsed.
 * @property warnings Document warnings whose `nodeId` points at this path.
 */
data class VectorPathCatalogEntry(
    val id: String,
    val name: String?,
    val index: Int,
    val groupId: String?,
    val fillColor: String?,
    val strokeColor: String?,
    val strokeWidth: Float?,
    val commandCount: Int,
    val estimatedPointCount: Int,
    val bounds: VectorBounds?,
    val warnings: List<VectorWarning>,
)

/**
 * Phase 7 — builds a per-path catalog used by the manual editing UI.
 *
 * Walks the document depth-first (root first), preserving each path's id, parent
 * group, style, and a conservative geometry estimate. It never mutates the
 * document and never throws — unparsed paths simply report a zero command count
 * and null bounds.
 */
object VectorPathCatalog {

    fun catalog(document: VectorDocument): List<VectorPathCatalogEntry> {
        val out = ArrayList<VectorPathCatalogEntry>()
        val warningsByNode = document.warnings
            .filter { it.nodeId != null }
            .groupBy { it.nodeId }
        var index = 0

        fun walk(group: VectorGroup, parentGroupId: String?) {
            for (child in group.children) {
                when (child) {
                    is VectorNode.GroupNode -> walk(child.group, child.group.id)
                    is VectorNode.PathNode -> {
                        val path = child.path
                        val commands = path.commands.orEmpty()
                        out += VectorPathCatalogEntry(
                            id = path.id,
                            name = path.name,
                            index = index++,
                            groupId = parentGroupId,
                            fillColor = path.style.fillColor,
                            strokeColor = path.style.strokeColor,
                            strokeWidth = path.style.strokeWidth,
                            commandCount = commands.size,
                            estimatedPointCount = commands.sumOf { pointsIn(it) },
                            bounds = estimateBounds(commands),
                            warnings = warningsByNode[path.id].orEmpty(),
                        )
                    }
                }
            }
        }

        walk(document.root, null)
        return out
    }

    private fun pointsIn(cmd: PathCommand): Int = when (cmd) {
        is PathCommand.MoveTo, is PathCommand.LineTo,
        is PathCommand.HorizontalTo, is PathCommand.VerticalTo,
        is PathCommand.SmoothQuadTo, is PathCommand.ArcTo -> 1
        is PathCommand.SmoothCubicTo, is PathCommand.QuadTo -> 2
        is PathCommand.CubicTo -> 3
        is PathCommand.Close -> 0
    }

    /**
     * Conservative absolute-coordinate bounds over [commands]. Curve control
     * points are folded in (over-estimate); relative commands are resolved
     * against the running pen position. Returns null when no point was produced.
     */
    private fun estimateBounds(commands: List<PathCommand>): VectorBounds? {
        if (commands.isEmpty()) return null
        var cx = 0f; var cy = 0f
        var startX = 0f; var startY = 0f
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var saw = false

        fun include(x: Float, y: Float) {
            minX = min(minX, x); minY = min(minY, y)
            maxX = max(maxX, x); maxY = max(maxY, y)
            saw = true
        }
        fun ax(v: Float, rel: Boolean) = if (rel) cx + v else v
        fun ay(v: Float, rel: Boolean) = if (rel) cy + v else v
        fun moveTo(x: Float, y: Float) { include(x, y); cx = x; cy = y }

        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo -> {
                    val x = ax(cmd.x, cmd.relative); val y = ay(cmd.y, cmd.relative)
                    moveTo(x, y); startX = x; startY = y
                }
                is PathCommand.LineTo -> moveTo(ax(cmd.x, cmd.relative), ay(cmd.y, cmd.relative))
                is PathCommand.HorizontalTo -> moveTo(ax(cmd.x, cmd.relative), cy)
                is PathCommand.VerticalTo -> moveTo(cx, ay(cmd.y, cmd.relative))
                is PathCommand.CubicTo -> {
                    include(ax(cmd.x1, cmd.relative), ay(cmd.y1, cmd.relative))
                    include(ax(cmd.x2, cmd.relative), ay(cmd.y2, cmd.relative))
                    moveTo(ax(cmd.x, cmd.relative), ay(cmd.y, cmd.relative))
                }
                is PathCommand.SmoothCubicTo -> {
                    include(ax(cmd.x2, cmd.relative), ay(cmd.y2, cmd.relative))
                    moveTo(ax(cmd.x, cmd.relative), ay(cmd.y, cmd.relative))
                }
                is PathCommand.QuadTo -> {
                    include(ax(cmd.x1, cmd.relative), ay(cmd.y1, cmd.relative))
                    moveTo(ax(cmd.x, cmd.relative), ay(cmd.y, cmd.relative))
                }
                is PathCommand.SmoothQuadTo -> moveTo(ax(cmd.x, cmd.relative), ay(cmd.y, cmd.relative))
                is PathCommand.ArcTo -> moveTo(ax(cmd.x, cmd.relative), ay(cmd.y, cmd.relative))
                is PathCommand.Close -> { cx = startX; cy = startY }
            }
        }
        return if (saw) VectorBounds(minX, minY, maxX, maxY) else null
    }
}
