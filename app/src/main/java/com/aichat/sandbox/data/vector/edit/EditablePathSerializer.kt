package com.aichat.sandbox.data.vector.edit

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.PathDataFormatter
import com.aichat.sandbox.data.vector.VectorPath

/**
 * Phase 1 — the inverse of [EditablePathFactory]: turns an [EditablePath] back
 * into [PathCommand]s and a [VectorPath].
 *
 * Output is always absolute `M`/`L`/`C`/`Z`. A segment with no handles on either
 * side becomes a [PathCommand.LineTo]; any handle present makes it a
 * [PathCommand.CubicTo] (a missing control point falls back to its own anchor, the
 * standard degenerate-cubic convention). A closed subpath whose closing segment is
 * curved emits an explicit `C` back to the start anchor *before* `Z`, because `Z`
 * only draws a straight line.
 *
 * The `pathData` string is produced by the existing [PathDataFormatter], so the
 * result re-parses through [com.aichat.sandbox.data.vector.PathDataParser]
 * identically and exports through the existing writers unchanged.
 */
object EditablePathSerializer {

    /** Build the flat command list for [path] (absolute M/L/C/Z). */
    fun toCommands(path: EditablePath): List<PathCommand> {
        val out = ArrayList<PathCommand>()
        for (sp in path.subpaths) {
            val anchors = sp.anchors
            if (anchors.isEmpty()) continue
            val first = anchors.first()
            out += PathCommand.MoveTo(first.x, first.y)

            for (i in 0 until anchors.size - 1) {
                out += segment(anchors[i], anchors[i + 1])
            }

            if (sp.closed) {
                val last = anchors.last()
                // Emit the curved closing segment explicitly; a straight close is
                // left to Z. (Skip the degenerate self-segment when size == 1.)
                if (anchors.size >= 2 && (last.outHandle != null || first.inHandle != null)) {
                    out += segment(last, first)
                }
                out += PathCommand.Close()
            }
        }
        return out
    }

    /** Re-serialize [path] into a [VectorPath], preserving id, name, and style. */
    fun toVectorPath(path: EditablePath): VectorPath {
        val commands = toCommands(path)
        return VectorPath(
            id = path.pathId,
            name = path.name,
            pathData = PathDataFormatter.format(commands),
            commands = commands,
            style = path.style,
        )
    }

    private fun segment(from: EditAnchor, to: EditAnchor): PathCommand {
        val out = from.outHandle
        val inc = to.inHandle
        return if (out == null && inc == null) {
            PathCommand.LineTo(to.x, to.y)
        } else {
            val c1 = out ?: ControlPoint(from.x, from.y)
            val c2 = inc ?: ControlPoint(to.x, to.y)
            PathCommand.CubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y)
        }
    }
}
