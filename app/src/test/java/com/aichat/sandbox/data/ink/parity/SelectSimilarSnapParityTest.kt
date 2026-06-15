package com.aichat.sandbox.data.ink.parity

import com.aichat.sandbox.data.ink.ConstraintSnap
import com.aichat.sandbox.data.ink.InkGeometry
import com.aichat.sandbox.data.ink.SelectSimilar
import com.aichat.sandbox.data.ink.StrokeMeshCache
import com.aichat.sandbox.data.ink.StrokeSimilarity
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase **I7 — select-similar + snapping (N2, idea #8)** (headless, ink-native
 * slice).
 *
 * The production similarity metric ([StrokeSimilarity]) is a cheap, pure-JVM
 * descriptor proxy chosen so select-similar stays interactive on a large note.
 * This test holds that proxy **honest against the real ink engine** (`ink-*-jvm`
 * + `libink.so`): it computes a true shape-overlap (IoU) from ink's
 * `PartitionedMesh` for the same strokes and shows the descriptor proxy ranks
 * candidates the *same way* ink's geometry does. It also shows a snap proposal,
 * applied as an ordinary translation on the canonical payload, makes the **real
 * ink geometry** line up — the constraint engine snaps the geometry, not just a
 * bounding box.
 *
 * On-device feel (the felt tap-to-select, snap-chip appearance, and AI ranking
 * quality on large notes) stays the device-only column in
 * `docs/INK_I2_PARITY_GATE.md`.
 */
class SelectSimilarSnapParityTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    private fun encode(pts: List<Pair<Float, Float>>): ByteArray {
        val out = FloatArray(pts.size * stride)
        pts.forEachIndexed { i, (x, y) ->
            out[i * stride] = x
            out[i * stride + 1] = y
            out[i * stride + 2] = 0.7f
            out[i * stride + 3] = 0.1f
        }
        return StrokeCodec.encode(out)
    }

    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 48): ByteArray {
        val pts = ArrayList<Pair<Float, Float>>(n + 1)
        for (i in 0..n) {
            val a = (2.0 * Math.PI * i / n).toFloat()
            pts.add((cx + r * cos(a)) to (cy + r * sin(a)))
        }
        return encode(pts)
    }

    private fun zigzag(x0: Float, y0: Float, w: Float, h: Float, teeth: Int = 6): ByteArray {
        val pts = ArrayList<Pair<Float, Float>>(teeth + 1)
        for (i in 0..teeth) {
            val x = x0 + w * i / teeth
            val y = if (i % 2 == 0) y0 else y0 + h
            pts.add(x to y)
        }
        return encode(pts)
    }

    /** Map a payload's samples into the unit box so the IoU is scale/pos invariant. */
    private fun normalized(payload: ByteArray): ByteArray {
        val samples = StrokeCodec.decode(payload)
        val count = samples.size / stride
        val b = HitTest.boundsOf(samples, count)!!
        val w = (b[2] - b[0]).coerceAtLeast(1e-3f)
        val h = (b[3] - b[1]).coerceAtLeast(1e-3f)
        val out = FloatArray(samples.size)
        for (i in 0 until count) {
            out[i * stride] = (samples[i * stride] - b[0]) / w
            out[i * stride + 1] = (samples[i * stride + 1] - b[1]) / h
            out[i * stride + 2] = samples[i * stride + 2]
            out[i * stride + 3] = samples[i * stride + 3]
        }
        return StrokeCodec.encode(out)
    }

    /** True shape-overlap (IoU) of two strokes via ink meshes, in a common frame. */
    private fun meshIoU(a: ByteArray, b: ByteArray): Float {
        val cache = StrokeMeshCache()
        cache.register("a", normalized(a), "pen", 0.08f)
        cache.register("b", normalized(b), "pen", 0.08f)
        val ma = cache.meshFor("a"); val mb = cache.meshFor("b")
        assertNotNull("ink built mesh a", ma)
        assertNotNull("ink built mesh b", mb)
        var inA = 0; var inB = 0; var both = 0
        val grid = 48
        for (gx in 0..grid) {
            for (gy in 0..grid) {
                val px = gx.toFloat() / grid
                val py = gy.toFloat() / grid
                val hitA = InkGeometry.containsPoint(ma!!, px, py)
                val hitB = InkGeometry.containsPoint(mb!!, px, py)
                if (hitA) inA++
                if (hitB) inB++
                if (hitA && hitB) both++
            }
        }
        val union = inA + inB - both
        return if (union == 0) 0f else both.toFloat() / union
    }

    private fun features(payload: ByteArray) =
        StrokeSimilarity.featuresOf(payload, "pen", 0xFF000000.toInt(), 4f)!!

    @Test
    fun descriptorProxyAgreesWithInkMeshOverlapOrdering() {
        val target = circle(100f, 100f, 50f)
        val similar = circle(400f, 400f, 20f) // a circle, translated + scaled
        val different = zigzag(0f, 0f, 200f, 60f)

        // Cheap descriptor proxy (what production ranks with).
        val simToSimilar = StrokeSimilarity.similarity(features(target), features(similar))
        val simToDifferent = StrokeSimilarity.similarity(features(target), features(different))

        // The engine it stands in for: true ink mesh IoU in a normalised frame.
        val iouToSimilar = meshIoU(target, similar)
        val iouToDifferent = meshIoU(target, different)

        assertTrue(
            "descriptor proxy ranks the circle above the zigzag ($simToSimilar vs $simToDifferent)",
            simToSimilar > simToDifferent,
        )
        assertTrue(
            "ink mesh IoU ranks the circle above the zigzag ($iouToSimilar vs $iouToDifferent)",
            iouToSimilar > iouToDifferent,
        )
    }

    @Test
    fun selectSimilarPicksTheInkTopMatch() {
        val target = circle(100f, 100f, 50f)
        val similar = circle(600f, 50f, 30f)
        val different = zigzag(0f, 0f, 180f, 40f)
        val candidates = listOf(
            SelectSimilar.Candidate("target", features(target)),
            SelectSimilar.Candidate("similar", features(similar)),
            SelectSimilar.Candidate("different", features(different)),
        )
        val ranked = SelectSimilar.rank("target", features(target), candidates)
        // The local ranker's top pick is the same stroke ink's IoU prefers.
        val inkTop = if (meshIoU(target, similar) >= meshIoU(target, different)) "similar" else "different"
        assertEquals("local top match agrees with ink geometry", inkTop, ranked.first().id)
    }

    @Test
    fun snapTranslationAlignsRealInkGeometry() {
        // Two near-left-aligned circles; the snap should make their ink meshes'
        // left extents coincide once applied to the canonical payloads.
        val a = circle(60f, 100f, 40f)   // left ~20
        val b = circle(74f, 300f, 40f)   // left ~34 (12 px off)
        val itemA = ConstraintSnap.Item("a", boundsOf(a))
        val itemB = ConstraintSnap.Item("b", boundsOf(b))
        val constraints = ConstraintSnap.detect(listOf(itemA, itemB))
        val adj = ConstraintSnap.resolve(constraints, listOf("a", "b"))
        assertTrue("a snap was proposed", adj.isNotEmpty())

        val moved = HashMap<String, ByteArray>()
        moved["a"] = a; moved["b"] = b
        for (m in adj) {
            val payload = moved.getValue(m.id)
            val shifted = StrokeTransform.applyToSamples(
                StrokeTransform.translation(m.dx, m.dy), StrokeCodec.decode(payload),
            )
            moved[m.id] = StrokeCodec.encode(shifted)
        }

        // Build ink meshes for the snapped payloads and compare left extents.
        val cache = StrokeMeshCache()
        cache.register("a", moved.getValue("a"), "pen", 8f)
        cache.register("b", moved.getValue("b"), "pen", 8f)
        val la = InkGeometry.boundingBox(cache.meshFor("a")!!)!![0]
        val lb = InkGeometry.boundingBox(cache.meshFor("b")!!)!![0]
        assertEquals("ink mesh left edges align after the snap", la, lb, 1.0f)
    }

    private fun boundsOf(payload: ByteArray): FloatArray {
        val s = StrokeCodec.decode(payload)
        return HitTest.boundsOf(s, s.size / stride)!!
    }
}
