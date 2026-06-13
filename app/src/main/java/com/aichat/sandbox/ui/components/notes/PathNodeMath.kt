package com.aichat.sandbox.ui.components.notes

import kotlin.math.hypot

/**
 * Sub-phase 12.3 — pure node-edit operations over [PathCodec.PathPayload].
 * Phase 17.2 generalizes them from flat anchor indices to `(subpath,
 * anchor)` so boolean results and imported icons (multi-subpath payloads
 * with holes) are node-editable too.
 *
 * Every function takes a trailing `subpath` index (default 0, so the
 * single-subpath call sites and their tests are unchanged) and returns a
 * fresh payload that preserves all other subpaths and every style field.
 * The node-edit overlay previews the result live and the ViewModel commits
 * one `CompositeEdit` per gesture. JVM-pure so the geometry is unit-testable.
 */
object PathNodeMath {

    /** Result of [nearestOnPath]: the closest curve point to a query. */
    data class Nearest(
        val subpath: Int,
        val segment: Int,
        val t: Float,
        val x: Float,
        val y: Float,
        val distance: Float,
    )

    /**
     * Closest point on the path to (`x`, `y`), scanning **every** subpath:
     * coarse uniform-t scan per segment, then a local refinement pass around
     * the best sample. Null when no subpath has a drawable segment.
     */
    fun nearestOnPath(payload: PathCodec.PathPayload, x: Float, y: Float): Nearest? {
        var best: Nearest? = null
        for ((si, sub) in payload.subpaths.withIndex()) {
            for (i in 0 until sub.segmentCount) {
                val s = PathCodec.segment(sub, i)
                for (step in 0..COARSE_STEPS) {
                    val t = step.toFloat() / COARSE_STEPS
                    val px = PathCodec.cubicAt(s[0], s[2], s[4], s[6], t)
                    val py = PathCodec.cubicAt(s[1], s[3], s[5], s[7], t)
                    val d = hypot(px - x, py - y)
                    val current = best
                    if (current == null || d < current.distance) {
                        best = Nearest(si, i, t, px, py, d)
                    }
                }
            }
        }
        val coarse = best ?: return null
        // Refine ± one coarse step at fine resolution within the best segment.
        val s = PathCodec.segment(payload.subpaths[coarse.subpath], coarse.segment)
        val span = 1f / COARSE_STEPS
        var refined = coarse
        for (step in 0..FINE_STEPS) {
            val t = (coarse.t - span + 2f * span * step / FINE_STEPS).coerceIn(0f, 1f)
            val px = PathCodec.cubicAt(s[0], s[2], s[4], s[6], t)
            val py = PathCodec.cubicAt(s[1], s[3], s[5], s[7], t)
            val d = hypot(px - x, py - y)
            if (d < refined.distance) {
                refined = Nearest(coarse.subpath, coarse.segment, t, px, py, d)
            }
        }
        return refined
    }

    /**
     * Insert an anchor at parameter [t] of [segment] within [subpath] via a
     * de Casteljau split — the curve's geometry is preserved exactly. The
     * new anchor lands as SMOOTH (its handles came off one curve, so they're
     * collinear by construction).
     */
    fun insertAnchor(
        payload: PathCodec.PathPayload,
        segment: Int,
        t: Float,
        subpath: Int = 0,
    ): PathCodec.PathPayload {
        val sub = payload.subpaths[subpath]
        val n = sub.anchors.size
        require(segment in 0 until sub.segmentCount) { "segment $segment out of range" }
        val s = PathCodec.segment(sub, segment)
        // de Casteljau at t.
        fun lerp(a: Float, b: Float) = a + (b - a) * t
        val q0x = lerp(s[0], s[2]); val q0y = lerp(s[1], s[3])
        val q1x = lerp(s[2], s[4]); val q1y = lerp(s[3], s[5])
        val q2x = lerp(s[4], s[6]); val q2y = lerp(s[5], s[7])
        val r0x = lerp(q0x, q1x); val r0y = lerp(q0y, q1y)
        val r1x = lerp(q1x, q2x); val r1y = lerp(q1y, q2y)
        val px = lerp(r0x, r1x); val py = lerp(r0y, r1y)

        val from = segment
        val to = (segment + 1) % n
        val anchors = sub.anchors.toMutableList()
        // First sub-curve: from-anchor keeps its position; out handle shrinks.
        anchors[from] = anchors[from].copy(outDx = q0x - anchors[from].x, outDy = q0y - anchors[from].y)
        anchors[to] = anchors[to].copy(inDx = q2x - anchors[to].x, inDy = q2y - anchors[to].y)
        val inserted = PathCodec.Anchor(
            x = px, y = py,
            inDx = r0x - px, inDy = r0y - py,
            outDx = r1x - px, outDy = r1y - py,
            type = PathCodec.TYPE_SMOOTH,
        )
        anchors.add(segment + 1, inserted)
        return payload.withSubpath(subpath, anchors)
    }

    /**
     * Remove anchor [index] from [subpath]. When the subpath would drop
     * below 2 anchors (no longer drawable) the whole subpath is removed
     * instead; when that empties the payload, returns null to signal the
     * caller should delete the item. For single-subpath payloads this keeps
     * the pre-17.2 "floors at 2 anchors → null" behaviour exactly.
     */
    fun deleteAnchor(
        payload: PathCodec.PathPayload,
        index: Int,
        subpath: Int = 0,
    ): PathCodec.PathPayload? {
        val sub = payload.subpaths[subpath]
        if (sub.anchors.size <= 2) {
            val remaining = payload.subpaths.toMutableList().also { it.removeAt(subpath) }
            return if (remaining.isEmpty()) null else payload.copy(subpaths = remaining)
        }
        val anchors = sub.anchors.toMutableList()
        anchors.removeAt(index)
        return payload.withSubpath(subpath, anchors)
    }

