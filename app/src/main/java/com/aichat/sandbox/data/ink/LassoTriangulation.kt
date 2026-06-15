package com.aichat.sandbox.data.ink

/**
 * Phase **I6 — mesh-backed geometry adoption**: turns a lasso loop into the
 * triangles ink's geometry core can intersect against a stroke's
 * `PartitionedMesh`.
 *
 * The migration plan notes "ink already ships **lasso → mesh** conversion", but
 * the *stable 1.0.0* `ink-geometry` artifact on our classpath exposes no public
 * polygon → `PartitionedMesh` builder — only primitive intersection
 * (`Intersection.intersects(mesh, triangle, …)`, `(mesh, box, …)`, etc.). So the
 * lasso path bridges through triangles: decompose the loop into a triangle fan
 * by **ear clipping** (handles the concave loops a freehand lasso produces), then
 * a stroke is selected when its mesh intersects *any* lasso triangle. Because the
 * triangles tile the whole loop interior, this catches both a stroke the lasso
 * encloses **and** a stroke whose body merely crosses the loop — the
 * "lasso of overlapping strokes" accuracy case the point-in-polygon sample loop
 * misses (it only tests the stroke's sample points, so a long stroke passing
 * through a small loop with all samples outside is wrongly skipped).
 *
 * Pure and Android/ink-free so it is unit-testable on the JVM.
 */
object LassoTriangulation {

    /** Two floats per polygon vertex, matching `LassoController.FLOATS_PER_VERTEX`. */
    private const val STRIDE = 2

    /** Six floats per emitted triangle: `ax,ay, bx,by, cx,cy`. */
    const val FLOATS_PER_TRIANGLE = 6

    /**
     * Ear-clip [polygon] (`[x0,y0,x1,y1,…]`, [vertexCount] vertices, implicitly
     * closed) into a flat `[ax,ay,bx,by,cx,cy, …]` triangle array. Returns an
     * empty array for a degenerate loop (`< 3` vertices or zero area), so callers
     * can fall back to the sample-in-polygon path.
     */
    fun triangulate(polygon: FloatArray, vertexCount: Int): FloatArray {
        if (vertexCount < 3) return FloatArray(0)

        // Working ring of vertex indices, normalised to counter-clockwise so the
        // convex / ear tests below use a single sign convention.
        val n = vertexCount
        val indices = IntArray(n) { it }
        if (signedArea(polygon, indices, n) < 0f) {
            // Reverse in place to make it CCW.
            var i = 0
            var j = n - 1
            while (i < j) {
                val t = indices[i]; indices[i] = indices[j]; indices[j] = t
                i++; j--
            }
        }

        val out = ArrayList<Float>((n - 2) * FLOATS_PER_TRIANGLE)
        val ring = indices.toMutableList()
        var guard = 0
        val guardLimit = 2 * n * n + 16 // generous bound against pathological loops
        while (ring.size > 3 && guard++ < guardLimit) {
            var earFound = false
            val m = ring.size
            for (k in 0 until m) {
                val iPrev = ring[(k - 1 + m) % m]
                val iCur = ring[k]
                val iNext = ring[(k + 1) % m]
                if (isEar(polygon, ring, iPrev, iCur, iNext)) {
                    emit(out, polygon, iPrev, iCur, iNext)
                    ring.removeAt(k)
                    earFound = true
                    break
                }
            }
            if (!earFound) break // numerically stuck — stop with what we have
        }
        if (ring.size == 3) emit(out, polygon, ring[0], ring[1], ring[2])

        val arr = FloatArray(out.size)
        for (i in out.indices) arr[i] = out[i]
        return arr
    }

    private fun emit(out: ArrayList<Float>, p: FloatArray, a: Int, b: Int, c: Int) {
        out.add(p[a * STRIDE]); out.add(p[a * STRIDE + 1])
        out.add(p[b * STRIDE]); out.add(p[b * STRIDE + 1])
        out.add(p[c * STRIDE]); out.add(p[c * STRIDE + 1])
    }

    /**
     * Twice the signed shoelace area; positive for a counter-clockwise ring (in a
     * y-up convention). Triangulation is purely combinatorial, so the convention
     * just needs to be **consistent** with the convex / ear test below — it is:
     * after normalising to a positive ring here, a convex vertex has a positive
     * cross product in [isEar].
     */
    private fun signedArea(p: FloatArray, indices: IntArray, n: Int): Float {
        var s = 0f
        for (i in 0 until n) {
            val a = indices[i]
            val b = indices[(i + 1) % n]
            s += p[a * STRIDE] * p[b * STRIDE + 1] - p[b * STRIDE] * p[a * STRIDE + 1]
        }
        return s
    }

    /**
     * A vertex forms an ear when it is convex (CCW turn) and no other ring vertex
     * lies inside the candidate triangle.
     */
    private fun isEar(p: FloatArray, ring: List<Int>, a: Int, b: Int, c: Int): Boolean {
        val ax = p[a * STRIDE]; val ay = p[a * STRIDE + 1]
        val bx = p[b * STRIDE]; val by = p[b * STRIDE + 1]
        val cx = p[c * STRIDE]; val cy = p[c * STRIDE + 1]
        // Convex in CCW ring: cross of (a→b)×(b→c) >= 0.
        val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        if (cross <= 0f) return false
        for (idx in ring) {
            if (idx == a || idx == b || idx == c) continue
            val px = p[idx * STRIDE]; val py = p[idx * STRIDE + 1]
            if (pointInTriangle(px, py, ax, ay, bx, by, cx, cy)) return false
        }
        return true
    }

    private fun pointInTriangle(
        px: Float, py: Float,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
    ): Boolean {
        val d1 = sign(px, py, ax, ay, bx, by)
        val d2 = sign(px, py, bx, by, cx, cy)
        val d3 = sign(px, py, cx, cy, ax, ay)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

    private fun sign(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        (px - bx) * (ay - by) - (ax - bx) * (py - by)
}
