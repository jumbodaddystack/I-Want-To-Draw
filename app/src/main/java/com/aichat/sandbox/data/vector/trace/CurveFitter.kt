package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorPoint
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Phase 5 (sub-feature 5) — fits a sequence of cubic Béziers to an ordered point
 * list within a maximum error, splitting where the error exceeds tolerance.
 *
 * A classic Schneider least-squares fit (Graphics Gems): parameterize by chord
 * length, solve for the two interior control points under fixed end tangents,
 * Newton-reparameterize a couple of iterations, and recursively subdivide at the
 * worst point when the fit can't meet [maxError]. Output is a list of
 * [PathCommand.CubicTo] whose implicit start is the first input point — the
 * caller prepends the `MoveTo` and (for outlines) appends `Close`. Pure JVM.
 */
object CurveFitter {

    private const val REPARAM_ITERATIONS = 4

    /** Fit [points] with cubics within [maxError]; returns the cubic commands (no leading MoveTo). */
    fun fit(points: List<VectorPoint>, maxError: Float): List<PathCommand> {
        val pts = dedupe(points)
        if (pts.size < 2) return emptyList()
        val err = maxError.toDouble().coerceAtLeast(1e-4)
        val out = ArrayList<PathCommand>()
        if (pts.size == 2) {
            out += lineCubic(pts[0], pts[1])
            return out
        }
        val tHat1 = (pts[1] - pts[0]).normalized()
        val tHat2 = (pts[pts.size - 2] - pts[pts.size - 1]).normalized()
        fitCubic(pts, 0, pts.size - 1, tHat1, tHat2, err, out)
        return out
    }

    private fun fitCubic(
        d: List<V>,
        first: Int,
        last: Int,
        tHat1: V,
        tHat2: V,
        error: Double,
        out: MutableList<PathCommand>,
    ) {
        val nPts = last - first + 1
        if (nPts == 2) {
            val dist = (d[last] - d[first]).len() / 3.0
            val bez = arrayOf(d[first], d[first] + tHat1 * dist, d[last] + tHat2 * dist, d[last])
            emit(bez, out)
            return
        }

        var u = chordLengthParameterize(d, first, last)
        var bez = generateBezier(d, first, last, u, tHat1, tHat2)
        var (maxErr, split) = computeMaxError(d, first, last, bez, u)
        if (maxErr < error) {
            emit(bez, out)
            return
        }

        // Within the reparameterization region: try Newton iterations before splitting.
        if (maxErr < error * error) {
            repeat(REPARAM_ITERATIONS) {
                u = reparameterize(d, first, u, bez)
                bez = generateBezier(d, first, last, u, tHat1, tHat2)
                val r = computeMaxError(d, first, last, bez, u)
                maxErr = r.first
                split = r.second
                if (maxErr < error) {
                    emit(bez, out)
                    return
                }
            }
        }

        // Split at the worst point with a continuous center tangent and recurse.
        val tHatCenter = computeCenterTangent(d, split)
        fitCubic(d, first, split, tHat1, tHatCenter, error, out)
        fitCubic(d, split, last, -tHatCenter, tHat2, error, out)
    }

    private fun emit(bez: Array<V>, out: MutableList<PathCommand>) {
        out += PathCommand.CubicTo(
            bez[1].x.toFloat(), bez[1].y.toFloat(),
            bez[2].x.toFloat(), bez[2].y.toFloat(),
            bez[3].x.toFloat(), bez[3].y.toFloat(),
        )
    }

    private fun lineCubic(a: V, b: V): PathCommand {
        val c1 = a + (b - a) * (1.0 / 3.0)
        val c2 = a + (b - a) * (2.0 / 3.0)
        return PathCommand.CubicTo(
            c1.x.toFloat(), c1.y.toFloat(),
            c2.x.toFloat(), c2.y.toFloat(),
            b.x.toFloat(), b.y.toFloat(),
        )
    }

