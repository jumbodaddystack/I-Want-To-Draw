package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.PathDataFormatter
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorPathSimplifier
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * Phase 5 (sub-feature 5a) — the deterministic local bitmap tracer.
 *
 * Pure JVM pipeline: threshold → binary mask → connected components →
 * (OUTLINE) Moore boundary tracing of each region, or (CENTERLINE) Zhang–Suen
 * thinning then skeleton walk → RDP simplification ([VectorPathSimplifier]) →
 * least-squares cubic fit ([CurveFitter]) → editable [VectorPath]s. Always
 * available, so a trace never hard-fails; the semantic AI backend falls back here.
 *
 * Coordinates map 1 pixel → 1 viewport unit, so the result drops straight into
 * the Phase 1/4 editable model.
 */
class LocalBitmapTracer : BitmapTracer {

    private val fillStyle = VectorStyle(fillColor = "#000000")
    private val strokeStyle = VectorStyle(strokeColor = "#000000", strokeWidth = 1f, strokeLineCap = "round")

    override suspend fun trace(pixels: IntArray, width: Int, height: Int, options: TraceOptions): TraceResult {
        val viewport = VectorViewport(width.toFloat(), height.toFloat(), width.toFloat(), height.toFloat())
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return TraceResult(emptyList(), viewport, emptyEmptyWarning())
        }
        val mask = threshold(pixels, width, height, options.threshold)
        if (mask.none { it }) {
            return TraceResult(emptyList(), viewport, emptyEmptyWarning())
        }
        val paths = when (options.mode) {
            TraceMode.OUTLINE -> traceOutlines(mask, width, height, options)
            TraceMode.CENTERLINE -> traceCenterlines(mask, width, height, options)
        }
        return if (paths.isEmpty()) {
            TraceResult(emptyList(), viewport, emptyEmptyWarning())
        } else {
            TraceResult(paths, viewport)
        }
    }

    private fun emptyEmptyWarning() = listOf(
        VectorWarning(VectorWarning.Codes.TRACE_EMPTY, "No traceable foreground regions were found."),
    )

    // ---- thresholding ----

    private fun threshold(pixels: IntArray, w: Int, h: Int, cutoff: Int): BooleanArray {
        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b)
            // Foreground = sufficiently opaque AND darker than the cutoff.
            mask[i] = a >= 128 && lum < cutoff
        }
        return mask
    }

    // ---- OUTLINE: connected components + Moore boundary tracing ----

    private fun traceOutlines(mask: BooleanArray, w: Int, h: Int, options: TraceOptions): List<VectorPath> {
        val labels = labelComponents(mask, w, h)
        val starts = HashMap<Int, Pair<Int, Int>>()
        val sizes = HashMap<Int, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val l = labels[y * w + x]
            if (l == 0) continue
            if (l !in starts) starts[l] = x to y
            sizes[l] = (sizes[l] ?: 0) + 1
        }
        val out = ArrayList<VectorPath>()
        var index = 0
        for ((label, start) in starts) {
            // A boundary can revisit pixels; 8× the component size is a safe cap.
            val maxSteps = 8 * (sizes[label] ?: (w * h)) + 16
            val contour = mooreTrace(start.first, start.second, maxSteps) { x, y ->
                x in 0 until w && y in 0 until h && labels[y * w + x] == label
            }
            if (contour.size < 3) continue
            val points = contour.map { VectorPoint(it.first.toFloat(), it.second.toFloat()) }
            val simplified = VectorPathSimplifier.simplify(points, options.simplifyTolerance)
            if (simplified.size < 3) continue
            val closedRun = simplified + simplified.first()
            val cmds = ArrayList<PathCommand>()
            cmds += PathCommand.MoveTo(simplified.first().x, simplified.first().y)
            cmds += CurveFitter.fit(closedRun, options.maxError)
            cmds += PathCommand.Close()
            out += pathOf("trace_${index++}", cmds, fillStyle)
        }
        return out
    }

    /** 4-connected component labeling. Returns label ids (0 = background). */
    private fun labelComponents(mask: BooleanArray, w: Int, h: Int): IntArray {
        val labels = IntArray(w * h)
        var next = 0
        val queue = ArrayDeque<Int>()
        for (s in 0 until w * h) {
            if (!mask[s] || labels[s] != 0) continue
            next++
            labels[s] = next
            queue.addLast(s)
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val x = idx % w
                val y = idx / w
                for ((dx, dy) in NEIGHBORS_4) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val ni = ny * w + nx
                        if (mask[ni] && labels[ni] == 0) {
                            labels[ni] = next
                            queue.addLast(ni)
                        }
                    }
                }
            }
        }
        return labels
    }

    /** Moore-neighbor boundary trace (clockwise) starting at a top-left foreground pixel. */
    private fun mooreTrace(sx: Int, sy: Int, maxSteps: Int, fg: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
        val contour = ArrayList<Pair<Int, Int>>()
        val start = sx to sy
        contour += start
        var current = start
        var prev = (sx - 1) to sy // we entered the start from the west (background)
        var steps = 0
        while (steps++ < maxSteps) {
            val nb = MOORE.map { (current.first + it.first) to (current.second + it.second) }
            val prevIdx = nb.indexOf(prev).let { if (it < 0) 0 else it }
            var found = false
            for (k in 1..8) {
                val candIdx = (prevIdx + k) % 8
                val cand = nb[candIdx]
                if (fg(cand.first, cand.second)) {
                    prev = nb[(candIdx + 7) % 8]
                    current = cand
                    found = true
                    break
                }
            }
            if (!found) break
            if (current == start) break
            contour += current
        }
        return contour
    }

    // ---- CENTERLINE: Zhang–Suen thinning + skeleton walk ----

    private fun traceCenterlines(mask: BooleanArray, w: Int, h: Int, options: TraceOptions): List<VectorPath> {
        val skeleton = zhangSuen(mask.copyOf(), w, h)
        val visited = BooleanArray(w * h)
        val out = ArrayList<VectorPath>()
        var index = 0

        fun degree(x: Int, y: Int): Int {
            var d = 0
            for ((dx, dy) in NEIGHBORS_8) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && skeleton[ny * w + nx]) d++
            }
            return d
        }

        // Prefer starting at endpoints (degree 1); then any remaining pixel.
        val starts = ArrayList<Int>()
        for (i in 0 until w * h) if (skeleton[i] && degree(i % w, i / w) == 1) starts += i
        for (i in 0 until w * h) if (skeleton[i]) starts += i

        for (s in starts) {
            if (visited[s]) continue
            val poly = walkSkeleton(skeleton, visited, w, h, s)
            if (poly.size < 2) continue
            val points = poly.map { VectorPoint(it.first.toFloat(), it.second.toFloat()) }
            val simplified = VectorPathSimplifier.simplify(points, options.simplifyTolerance)
            val use = if (simplified.size >= 2) simplified else points
            val cmds = ArrayList<PathCommand>()
            cmds += PathCommand.MoveTo(use.first().x, use.first().y)
            cmds += CurveFitter.fit(use, options.maxError)
            out += pathOf("trace_${index++}", cmds, strokeStyle)
        }
        return out
    }

    /** Walk an ordered run of skeleton pixels from [start], greedily following neighbors. */
    private fun walkSkeleton(skel: BooleanArray, visited: BooleanArray, w: Int, h: Int, start: Int): List<Pair<Int, Int>> {
        val path = ArrayList<Pair<Int, Int>>()
        var cur = start
        while (true) {
            visited[cur] = true
            val x = cur % w; val y = cur / w
            path += x to y
            var nextIdx = -1
            for ((dx, dy) in NEIGHBORS_8) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) {
                    val ni = ny * w + nx
                    if (skel[ni] && !visited[ni]) { nextIdx = ni; break }
                }
            }
            if (nextIdx < 0) break
            cur = nextIdx
        }
        return path
    }

    /** Zhang–Suen thinning to a 1-pixel-wide skeleton. */
    private fun zhangSuen(img: BooleanArray, w: Int, h: Int): BooleanArray {
        fun at(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) img[y * w + x] else false
        var changed = true
        val toClear = ArrayList<Int>()
        while (changed) {
            changed = false
            for (step in 0..1) {
                toClear.clear()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    if (!img[y * w + x]) continue
                    val p2 = at(x, y - 1); val p3 = at(x + 1, y - 1); val p4 = at(x + 1, y)
                    val p5 = at(x + 1, y + 1); val p6 = at(x, y + 1); val p7 = at(x - 1, y + 1)
                    val p8 = at(x - 1, y); val p9 = at(x - 1, y - 1)
                    val ring = booleanArrayOf(p2, p3, p4, p5, p6, p7, p8, p9)
                    val bp = ring.count { it }
                    if (bp < 2 || bp > 6) continue
                    var ap = 0
                    for (i in 0 until 8) if (!ring[i] && ring[(i + 1) % 8]) ap++
                    if (ap != 1) continue
                    if (step == 0) {
                        if (p2 && p4 && p6) continue
                        if (p4 && p6 && p8) continue
                    } else {
                        if (p2 && p4 && p8) continue
                        if (p2 && p6 && p8) continue
                    }
                    toClear += y * w + x
                }
                if (toClear.isNotEmpty()) {
                    changed = true
                    for (i in toClear) img[i] = false
                }
            }
        }
        return img
    }

    private fun pathOf(id: String, cmds: List<PathCommand>, style: VectorStyle): VectorPath =
        VectorPath(id = id, pathData = PathDataFormatter.format(cmds), commands = cmds, style = style)

    private companion object {
        val NEIGHBORS_4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val NEIGHBORS_8 = listOf(
            -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
        )

        // Moore neighborhood in clockwise order (y-down) starting from West.
        val MOORE = listOf(
            -1 to 0, -1 to -1, 0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1,
        )
    }
}
