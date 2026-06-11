package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class PathCodecTest {

    private fun curvedPath(
        closed: Boolean = false,
        fill: Int = 0,
        style: Byte = ShapeCodec.STROKE_STYLE_SOLID,
        capJoin: Int = PathCodec.DEFAULT_CAP_JOIN,
    ) = PathCodec.PathPayload(
        anchors = listOf(
            PathCodec.Anchor(0f, 0f, outDx = 20f, outDy = 0f, type = PathCodec.TYPE_SMOOTH),
            PathCodec.Anchor(100f, 50f, inDx = -20f, inDy = -20f, outDx = 20f, outDy = 20f, type = PathCodec.TYPE_SYMMETRIC),
            PathCodec.Anchor(200f, 0f, inDx = -20f, inDy = 0f, type = PathCodec.TYPE_CORNER),
        ),
        closed = closed,
        fillArgb = fill,
        strokeStyle = style,
        capJoin = capJoin,
    )

    @Test
    fun roundTripOpenPath() {
        val payload = curvedPath()
        assertEquals(payload, PathCodec.decode(PathCodec.encode(payload)))
    }

    @Test
    fun roundTripClosedFilledDashedPath() {
        val payload = curvedPath(
            closed = true,
            fill = 0x802463EB.toInt(),
            style = ShapeCodec.STROKE_STYLE_DASHED,
            capJoin = PathCodec.capJoinOf(PathCodec.CAP_SQUARE, PathCodec.JOIN_BEVEL),
        )
        val decoded = PathCodec.decode(PathCodec.encode(payload))
        assertEquals(payload, decoded)
        assertEquals(PathCodec.CAP_SQUARE, PathCodec.cap(decoded.capJoin))
        assertEquals(PathCodec.JOIN_BEVEL, PathCodec.join(decoded.capJoin))
    }

    @Test
    fun shortPayloadDecodesWithDefaults() {
        // Hand-build a payload that stops right after the anchors — a future
        // (or minimal) writer. Trailing fields must default.
        val anchors = listOf(
            PathCodec.Anchor(1f, 2f),
            PathCodec.Anchor(3f, 4f),
        )
        val buf = ByteBuffer.allocate(1 + 1 + 2 + anchors.size * 25).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PathCodec.VERSION)
        buf.put(1.toByte()) // closed
        buf.putShort(anchors.size.toShort())
        for (a in anchors) {
            buf.putFloat(a.x); buf.putFloat(a.y)
            buf.putFloat(a.inDx); buf.putFloat(a.inDy)
            buf.putFloat(a.outDx); buf.putFloat(a.outDy)
            buf.put(a.type)
        }
        val decoded = PathCodec.decode(buf.array())
        assertTrue(decoded.closed)
        assertEquals(anchors, decoded.anchors)
        assertEquals(0, decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_SOLID, decoded.strokeStyle)
        assertEquals(PathCodec.DEFAULT_CAP_JOIN, decoded.capJoin)
    }

    @Test
    fun payloadEndingAtFillDecodesSolidRound() {
        val full = PathCodec.encode(curvedPath(fill = 0x11223344))
        // Strip strokeStyle + capJoin (last 2 bytes).
        val truncated = full.copyOf(full.size - 2)
        val decoded = PathCodec.decode(truncated)
        assertEquals(0x11223344, decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_SOLID, decoded.strokeStyle)
        assertEquals(PathCodec.DEFAULT_CAP_JOIN, decoded.capJoin)
    }

    @Test
    fun translateMovesAnchorsButNotHandles() {
        val payload = curvedPath()
        val moved = PathCodec.transform(payload, StrokeTransform.translation(10f, -5f))
        for ((before, after) in payload.anchors.zip(moved.anchors)) {
            assertEquals(before.x + 10f, after.x, 1e-4f)
            assertEquals(before.y - 5f, after.y, 1e-4f)
            assertEquals(before.inDx, after.inDx, 1e-4f)
            assertEquals(before.inDy, after.inDy, 1e-4f)
            assertEquals(before.outDx, after.outDx, 1e-4f)
            assertEquals(before.outDy, after.outDy, 1e-4f)
            assertEquals(before.type, after.type)
        }
    }

    @Test
    fun scaleScalesHandles() {
        val payload = curvedPath()
        val scaled = PathCodec.transform(payload, StrokeTransform.scaleAround(2f, 3f, 0f, 0f))
        val a = payload.anchors[1]
        val s = scaled.anchors[1]
        assertEquals(a.x * 2f, s.x, 1e-4f)
        assertEquals(a.y * 3f, s.y, 1e-4f)
        assertEquals(a.outDx * 2f, s.outDx, 1e-4f)
        assertEquals(a.outDy * 3f, s.outDy, 1e-4f)
    }

    @Test
    fun rotationRoundTripsThroughInverse() {
        val payload = curvedPath(closed = true, fill = 0x40FF0000)
        val rot = StrokeTransform.rotationAround(0.7f, 50f, 25f)
        val back = PathCodec.transform(
            PathCodec.transform(payload, rot),
            StrokeTransform.invert(rot),
        )
        for ((before, after) in payload.anchors.zip(back.anchors)) {
            assertEquals(before.x, after.x, 1e-3f)
            assertEquals(before.y, after.y, 1e-3f)
            assertEquals(before.outDx, after.outDx, 1e-3f)
            assertEquals(before.outDy, after.outDy, 1e-3f)
        }
    }

    @Test
    fun boundsCoverCurveBulgeNotControlEnvelope() {
        // Symmetric arch: anchors at y=0, handles pull up to y=-90; the
        // curve's apex is 3/4 of the control height, well below the
        // control-point envelope. Exact bounds must report the apex.
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f, outDx = 0f, outDy = -90f),
                PathCodec.Anchor(100f, 0f, inDx = 0f, inDy = -90f),
            ),
            closed = false,
        )
        val b = PathCodec.boundsOf(payload)
        assertNotNull(b)
        assertEquals(0f, b!![0], 1e-3f)
        assertEquals(100f, b[2], 1e-3f)
        assertEquals(0f, b[3], 1e-3f)
        // Apex of a cubic with both control points at -90 is -67.5.
        assertEquals(-67.5f, b[1], 1e-2f)
    }

    @Test
    fun straightSegmentsHaveAnchorBounds() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(10f, 20f),
                PathCodec.Anchor(110f, 70f),
                PathCodec.Anchor(60f, 120f),
            ),
            closed = true,
        )
        val b = PathCodec.boundsOf(payload)!!
        assertEquals(10f, b[0], 1e-4f)
        assertEquals(20f, b[1], 1e-4f)
        assertEquals(110f, b[2], 1e-4f)
        assertEquals(120f, b[3], 1e-4f)
    }

    @Test
    fun flattenEndsWhereThePathEnds() {
        val open = curvedPath()
        val pts = PathCodec.flatten(open)
        assertEquals((open.segmentCount * PathCodec.FLATTEN_STEPS + 1) * 2, pts.size)
        assertEquals(0f, pts[0], 1e-4f)
        assertEquals(0f, pts[1], 1e-4f)
        assertEquals(200f, pts[pts.size - 2], 1e-4f)
        assertEquals(0f, pts[pts.size - 1], 1e-4f)

        val closed = curvedPath(closed = true)
        val loop = PathCodec.flatten(closed)
        // Closed paths flatten back to the start.
        assertEquals(loop[0], loop[loop.size - 2], 1e-3f)
        assertEquals(loop[1], loop[loop.size - 1], 1e-3f)
    }

    @Test
    fun hitTestOpenPathFollowsTheCurve() {
        val payload = curvedPath()
        // On the first anchor.
        assertTrue(HitTest.pathContainsPoint(payload, 0f, 0f, 4f))
        // Far from the curve.
        assertFalse(HitTest.pathContainsPoint(payload, 100f, -200f, 4f))
        // An open path's interior is not a hit.
        assertFalse(HitTest.pathContainsPoint(payload, 100f, 5f, 2f))
    }

    @Test
    fun hitTestClosedPathIncludesInterior() {
        val payload = PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f),
                PathCodec.Anchor(100f, 0f),
                PathCodec.Anchor(100f, 100f),
                PathCodec.Anchor(0f, 100f),
            ),
            closed = true,
        )
        assertTrue(HitTest.pathContainsPoint(payload, 50f, 50f, 1f))
        assertFalse(HitTest.pathContainsPoint(payload, 150f, 50f, 1f))
    }

    @Test
    fun lassoIntersectionMatchesFlattenedPoints() {
        val payload = curvedPath()
        // Loop around the middle anchor.
        val polygon = floatArrayOf(80f, 20f, 120f, 20f, 120f, 60f, 80f, 60f)
        val polyBounds = floatArrayOf(80f, 20f, 120f, 60f)
        assertTrue(HitTest.pathIntersectsPolygon(payload, polygon, 4, polyBounds))
        // Loop far away.
        val farPolygon = floatArrayOf(300f, 300f, 340f, 300f, 340f, 340f, 300f, 340f)
        val farBounds = floatArrayOf(300f, 300f, 340f, 340f)
        assertFalse(HitTest.pathIntersectsPolygon(payload, farPolygon, 4, farBounds))
    }

    @Test
    fun itemTransformerRoutesPathKind() {
        val payload = curvedPath(fill = 0x20FF00FF)
        val item = com.aichat.sandbox.data.model.NoteItem(
            noteId = "n",
            zIndex = 3,
            kind = PathCodec.KIND,
            tool = null,
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 4f,
            payload = PathCodec.encode(payload),
        )
        val moved = ItemTransformer.transform(item, StrokeTransform.translation(5f, 5f))
        val decoded = PathCodec.decode(moved.payload)
        assertEquals(payload.fillArgb, decoded.fillArgb)
        assertEquals(payload.anchors[0].x + 5f, decoded.anchors[0].x, 1e-4f)
        // Inverse restores byte-identical geometry.
        val back = ItemTransformer.transform(moved, StrokeTransform.translation(-5f, -5f))
        val backDecoded = PathCodec.decode(back.payload)
        for ((a, b) in payload.anchors.zip(backDecoded.anchors)) {
            assertTrue(abs(a.x - b.x) < 1e-3f && abs(a.y - b.y) < 1e-3f)
        }
    }

    // ── 13.2 — gradient block after capJoin ──────────────────────────────

    @Test
    fun gradientRoundTripsAfterCapJoin() {
        val gradient = FillStyle.Gradient(
            type = FillStyle.TYPE_LINEAR,
            x0 = 0f, y0 = 0f, x1 = 1f, y1 = 0.5f,
            stops = listOf(
                FillStyle.Stop(0f, 0xFF109F5C.toInt()),
                FillStyle.Stop(1f, 0x8006B6D4.toInt()),
            ),
        )
        val payload = curvedPath(
            closed = true,
            fill = gradient.firstStopArgb,
            style = ShapeCodec.STROKE_STYLE_DASHED,
            capJoin = PathCodec.capJoinOf(PathCodec.CAP_SQUARE, PathCodec.JOIN_BEVEL),
        ).copy(gradient = gradient)
        assertEquals(payload, PathCodec.decode(PathCodec.encode(payload)))
    }

    @Test
    fun legacyPayloadEndingAtCapJoinDecodesNullGradient() {
        val payload = curvedPath(closed = true, fill = 0x40000000)
        val full = PathCodec.encode(payload)
        // Pre-13.2 payloads end at capJoin — drop the trailing fillType byte.
        val legacy = full.copyOf(full.size - 1)
        val decoded = PathCodec.decode(legacy)
        assertEquals(null, decoded.gradient)
        assertEquals(payload, decoded)
    }

    @Test
    fun transformPreservesGradient() {
        val gradient = FillStyle.radial(0xFFD62828.toInt(), 0xFFFF9F1C.toInt())
        val payload = curvedPath(closed = true).copy(gradient = gradient)
        val transformed = PathCodec.transform(payload, StrokeTransform.translation(7f, 9f))
        assertEquals(gradient, transformed.gradient)
    }
}