    private fun generateBezier(d: List<V>, first: Int, last: Int, u: DoubleArray, tHat1: V, tHat2: V): Array<V> {
        val nPts = last - first + 1
        // Precompute the right-hand-side tangent contributions (A matrix).
        val a0 = Array(nPts) { V(0.0, 0.0) }
        val a1 = Array(nPts) { V(0.0, 0.0) }
        for (i in 0 until nPts) {
            a0[i] = tHat1 * b1(u[i])
            a1[i] = tHat2 * b2(u[i])
        }
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0
        var x0 = 0.0; var x1 = 0.0
        for (i in 0 until nPts) {
            c00 += a0[i].dot(a0[i])
            c01 += a0[i].dot(a1[i])
            c11 += a1[i].dot(a1[i])
            val tmp = d[first + i] -
                (d[first] * b0(u[i]) + d[first] * b1(u[i]) + d[last] * b2(u[i]) + d[last] * b3(u[i]))
            x0 += a0[i].dot(tmp)
            x1 += a1[i].dot(tmp)
        }
        val c10 = c01
        val detC0C1 = c00 * c11 - c10 * c01
        val detC0X = c00 * x1 - c10 * x0
        val detXC1 = x0 * c11 - x1 * c01
        var alphaL = if (detC0C1 != 0.0) detXC1 / detC0C1 else 0.0
        var alphaR = if (detC0C1 != 0.0) detC0X / detC0C1 else 0.0

        val segLen = (d[last] - d[first]).len()
        val epsilon = 1e-6 * segLen
        if (alphaL < epsilon || alphaR < epsilon) {
            // Fall back to Wu/Barsky heuristic (1/3 chord) when the fit is degenerate.
            val dist = segLen / 3.0
            alphaL = dist
            alphaR = dist
        }
        return arrayOf(d[first], d[first] + tHat1 * alphaL, d[last] + tHat2 * alphaR, d[last])
    }

    private fun reparameterize(d: List<V>, first: Int, u: DoubleArray, bez: Array<V>): DoubleArray {
        val out = DoubleArray(u.size)
        for (i in u.indices) {
            out[i] = newtonRaphson(bez, d[first + i], u[i])
        }
        return out
    }

    private fun newtonRaphson(bez: Array<V>, point: V, u: Double): Double {
        val d = bezier(bez, u)
        // First and second derivative control points.
        val q1 = Array(3) { (bez[it + 1] - bez[it]) * 3.0 }
        val q2 = Array(2) { (q1[it + 1] - q1[it]) * 2.0 }
        val d1 = bezier3(q1, u)
        val d2 = bezier2(q2, u)
        val diff = d - point
        val numerator = diff.dot(d1)
        val denominator = d1.dot(d1) + diff.dot(d2)
        if (denominator == 0.0) return u
        return u - numerator / denominator
    }

    private fun computeMaxError(d: List<V>, first: Int, last: Int, bez: Array<V>, u: DoubleArray): Pair<Double, Int> {
        var maxDist = 0.0
        var splitPoint = (last - first + 1) / 2
        for (i in 1 until (last - first)) {
            val p = bezier(bez, u[i])
            val dist = (p - d[first + i]).lenSq()
            if (dist >= maxDist) {
                maxDist = dist
                splitPoint = first + i
            }
        }
        return maxDist to splitPoint
    }

    private fun chordLengthParameterize(d: List<V>, first: Int, last: Int): DoubleArray {
        val u = DoubleArray(last - first + 1)
        u[0] = 0.0
        for (i in first + 1..last) {
            u[i - first] = u[i - first - 1] + (d[i] - d[i - 1]).len()
        }
        val total = u[last - first]
        if (total > 0.0) for (i in 1..(last - first)) u[i] /= total
        return u
    }

    private fun computeCenterTangent(d: List<V>, center: Int): V {
        val v1 = d[center - 1] - d[center]
        val v2 = d[center] - d[center + 1]
        return ((v1 + v2) * 0.5).normalized()
    }

    private fun dedupe(points: List<VectorPoint>): List<V> {
        val out = ArrayList<V>(points.size)
        for (p in points) {
            val v = V(p.x.toDouble(), p.y.toDouble())
            if (out.isEmpty() || (out.last() - v).len() > 1e-6) out += v
        }
        return out
    }

    // Bernstein basis.
    private fun b0(u: Double) = (1 - u) * (1 - u) * (1 - u)
    private fun b1(u: Double) = 3 * u * (1 - u) * (1 - u)
    private fun b2(u: Double) = 3 * u * u * (1 - u)
    private fun b3(u: Double) = u * u * u

    private fun bezier(c: Array<V>, t: Double): V =
        c[0] * b0(t) + c[1] * b1(t) + c[2] * b2(t) + c[3] * b3(t)

    private fun bezier3(c: Array<V>, t: Double): V {
        val mt = 1 - t
        return c[0] * (mt * mt) + c[1] * (2 * mt * t) + c[2] * (t * t)
    }

    private fun bezier2(c: Array<V>, t: Double): V = c[0] * (1 - t) + c[1] * t

    /** Internal double-precision vector. */
    private data class V(val x: Double, val y: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
        operator fun minus(o: V) = V(x - o.x, y - o.y)
        operator fun times(s: Double) = V(x * s, y * s)
        operator fun unaryMinus() = V(-x, -y)
        fun dot(o: V) = x * o.x + y * o.y
        fun len() = hypot(x, y)
        fun lenSq() = x * x + y * y
        fun normalized(): V {
            val l = len()
            return if (l < 1e-12) V(0.0, 0.0) else V(x / l, y / l)
        }
    }
}
