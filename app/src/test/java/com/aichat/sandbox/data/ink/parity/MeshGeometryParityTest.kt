package com.aichat.sandbox.data.ink.parity

import com.aichat.sandbox.data.ink.InkGeometry
import com.aichat.sandbox.data.ink.LassoTriangulation
import com.aichat.sandbox.data.ink.MeshHitTest
import com.aichat.sandbox.data.ink.StrokeMeshCache
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.screens.notes.LassoController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I6 — mesh-backed geometry adoption** (headless, ink-native slice).
 *
 * Proves, against the *real* ink engine on the headless container (`ink-*-jvm` +
 * `libink.so`), the three accuracy wins the migration plan's capability area B
 * promises — and that each degrades to the existing point-to-segment loop when no
 * mesh is available, so ink staying **default-off** leaves the live eraser /
 * lasso byte-for-byte unchanged. Also pins the cache infrastructure: signature
 * invalidation (transform / restyle / delete), the spatial prefilter, and the
 * deterministic mesh-hit → item-id mapping.
 *
 * On-device feel (the felt accuracy of the ink-on eraser / lasso, and the lasso
 * contract change as a UX decision) stays the device-only column documented in
 * `docs/INK_I2_PARITY_GATE.md`.
 */
class MeshGeometryParityTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    /** A stroke from explicit `[x,y]` points at fixed pressure/tilt. */
    private fun stroke(points: List<Pair<Float, Float>>): FloatArray {
        val out = FloatArray(points.size * stride)
        points.forEachIndexed { i, (x, y) ->
            out[i * stride] = x
            out[i * stride + 1] = y
            out[i * stride + 2] = 0.7f
            out[i * stride + 3] = 0.1f
        }
        return out
    }

    private fun horizontal(x0: Float, x1: Float, y: Float, n: Int): FloatArray {
        val pts = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            pts.add((x0 + (x1 - x0) * t) to y)
        }
        return stroke(pts)
    }

    // ── Eraser: mesh hits the stroke's rendered width, not just its centerline ──

    /**
     * The accuracy win for partial erase: an eraser tip that grazes the *body* of
     * a wide stroke (well off its centerline) hits via the mesh, while the
     * centerline-only point-to-segment fallback — with a small eraser radius —
     * misses. Measured against ink's own mesh extent so it isn't brittle.
     */
    @Test
    fun eraserMeshHitsWideStrokeBodyWhereCenterlineMisses() {
        val cache = StrokeMeshCache()
        val width = 40f
        val samples = horizontal(0f, 200f, 100f, 24)
        val payload = StrokeCodec.encode(samples)
        assertTrue(cache.register("s", payload, "pen", width))
        val mesh = cache.meshFor("s")
        assertNotNull("ink should build a mesh for a pen stroke", mesh)

        // How thick is ink's mesh here? Probe halfway into the body, above the line.
        val bounds = InkGeometry.boundingBox(mesh!!)!!
        val halfBody = bounds[3] - 100f
        assertTrue("a width-40 pen stroke should render a body thicker than the eraser radius", halfBody > 6f)
        val py = 100f + halfBody * 0.5f
        val px = 100f
        val radius = 1.5f // much smaller than the offset into the body

        val count = samples.size / stride
        val bbox = HitTest.boundsOf(samples, count)!!
        val fallback = {
            HitTest.bboxContainsPoint(bbox, px, py, radius) &&
                HitTest.pointWithinStroke(samples, count, px, py, radius)
        }
        assertFalse("centerline fallback misses the body offset", fallback())
        assertTrue(
            "mesh eraser hits the stroke body",
            MeshHitTest.eraserHitsStroke(mesh, px, py, radius, fallback),
        )
    }

    /** Off the stroke entirely: both mesh and fallback agree it's a miss. */
    @Test
    fun eraserMeshAndFallbackAgreeOnClearMiss() {
        val cache = StrokeMeshCache()
        val samples = horizontal(0f, 200f, 100f, 16)
        cache.register("s", StrokeCodec.encode(samples), "pen", 6f)
        val mesh = cache.meshFor("s")
        val px = 100f; val py = 400f; val radius = 4f
        val count = samples.size / stride
        val bbox = HitTest.boundsOf(samples, count)!!
        val fallback = {
            HitTest.bboxContainsPoint(bbox, px, py, radius) &&
                HitTest.pointWithinStroke(samples, count, px, py, radius)
        }
        assertFalse(fallback())
        assertFalse(MeshHitTest.eraserHitsStroke(mesh, px, py, radius, fallback))
    }

    // ── Lasso: mesh catches a stroke the loop *crosses* but no sample is inside ──

    /**
     * The overlapping-stroke accuracy win: a long stroke with only two endpoint
     * samples (both outside the loop) is crossed through the middle by a small
     * lasso loop. The sample-in-polygon fallback misses it (no sample is inside);
     * the mesh-vs-triangle path selects it.
     */
    @Test
    fun lassoMeshSelectsCrossingStrokeWhereSampleLoopMisses() {
        val cache = StrokeMeshCache()
        // Two samples only: a long bar from (0,100) to (400,100).
        val samples = stroke(listOf(0f to 100f, 400f to 100f))
        val payload = StrokeCodec.encode(samples)
        cache.register("bar", payload, "pen", 6f)
        val mesh = cache.meshFor("bar")

        // A small square loop around the bar's middle — neither endpoint is in it.
        val loop = floatArrayOf(180f, 70f, 220f, 70f, 220f, 130f, 180f, 130f)
        val vertexCount = loop.size / LassoController.FLOATS_PER_VERTEX
        val polyBounds = LassoController.polygonBounds(loop, vertexCount)!!
        val triangles = LassoTriangulation.triangulate(loop, vertexCount)

        val count = samples.size / stride
        val b = HitTest.boundsOf(samples, count)!!
        val fallback = {
            LassoController.strokeIntersectsPolygon(samples, count, b, loop, vertexCount, polyBounds)
        }
        assertFalse("sample-loop misses a crossing stroke with no sample inside", fallback())
        assertTrue(
            "mesh lasso selects the crossing stroke",
            MeshHitTest.lassoSelectsStroke(mesh, triangles, fallback),
        )
    }

    /** A stroke clearly outside the loop is selected by neither path. */
    @Test
    fun lassoMeshAndFallbackAgreeOnOutsideStroke() {
        val cache = StrokeMeshCache()
        val samples = horizontal(0f, 100f, 500f, 8) // far below the loop
        cache.register("s", StrokeCodec.encode(samples), "pen", 6f)
        val mesh = cache.meshFor("s")
        val loop = floatArrayOf(180f, 70f, 220f, 70f, 220f, 130f, 180f, 130f)
        val vc = loop.size / LassoController.FLOATS_PER_VERTEX
        val pb = LassoController.polygonBounds(loop, vc)!!
        val tris = LassoTriangulation.triangulate(loop, vc)
        val count = samples.size / stride
        val b = HitTest.boundsOf(samples, count)!!
        val fallback = { LassoController.strokeIntersectsPolygon(samples, count, b, loop, vc, pb) }
        assertFalse(fallback())
        assertFalse(MeshHitTest.lassoSelectsStroke(mesh, tris, fallback))
    }

    // ── Fallback equivalence: no mesh ⇒ exactly the existing loop ──────────────

    @Test
    fun nullMeshDefersToFallbackForBothPaths() {
        var eraserFallbackRan = false
        assertTrue(MeshHitTest.eraserHitsStroke(null, 0f, 0f, 1f) { eraserFallbackRan = true; true })
        assertTrue("eraser used the fallback", eraserFallbackRan)

        var lassoFallbackRan = false
        assertFalse(MeshHitTest.lassoSelectsStroke(null, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)) {
            lassoFallbackRan = true; false
        })
        assertTrue("lasso used the fallback", lassoFallbackRan)

        // Degenerate (empty) triangles also fall back even with a real mesh.
        val cache = StrokeMeshCache()
        cache.register("s", StrokeCodec.encode(horizontal(0f, 100f, 100f, 8)), "pen", 6f)
        var emptyTriFallbackRan = false
        MeshHitTest.lassoSelectsStroke(cache.meshFor("s"), FloatArray(0)) { emptyTriFallbackRan = true; false }
        assertTrue("empty triangle fan falls back", emptyTriFallbackRan)
    }

    // ── Cache infrastructure: invalidation, prefilter, id mapping ─────────────

    /** Identical content returns the *same* cached mesh; changed content rebuilds. */
    @Test
    fun cacheReusesMeshUntilContentChanges() {
        val cache = StrokeMeshCache()
        val samples = horizontal(0f, 200f, 100f, 16)
        cache.register("s", StrokeCodec.encode(samples), "pen", 6f)
        val m1 = cache.meshFor("s")
        cache.register("s", StrokeCodec.encode(samples), "pen", 6f) // unchanged
        assertSame("unchanged content keeps the cached mesh", m1, cache.meshFor("s"))

        // Restyle: width change bumps the signature and rebuilds a wider mesh.
        cache.register("s", StrokeCodec.encode(samples), "pen", 40f)
        val m2 = cache.meshFor("s")
        assertNotSame("restyle (width) rebuilds the mesh", m1, m2)
        val h1 = InkGeometry.boundingBox(m1!!)!!.let { it[3] - it[1] }
        val h2 = InkGeometry.boundingBox(m2!!)!!.let { it[3] - it[1] }
        assertTrue("the width-40 mesh is thicker than the width-6 one (h1=$h1 h2=$h2)", h2 > h1)
    }

    /** Transform: moving the samples re-indexes the AABB and rebuilds. */
    @Test
    fun transformInvalidatesAndReindexes() {
        val cache = StrokeMeshCache()
        cache.register("s", StrokeCodec.encode(horizontal(0f, 100f, 100f, 8)), "pen", 6f)
        cache.meshFor("s")
        val before = cache.boundsFor("s")!!
        // Shift far to the right.
        cache.register("s", StrokeCodec.encode(horizontal(500f, 600f, 100f, 8)), "pen", 6f)
        val after = cache.boundsFor("s")!!
        assertTrue("AABB moved with the transform", after[0] > before[2])
        assertTrue("region query finds it at the new location", "s" in cache.candidates(500f, 90f, 600f, 110f))
        assertFalse("and not at the old one", "s" in cache.candidates(0f, 90f, 100f, 110f))
    }

    /** Delete drops the stroke from the cache and its spatial index. */
    @Test
    fun retainDropsDeletedStrokes() {
        val cache = StrokeMeshCache()
        cache.register("a", StrokeCodec.encode(horizontal(0f, 100f, 100f, 8)), "pen", 6f)
        cache.register("b", StrokeCodec.encode(horizontal(0f, 100f, 300f, 8)), "pen", 6f)
        assertEquals(2, cache.size)
        cache.retain(setOf("a"))
        assertEquals(1, cache.size)
        assertNull(cache.boundsFor("b"))
        assertNull(cache.meshFor("b"))
    }

    /**
     * Region query: deterministic id list of strokes whose mesh overlaps the box,
     * via the spatial prefilter — and demonstrate the id → layer resolution the
     * N2 work needs.
     */
    @Test
    fun regionQueryReturnsDeterministicIdsForLayerMapping() {
        val cache = StrokeMeshCache()
        cache.register("near1", StrokeCodec.encode(horizontal(0f, 100f, 100f, 8)), "pen", 6f)
        cache.register("far", StrokeCodec.encode(horizontal(0f, 100f, 5000f, 8)), "pen", 6f)
        cache.register("near2", StrokeCodec.encode(horizontal(20f, 120f, 110f, 8)), "pen", 6f)

        val region = floatArrayOf(0f, 80f, 200f, 130f)
        // Prefilter excludes the far stroke without ever building its mesh.
        assertFalse("far stroke is culled by the prefilter", "far" in cache.candidates(0f, 80f, 200f, 130f))

        val hits = MeshHitTest.strokesInRegion(cache, region[0], region[1], region[2], region[3])
        assertEquals("deterministic registration order", listOf("near1", "near2"), hits)
        assertEquals("stable across runs", hits, MeshHitTest.strokesInRegion(cache, region[0], region[1], region[2], region[3]))

        // The caller resolves each id to its layer — modelled here with a map.
        val layerOf = mapOf("near1" to "L1", "near2" to "L2", "far" to "L1")
        assertEquals(listOf("L1", "L2"), hits.map { layerOf.getValue(it) })
    }
}
