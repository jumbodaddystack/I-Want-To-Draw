package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Phase 5 (sub-feature 5) — Schneider cubic fitting (pure). */
class CurveFitterTest {

    /** Evaluate the fitted cubics starting at [start]; returns sampled points. */
    private fun sample(start: VectorPoint, cmds: List<PathCommand>, perCurve: Int = 12): List<VectorPoint> {
        val out = ArrayList<VectorPoint>()
        var px = start.x
        var py = start.y
        for (c in cmds) {
            require(c is PathCommand.CubicTo)
            for (k in 0..perCurve) {
                val t = k.toFloat() / perCurve
                val mt = 1 - t
                val a = mt * mt * mt
                val b = 3 * mt * mt * t
                val cc = 3 * mt * t * t
                val d = t * t * t
                out += VectorPoint(
                    a * px + b * c.x1 + cc * c.x2 + d * c.x,
                    a * py + b * c.y1 + cc * c.y2 + d * c.y,
                )
            }
            px = c.x
            py = c.y
        }
        return out
    }

    @Test
    fun fit_collinearPoints_yieldsSingleLinearCubic() {
        val pts = listOf(
            VectorPoint(0f, 0f), VectorPoint(1f, 0f), VectorPoint(2f, 0f), VectorPoint(3f, 0f),
        )
        val cmds = CurveFitter.fit(pts, maxError = 1f)
        assertEquals(1, cmds.size)
        val cubic = cmds.single() as PathCommand.CubicTo
        assertEquals(3f, cubic.x, 1e-2f)
        assertEquals(0f, cubic.y, 1e-2f)
        // Control points stay on the line (y ≈ 0).
        assertEquals(0f, cubic.y1, 1e-2f)
        assertEquals(0f, cubic.y2, 1e-2f)
    }

    @Test
    fun fit_circleSamples_reproducesCircleWithinTolerance() {
        val r = 10f
        val n = 40
        val pts = (0..n).map {
            val a = it.toFloat() / n * (2 * Math.PI).toFloat()
            VectorPoint(r * cos(a), r * sin(a))
        }
        val cmds = CurveFitter.fit(pts, maxError = 0.3f)
        assertTrue("expected multiple cubics for a circle", cmds.size >= 3)
        val sampled = sample(pts.first(), cmds)
        for (p in sampled) {
            val radius = hypot(p.x, p.y)
            assertEquals("point should lie on the circle", r, radius, 1f)
        }
    }
}
