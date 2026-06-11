package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.edit.boolean.PathBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 13.1 — boolean ops over [PathCodec.PathPayload]s via the pure
 * flatten → clip → refit pipeline. Areas are checked by shoelace over the
 * flattened result, with a tolerance covering the refit error budget.
 */
class PathBooleanBridgeTest {

    private fun rectPath(x0: Float, y0: Float, x1: Float, y1: Float, closed: Boolean = true) =
        PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(x0, y0),
                PathCodec.Anchor(x1, y0),
                PathCodec.Anchor(x1, y1),
                PathCodec.Anchor(x0, y1),
            ),
            closed = closed,
        )

    private fun areaOf(payload: PathCodec.PathPayload): Float {
        val pts = PathCodec.flatten(payload)
        var sum = 0.0
        var i = 0
        while (i + 3 < pts.size) {
            sum += pts[i].toDouble() * pts[i + 3] - pts[i + 2].toDouble() * pts[i + 1]
            i += 2
        }
        sum += pts[pts.size - 2].toDouble() * pts[1] - pts[0].toDouble() * pts[pts.size - 1]
        return abs(sum / 2.0).toFloat()
    }

    private fun combine(op: PathBoolean.Op, vararg payloads: PathCodec.PathPayload) =
        PathBooleanBridge.combine(payloads.map { listOf(it) }, op)

    private fun assertBoundsClose(expected: FloatArray, actual: FloatArray, eps: Float = 1.5f) {
        for (i in 0..3) {
            assertTrue(
                "bounds[$i] expected ${expected[i]} got ${actual[i]}",
                abs(expected[i] - actual[i]) <= eps,
            )
        }
    }

    @Test
    fun unionOfOverlappingRectsIsOneRing() {
        val results = combine(
            PathBoolean.Op.UNION,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertEquals(1, results.size)
        val result = results[0]
        assertTrue(result.closed)
        assertBoundsClose(floatArrayOf(0f, 0f, 150f, 150f), PathCodec.boundsOf(result)!!)
        // 2 × 100² − 50² overlap.
        assertEquals(17500f, areaOf(result), 350f)
    }

    @Test
    fun subtractRemovesTheTopItemFromTheSubject() {
        val results = combine(
            PathBoolean.Op.SUBTRACT,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertEquals(1, results.size)
        assertBoundsClose(floatArrayOf(0f, 0f, 100f, 100f), PathCodec.boundsOf(results[0])!!)
        assertEquals(7500f, areaOf(results[0]), 150f)
    }

    @Test
    fun intersectKeepsTheOverlap() {
        val results = combine(
            PathBoolean.Op.INTERSECT,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertEquals(1, results.size)
        assertBoundsClose(floatArrayOf(50f, 50f, 100f, 100f), PathCodec.boundsOf(results[0])!!)
        assertEquals(2500f, areaOf(results[0]), 50f)
    }

    @Test
    fun excludeYieldsBothCrescents() {
        val results = combine(
            PathBoolean.Op.EXCLUDE,
            rectPath(0f, 0f, 100f, 100f),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertEquals(2, results.size)
        assertEquals(15000f, results.sumOf { areaOf(it).toDouble() }.toFloat(), 300f)
    }

    @Test
    fun disjointIntersectIsEmpty() {
        val results = combine(
            PathBoolean.Op.INTERSECT,
            rectPath(0f, 0f, 10f, 10f),
            rectPath(500f, 500f, 510f, 510f),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun openInputsAreImplicitlyClosed() {
        val results = combine(
            PathBoolean.Op.UNION,
            rectPath(0f, 0f, 100f, 100f, closed = false),
            rectPath(50f, 50f, 150f, 150f),
        )
        assertEquals(1, results.size)
        assertEquals(17500f, areaOf(results[0]), 350f)
    }

    @Test
    fun fewerThanTwoUsableInputsIsEmpty() {
        assertTrue(PathBooleanBridge.combine(emptyList(), PathBoolean.Op.UNION).isEmpty())
        assertTrue(
            PathBooleanBridge.combine(
                listOf(listOf(rectPath(0f, 0f, 10f, 10f))),
                PathBoolean.Op.UNION,
            ).isEmpty(),
        )
        // A degenerate (single-anchor) second input drops out.
        val degenerate = PathCodec.PathPayload(
            anchors = listOf(PathCodec.Anchor(0f, 0f)),
            closed = false,
        )
        assertTrue(
            PathBooleanBridge.combine(
                listOf(listOf(rectPath(0f, 0f, 10f, 10f)), listOf(degenerate)),
                PathBoolean.Op.UNION,
            ).isEmpty(),
        )
    }

    @Test
    fun subpathRoundTripPreservesHandlesAndTypes() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f, outDx = 10f, outDy = 5f, type = PathCodec.TYPE_SMOOTH),
                PathCodec.Anchor(
                    100f, 50f,
                    inDx = -10f, inDy = -5f, outDx = 10f, outDy = 5f,
                    type = PathCodec.TYPE_SYMMETRIC,
                ),
                PathCodec.Anchor(200f, 0f, inDx = -10f, inDy = 5f),
            ),
            closed = true,
        )
        val sub = PathBooleanBridge.toSubpath(payload, "t")
        assertNotNull(sub)
        val back = PathBooleanBridge.fromSubpath(sub!!)
        assertEquals(payload.anchors.size, back.anchors.size)
        for ((a, b) in payload.anchors.zip(back.anchors)) {
            assertEquals(a.x, b.x, 1e-4f)
            assertEquals(a.y, b.y, 1e-4f)
            assertEquals(a.inDx, b.inDx, 1e-4f)
            assertEquals(a.inDy, b.inDy, 1e-4f)
            assertEquals(a.outDx, b.outDx, 1e-4f)
            assertEquals(a.outDy, b.outDy, 1e-4f)
            assertEquals(a.type, b.type)
        }
        assertTrue(back.closed)
    }
}
