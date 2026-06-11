package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.boolean.PathBoolean

/**
 * Sub-phase 13.1 — boolean ops for notes paths.
 *
 * Bridges [PathCodec.PathPayload]s to the vector tune-up lane's pure
 * flatten → clip → refit pipeline ([PathBoolean] / `PolygonClipper` /
 * `CurveRefit`) and back. The master plan suggested
 * `android.graphics.Path.op()`, but reading the result's geometry back out
 * of a framework `Path` needs API-34 `PathIterator` (or a new androidx
 * dependency) and is untestable on the JVM; the in-repo clipper is proven,
 * pure Kotlin, and already refits rings to smooth/corner cubic anchors.
 *
 * Booleans are area ops: open inputs are implicitly closed by the
 * flattener and every result ring comes back closed. [PathCodec] is
 * single-subpath, so a multi-ring result maps to multiple payloads — the
 * caller lands them as separate (grouped) items. Hole rings are emitted as
 * their own payloads too (documented limitation: they render filled on
 * top rather than punching through).
 */
object PathBooleanBridge {

    /**
     * Combine per-item payload lists under [op]. The first element is the
     * **subject** (Subtract removes every later item from it). Items whose
     * payloads are all degenerate (< 2 anchors) are dropped; fewer than two
     * usable inputs — or an empty result (e.g. a disjoint intersect) —
     * return an empty list. Result payloads carry geometry only (closed
     * rings, default style); the caller stamps fill / stroke style.
     */
    fun combine(
        itemPayloads: List<List<PathCodec.PathPayload>>,
        op: PathBoolean.Op,
    ): List<PathCodec.PathPayload> {
        val paths = itemPayloads.mapIndexedNotNull { i, payloads ->
            val subs = payloads.mapIndexedNotNull { j, p -> toSubpath(p, "in$i.s$j") }
            if (subs.isEmpty()) {
                null
            } else {
                EditablePath(pathId = "in$i", subpaths = subs, style = VectorStyle())
            }
        }
        if (paths.size < 2) return emptyList()
        val result = PathBoolean.combine(paths, op, "bool") ?: return emptyList()
        return result.subpaths
            .filter { it.anchors.size >= 2 }
            .map { fromSubpath(it) }
    }

    /** Relative handle deltas → absolute control points (zero delta = no handle). */
    fun toSubpath(payload: PathCodec.PathPayload, id: String): EditSubpath? {
        if (payload.anchors.size < 2) return null
        val anchors = payload.anchors.mapIndexed { i, a ->
            EditAnchor(
                id = "$id.a$i",
                x = a.x,
                y = a.y,
                inHandle = if (a.inDx != 0f || a.inDy != 0f) {
                    ControlPoint(a.x + a.inDx, a.y + a.inDy)
                } else {
                    null
                },
                outHandle = if (a.outDx != 0f || a.outDy != 0f) {
                    ControlPoint(a.x + a.outDx, a.y + a.outDy)
                } else {
                    null
                },
                type = when (a.type) {
                    PathCodec.TYPE_SMOOTH -> AnchorType.SMOOTH
                    PathCodec.TYPE_SYMMETRIC -> AnchorType.SYMMETRIC
                    else -> AnchorType.CORNER
                },
            )
        }
        return EditSubpath(id = id, anchors = anchors, closed = payload.closed)
    }

    /** Absolute control points → relative handle deltas; boolean rings are closed. */
    fun fromSubpath(sub: EditSubpath): PathCodec.PathPayload = PathCodec.PathPayload(
        anchors = sub.anchors.map { a ->
            PathCodec.Anchor(
                x = a.x,
                y = a.y,
                inDx = (a.inHandle?.x ?: a.x) - a.x,
                inDy = (a.inHandle?.y ?: a.y) - a.y,
                outDx = (a.outHandle?.x ?: a.x) - a.x,
                outDy = (a.outHandle?.y ?: a.y) - a.y,
                type = when (a.type) {
                    AnchorType.SMOOTH -> PathCodec.TYPE_SMOOTH
                    AnchorType.SYMMETRIC -> PathCodec.TYPE_SYMMETRIC
                    else -> PathCodec.TYPE_CORNER
                },
            )
        },
        closed = true,
    )
}
