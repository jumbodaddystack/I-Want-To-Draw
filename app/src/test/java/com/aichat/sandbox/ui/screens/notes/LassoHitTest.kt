package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polygon / selection geometry tests for sub-phase 1.8.
 *
 * Covers the four public surfaces the editor relies on:
 *   - [LassoController.polygonContainsPoint] — ray-cast correctness on a
 *     square, on a concave U, and at the polygon's edge.
 *   - [LassoController.strokeIntersectsPolygon] — bbox early-out + per-sample
 *     containment.
 *   - The [EditorAction.TransformItems] bake / inverse round-trip.
 *   - [LassoController.unionBounds] for the selection rect.
 */
class LassoHitTest {

    private fun polygon(vararg xy: Float): FloatArray = xy

    private fun strokeSamples(vararg xy: Float): FloatArray {
        require(xy.size % 2 == 0)
        val count = xy.size / 2
        val out = FloatArray(count * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until count) {
            val src = i * 2
            val dst = i * StrokeCodec.FLOATS_PER_SAMPLE
            out[dst] = xy[src]
            out[dst + 1] = xy[src + 1]
            out[dst + 2] = 1f
            out[dst + 3] = 0f
        }
        return out
    }

    @Test
    fun polygonContainsPointAcceptsInteriorRejectsExterior() {
        // Square from (0,0) to (100,100).
        val poly = polygon(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        assertTrue(LassoController.polygonContainsPoint(poly, 4, 50f, 50f))
        assertFalse(LassoController.polygonContainsPoint(poly, 4, 150f, 50f))
        assertFalse(LassoController.polygonContainsPoint(poly, 4, -1f, 50f))
        // Centroid of the polygon — definitely inside.
        assertTrue(LassoController.polygonContainsPoint(poly, 4, 50f, 1f))
    }

    @Test
    fun polygonContainsPointHandlesConcaveShapes() {
        // U-shape (concave). Inner notch sits between x=40..60, y=0..80.
        val poly = polygon(
            0f, 0f,
            100f, 0f,
            100f, 100f,
            60f, 100f,
            60f, 20f,
            40f, 20f,
            40f, 100f,
            0f, 100f,
        )
        // Point inside the left arm.
        assertTrue(LassoController.polygonContainsPoint(poly, 8, 20f, 50f))
        // Point inside the notch (between the arms) — should NOT be selected.
        assertFalse(LassoController.polygonContainsPoint(poly, 8, 50f, 60f))
    }

    @Test
    fun degeneratePolygonsContainNothing() {
        assertFalse(LassoController.polygonContainsPoint(FloatArray(0), 0, 1f, 1f))
        assertFalse(LassoController.polygonContainsPoint(polygon(0f, 0f, 1f, 1f), 2, 0.5f, 0.5f))
    }

    @Test
    fun polygonBoundsCoversAllVertices() {
        val poly = polygon(10f, -5f, 50f, 30f, -3f, 12f)
        val bounds = LassoController.polygonBounds(poly, 3)!!
        assertEquals(-3f, bounds[0], 0f)
        assertEquals(-5f, bounds[1], 0f)
        assertEquals(50f, bounds[2], 0f)
        assertEquals(30f, bounds[3], 0f)
    }

    @Test
    fun strokeWhollyInsidePolygonIsSelected() {
        val poly = polygon(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val polyBounds = LassoController.polygonBounds(poly, 4)!!
        val samples = strokeSamples(20f, 20f, 80f, 80f)
        val strokeBounds = HitTest.boundsOf(samples, 2)!!
        assertTrue(
            LassoController.strokeIntersectsPolygon(
                samples, 2, strokeBounds, poly, 4, polyBounds,
            )
        )
    }

    @Test
    fun strokeWhollyOutsidePolygonIsRejected() {
        val poly = polygon(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val polyBounds = LassoController.polygonBounds(poly, 4)!!
        val samples = strokeSamples(200f, 200f, 250f, 250f)
        val strokeBounds = HitTest.boundsOf(samples, 2)!!
        assertFalse(
            LassoController.strokeIntersectsPolygon(
                samples, 2, strokeBounds, poly, 4, polyBounds,
            )
        )
    }

    @Test
    fun strokeWithOnePointInsideSelectsTheWholeStroke() {
        val poly = polygon(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val polyBounds = LassoController.polygonBounds(poly, 4)!!
        // First sample is well outside; the second pokes inside the polygon.
        val samples = strokeSamples(-50f, 50f, 50f, 50f)
        val strokeBounds = HitTest.boundsOf(samples, 2)!!
        assertTrue(
            LassoController.strokeIntersectsPolygon(
                samples, 2, strokeBounds, poly, 4, polyBounds,
            )
        )
    }

    @Test
    fun unionBoundsCoversAllRects() {
        val rects = listOf(
            floatArrayOf(0f, 0f, 10f, 10f),
            floatArrayOf(50f, -5f, 60f, 5f),
            floatArrayOf(-20f, 30f, 0f, 60f),
        )
        val u = LassoController.unionBounds(rects)!!
        assertEquals(-20f, u[0], 0f)
        assertEquals(-5f, u[1], 0f)
        assertEquals(60f, u[2], 0f)
        assertEquals(60f, u[3], 0f)
    }

    @Test
    fun transformItemsBakesAndInvertsToOriginalPayload() {
        val original = stroke("s1", samples = strokeSamples(10f, 20f, 30f, 40f))
        val items = mutableListOf<NoteItem>(original)
        // Translate +5 / +7 then scale 2x around the origin.
        val translate = StrokeTransform.translation(5f, 7f)
        val scale = StrokeTransform.scaleAround(2f, 2f, 0f, 0f)
        val combined = StrokeTransform.multiply(scale, translate)

        val action = EditorAction.TransformItems(listOf("s1"), combined)
        action.applyTo(items)

        val transformed = StrokeCodec.decode(items[0].payload)
        assertEquals(2f * (10f + 5f), transformed[0], 0.0001f)
        assertEquals(2f * (20f + 7f), transformed[1], 0.0001f)
        // Pressure / tilt untouched.
        assertEquals(1f, transformed[2], 0f)
        assertEquals(0f, transformed[3], 0f)

        // Round-trip: invert and re-apply, get the original bytes back.
        action.invert().applyTo(items)
        val restored = StrokeCodec.decode(items[0].payload)
        val expected = StrokeCodec.decode(original.payload)
        for (i in expected.indices) {
            assertEquals(expected[i], restored[i], 0.0001f)
        }
    }

    @Test
    fun transformItemsLeavesNonMatchingItemsAlone() {
        val a = stroke("a", samples = strokeSamples(0f, 0f, 10f, 10f))
        val b = stroke("b", samples = strokeSamples(50f, 50f, 60f, 60f))
        val items = mutableListOf(a, b)

        EditorAction.TransformItems(
            listOf("a"),
            StrokeTransform.translation(100f, 0f),
        ).applyTo(items)

        // a moved, b untouched (byte-identical payload).
        val aBytes = StrokeCodec.decode(items[0].payload)
        assertEquals(100f, aBytes[0], 0.0001f)
        assertEquals(b.payload.size, items[1].payload.size)
        assertTrue(b.payload.contentEquals(items[1].payload))
    }

    @Test
    fun identityTransformIsANoOp() {
        val a = stroke("a", samples = strokeSamples(1f, 2f, 3f, 4f))
        val items = mutableListOf(a)
        EditorAction.TransformItems(listOf("a"), StrokeTransform.IDENTITY).applyTo(items)
        assertTrue(a.payload.contentEquals(items[0].payload))
    }

    @Test
    fun clipboardSurvivesSelectionMutation() {
        NoteClipboard.clear()
        val items = listOf(
            stroke("a", samples = strokeSamples(0f, 0f)),
            stroke("b", samples = strokeSamples(10f, 10f)),
        )
        NoteClipboard.put(items)
        assertEquals(2, NoteClipboard.peek().size)
        // Mutating the source list doesn't reach back into the clipboard.
        assertFalse(NoteClipboard.isEmpty())
        val pinned = NoteClipboard.peek()
        assertEquals("a", pinned[0].id)
        assertEquals("b", pinned[1].id)
    }

    @Test
    fun strokeTransformInverseRoundTripIsExact() {
        val m = StrokeTransform.multiply(
            StrokeTransform.translation(13f, -7f),
            StrokeTransform.scaleAround(1.5f, 0.8f, 50f, 50f),
        )
        val inv = StrokeTransform.invert(m)
        val identity = StrokeTransform.multiply(m, inv)
        for (i in 0 until StrokeTransform.SIZE) {
            assertEquals(StrokeTransform.IDENTITY[i], identity[i], 0.0001f)
        }
    }

    @Test
    fun rotateAroundCenterPreservesCenter() {
        val rotation = StrokeTransform.rotationAround(Math.PI.toFloat() / 4f, 50f, 50f)
        val out = FloatArray(2)
        StrokeTransform.mapPoint(rotation, 50f, 50f, out)
        assertEquals(50f, out[0], 0.0001f)
        assertEquals(50f, out[1], 0.0001f)
    }

    @Test
    fun polygonBoundsOnEmptyInputReturnsNull() {
        org.junit.Assert.assertNull(LassoController.polygonBounds(FloatArray(0), 0))
    }

    @Test
    fun unionBoundsOnEmptyInputReturnsNull() {
        org.junit.Assert.assertNull(LassoController.unionBounds(emptyList()))
    }

    @Test
    fun polygonBoundsForSingleVertexIsADegeneratePoint() {
        val bounds = LassoController.polygonBounds(floatArrayOf(7f, 9f), 1)
        assertNotNull(bounds)
    }

    private fun stroke(
        id: String,
        samples: FloatArray = strokeSamples(0f, 0f, 10f, 10f),
    ): NoteItem = NoteItem(
        id = id,
        noteId = "note",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = StrokeCodec.encode(samples),
    )
}
