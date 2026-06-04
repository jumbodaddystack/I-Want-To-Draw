package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import kotlin.math.hypot

/**
 * Phase 1 (step 1b) — pure hit-testing for the node editor.
 *
 * The canvas (step 1d) translates a screen-space touch into a **world** point and
 * a **world-space tolerance** (screen px ÷ `ViewportController.scale`, exactly the
 * trick `VectorPreviewCanvas` uses for constant-width highlights) and asks this
 * object what sits under the finger. Everything here is straight geometry over the
 * Phase 0 [EditablePath] model — no Compose/Android imports — so it is unit-tested
 * on the JVM like the rest of the edit core.
 *
 * The results feed the reducer directly:
 *  - [Hit.Anchor] → `SelectAnchor(anchorId, …)`;
 *  - [Hit.Handle] → handle drag (a handle-aware ref the reducer's direct-select
 *    handle editing needs — see PROGRESS watch-outs);
 *  - [Hit.Segment] → `InsertAnchorOnSegment(subpathId, segmentIndex, t)`. The
 *    `(segmentIndex, t)` it returns matches the reducer's split convention,
 *    including the closing-segment wrap for a closed subpath.
 */
object EditHitTest {

    /** Which cubic control handle of an anchor was hit. */
    enum class HandleSide { IN, OUT }

    /** What lies under a query point. Ordered by the editor's pick priority. */
    sealed interface Hit {
        /** An on-path node knob. */
        data class Anchor(val subpathId: String, val anchorId: String) : Hit

        /** A cubic control-handle knob of [anchorId]'s [side]. */
        data class Handle(
            val subpathId: String,
            val anchorId: String,
            val side: HandleSide,
        ) : Hit

        /**
         * A point on the curve between two anchors. [segmentIndex] + [t] are the
         * de Casteljau split coordinates the reducer's `InsertAnchorOnSegment`
         * expects; [x]/[y] is the world point on the curve at [t].
         */
        data class Segment(
            val subpathId: String,
            val segmentIndex: Int,
            val t: Float,
            val x: Float,
            val y: Float,
        ) : Hit
    }

    /** Convert a screen-pixel [screenTolerance] to world units for the given [scale]. */
    fun worldTolerance(screenTolerance: Float, scale: Float): Float =
        if (scale > 0f) screenTolerance / scale else screenTolerance

    /**
     * The single entry point the gestures use: pick whatever the user most likely
     * meant at (`wx`, `wy`), within world [tolerance].
     *
     * Priority mirrors what's painted on top: handles of [handleCandidates] anchors
     * (drawn over everything, so they win), then any anchor, then the nearest
     * segment (for inserting a point). [handleCandidates] defaults to the current
     * [selection] because handles are only shown — and therefore only grabbable —
     * for selected nodes.
     */
    fun hitTest(
        path: EditablePath,
        wx: Float,
        wy: Float,
        tolerance: Float,
        selection: Selection = Selection(),
        handleCandidates: Set<String> = selection.anchorIds,
    ): Hit? =
        hitHandle(path, wx, wy, tolerance, handleCandidates)
            ?: hitAnchor(path, wx, wy, tolerance)
            ?: hitSegment(path, wx, wy, tolerance)

    /** Nearest anchor within [tolerance], or null. Ties resolve to the closest. */
    fun hitAnchor(path: EditablePath, wx: Float, wy: Float, tolerance: Float): Hit.Anchor? {
        var best: Hit.Anchor? = null
        var bestDist = tolerance
        path.subpaths.forEach { sp ->
            sp.anchors.forEach { a ->
                val d = hypot(a.x - wx, a.y - wy)
                if (d <= bestDist) {
                    bestDist = d
                    best = Hit.Anchor(sp.id, a.id)
                }
            }
        }
        return best
    }

    /**
     * Nearest control handle within [tolerance], restricted to anchors in
     * [candidates] (handles are only visible/grabbable for those). A null-side
     * handle (a straight corner) has no knob to hit and is skipped.
     */
    fun hitHandle(
        path: EditablePath,
        wx: Float,
        wy: Float,
        tolerance: Float,
        candidates: Set<String>,
    ): Hit.Handle? {
        if (candidates.isEmpty()) return null
        var best: Hit.Handle? = null
        var bestDist = tolerance
        path.subpaths.forEach { sp ->
            sp.anchors.forEach anchors@{ a ->
                if (a.id !in candidates) return@anchors
                a.inHandle?.let { h ->
                    val d = hypot(h.x - wx, h.y - wy)
                    if (d <= bestDist) {
                        bestDist = d
                        best = Hit.Handle(sp.id, a.id, HandleSide.IN)
                    }
                }
                a.outHandle?.let { h ->
                    val d = hypot(h.x - wx, h.y - wy)
                    if (d <= bestDist) {
                        bestDist = d
                        best = Hit.Handle(sp.id, a.id, HandleSide.OUT)
                    }
                }
            }
        }
        return best
    }