    /**
     * Corner ⇄ smooth toggle on anchor [index] of [subpath]. Corner → smooth
     * aligns the handles into a single tangent (averaged from whatever
     * handles exist, falling back to the neighbour chord) with one-third-chord
     * lengths when a side had no handle. Smooth / symmetric → corner keeps the
     * handles as-is — corner just means the sides move independently from here.
     */
    fun toggleType(
        payload: PathCodec.PathPayload,
        index: Int,
        subpath: Int = 0,
    ): PathCodec.PathPayload {
        val sub = payload.subpaths[subpath]
        val anchors = sub.anchors.toMutableList()
        val a = anchors[index]
        anchors[index] = if (a.type == PathCodec.TYPE_CORNER) {
            val (tx, ty) = smoothTangent(sub, index)
            if (tx == 0f && ty == 0f) {
                a.copy(type = PathCodec.TYPE_SMOOTH)
            } else {
                val inLen = handleLengthOrDefault(sub, index, incoming = true)
                val outLen = handleLengthOrDefault(sub, index, incoming = false)
                a.copy(
                    inDx = -tx * inLen, inDy = -ty * inLen,
                    outDx = tx * outLen, outDy = ty * outLen,
                    type = PathCodec.TYPE_SMOOTH,
                )
            }
        } else {
            a.copy(type = PathCodec.TYPE_CORNER)
        }
        return payload.withSubpath(subpath, anchors)
    }

    /** Move anchor [index] of [subpath] to (`x`, `y`); relative handles ride along. */
    fun moveAnchor(
        payload: PathCodec.PathPayload,
        index: Int,
        x: Float,
        y: Float,
        subpath: Int = 0,
    ): PathCodec.PathPayload {
        val anchors = payload.subpaths[subpath].anchors.toMutableList()
        anchors[index] = anchors[index].copy(x = x, y = y)
        return payload.withSubpath(subpath, anchors)
    }

    /**
     * Retarget one handle of anchor [index] in [subpath] to the absolute
     * point (`x`, `y`). SYMMETRIC mirrors direction + length onto the other
     * side; SMOOTH mirrors direction but keeps the other side's length;
     * CORNER moves the dragged side only.
     */
    fun moveHandle(
        payload: PathCodec.PathPayload,
        index: Int,
        out: Boolean,
        x: Float,
        y: Float,
        subpath: Int = 0,
    ): PathCodec.PathPayload {
        val anchors = payload.subpaths[subpath].anchors.toMutableList()
        val a = anchors[index]
        val dx = x - a.x
        val dy = y - a.y
        val len = hypot(dx, dy)
        val nx = if (len < 1e-6f) 0f else dx / len
        val ny = if (len < 1e-6f) 0f else dy / len
        anchors[index] = when (a.type) {
            PathCodec.TYPE_SYMMETRIC -> if (out) {
                a.copy(outDx = dx, outDy = dy, inDx = -dx, inDy = -dy)
            } else {
                a.copy(inDx = dx, inDy = dy, outDx = -dx, outDy = -dy)
            }
            PathCodec.TYPE_SMOOTH -> if (out) {
                val other = hypot(a.inDx, a.inDy)
                a.copy(outDx = dx, outDy = dy, inDx = -nx * other, inDy = -ny * other)
            } else {
                val other = hypot(a.outDx, a.outDy)
                a.copy(inDx = dx, inDy = dy, outDx = -nx * other, outDy = -ny * other)
            }
            else -> if (out) a.copy(outDx = dx, outDy = dy) else a.copy(inDx = dx, inDy = dy)
        }
        return payload.withSubpath(subpath, anchors)
    }

    /** Unit tangent for a corner→smooth toggle: averaged handles, else chord. */
    private fun smoothTangent(sub: PathCodec.Subpath, index: Int): Pair<Float, Float> {
        val a = sub.anchors[index]
        var tx = a.outDx - a.inDx
        var ty = a.outDy - a.inDy
        if (tx == 0f && ty == 0f) {
            val prev = neighbour(sub, index, -1)
            val next = neighbour(sub, index, +1)
            if (prev != null && next != null) {
                tx = next.x - prev.x
                ty = next.y - prev.y
            } else if (next != null) {
                tx = next.x - a.x; ty = next.y - a.y
            } else if (prev != null) {
                tx = a.x - prev.x; ty = a.y - prev.y
            }
        }
        val len = hypot(tx, ty)
        return if (len < 1e-6f) 0f to 0f else (tx / len) to (ty / len)
    }

    private fun handleLengthOrDefault(
        sub: PathCodec.Subpath,
        index: Int,
        incoming: Boolean,
    ): Float {
        val a = sub.anchors[index]
        val existing = if (incoming) hypot(a.inDx, a.inDy) else hypot(a.outDx, a.outDy)
        if (existing > 1e-6f) return existing
        val n = neighbour(sub, index, if (incoming) -1 else +1)
        if (n != null) return hypot(n.x - a.x, n.y - a.y) / 3f
        // Endpoint with no neighbour on this side — mirror the other
        // handle's length so the toggle still produces a usable tangent.
        return if (incoming) hypot(a.outDx, a.outDy) else hypot(a.inDx, a.inDy)
    }

    private fun neighbour(sub: PathCodec.Subpath, index: Int, delta: Int): PathCodec.Anchor? {
        val n = sub.anchors.size
        val i = index + delta
        return when {
            i in 0 until n -> sub.anchors[i]
            sub.closed -> sub.anchors[(i + n) % n]
            else -> null
        }
    }

    private const val COARSE_STEPS = 16
    private const val FINE_STEPS = 20
}