    /**
     * Nearest segment within [tolerance], as the split coordinates needed to insert
     * a node there. Walks every drawable segment (including the closing segment of a
     * closed subpath, whose index wraps to the start anchor) and keeps the closest
     * point across all of them.
     */
    fun hitSegment(path: EditablePath, wx: Float, wy: Float, tolerance: Float): Hit.Segment? {
        var best: Hit.Segment? = null
        var bestDist = tolerance
        path.subpaths.forEach { sp ->
            forEachSegment(sp) { segmentIndex, a, b ->
                val near = nearestOnSegment(a, b, wx, wy)
                if (near.dist <= bestDist) {
                    bestDist = near.dist
                    best = Hit.Segment(sp.id, segmentIndex, near.t, near.x, near.y)
                }
            }
        }
        return best
    }

    // ---- segment iteration (matches the reducer's index convention) ----

    /**
     * Invoke [block] for each segment of [sp]: `segmentIndex` is the from-anchor's
     * index; the to-anchor is the next one, or the start anchor when this is a
     * closed subpath's closing segment (`segmentIndex == n-1`). Mirrors
     * `VectorEditReducer.insertAnchor` so a [Hit.Segment] feeds straight back in.
     */
    private inline fun forEachSegment(sp: EditSubpath, block: (Int, EditAnchor, EditAnchor) -> Unit) {
        val n = sp.anchors.size
        if (n < 2) return
        val lastSpan = if (sp.closed) n - 1 else n - 2
        for (i in 0..lastSpan) {
            val b = if (sp.closed && i == n - 1) sp.anchors[0] else sp.anchors[i + 1]
            block(i, sp.anchors[i], b)
        }
    }

    // ---- nearest-point-on-segment geometry ----

    private data class Near(val t: Float, val x: Float, val y: Float, val dist: Float)

    /**
     * Closest point on the segment `a → b` to (`px`, `py`). A handleless segment is
     * an exact line projection; a cubic is sampled coarsely then refined by a small
     * local search around the best sample (cheap, robust, and accurate to well
     * within touch tolerance). Control points fall back to their own anchor when a
     * handle is null, matching the serializer / `splitSegment`.
     */
    private fun nearestOnSegment(a: EditAnchor, b: EditAnchor, px: Float, py: Float): Near {
        val out = a.outHandle
        val inc = b.inHandle
        if (out == null && inc == null) {
            return nearestOnLine(a.x, a.y, b.x, b.y, px, py)
        }
        val p1x = out?.x ?: a.x; val p1y = out?.y ?: a.y
        val p2x = inc?.x ?: b.x; val p2y = inc?.y ?: b.y

        var bestT = 0f
        var bestX = a.x
        var bestY = a.y
        var bestDist = Float.MAX_VALUE
        for (i in 0..SAMPLES) {
            val t = i.toFloat() / SAMPLES
            val (cx, cy) = cubicAt(a.x, a.y, p1x, p1y, p2x, p2y, b.x, b.y, t)
            val d = hypot(cx - px, cy - py)
            if (d < bestDist) {
                bestDist = d; bestT = t; bestX = cx; bestY = cy
            }
        }
        // Refine within ±one sample step via a short ternary-ish bisection.
        var lo = (bestT - 1f / SAMPLES).coerceIn(0f, 1f)
        var hi = (bestT + 1f / SAMPLES).coerceIn(0f, 1f)
        repeat(REFINE_STEPS) {
            val m1 = lo + (hi - lo) / 3f
            val m2 = hi - (hi - lo) / 3f
            val (x1, y1) = cubicAt(a.x, a.y, p1x, p1y, p2x, p2y, b.x, b.y, m1)
            val (x2, y2) = cubicAt(a.x, a.y, p1x, p1y, p2x, p2y, b.x, b.y, m2)
            if (hypot(x1 - px, y1 - py) < hypot(x2 - px, y2 - py)) hi = m2 else lo = m1
        }
        val t = (lo + hi) / 2f
        val (cx, cy) = cubicAt(a.x, a.y, p1x, p1y, p2x, p2y, b.x, b.y, t)
        val d = hypot(cx - px, cy - py)
        if (d < bestDist) return Near(t, cx, cy, d)
        return Near(bestT, bestX, bestY, bestDist)
    }

    private fun nearestOnLine(
        ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float,
    ): Near {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= EPS) 0f else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        val x = ax + dx * t
        val y = ay + dy * t
        return Near(t, x, y, hypot(x - px, y - py))
    }

    private fun cubicAt(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float, t: Float,
    ): Pair<Float, Float> {
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        return (a * x0 + b * x1 + c * x2 + d * x3) to (a * y0 + b * y1 + c * y2 + d * y3)
    }

    /** Default touch radius (screen px) the UI divides by `viewport.scale`. */
    const val DEFAULT_TOLERANCE_PX = 22f

    private const val SAMPLES = 24
    private const val REFINE_STEPS = 24
    private const val EPS = 1e-6f
}
