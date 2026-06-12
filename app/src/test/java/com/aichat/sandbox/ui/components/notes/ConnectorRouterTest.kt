package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sub-phase 14.2 — pins the route geometry: straight passthrough, elbow
 * orthogonality + anchor-normal exits + obstacle clearance, curve control
 * points, flatten endpoints and determinism.
 */
class ConnectorRouterTest {

    /** Two stickies side by side: from-E of the left binds to W of the right. */
    private val fromBounds = floatArrayOf(0f, 0f, 100f, 100f)
    private val toBounds = floatArrayOf(300f, 0f, 400f, 100f)

    private fun payload(routeStyle: Byte, fromAnchor: Byte, toAnchor: Byte) =
        ConnectorCodec.ConnectorPayload(
            fromItemId = "from",
            fromAnchor = fromAnchor,
            toItemId = "to",
            toAnchor = toAnchor,
            x0 = 0f, y0 = 0f, x1 = 0f, y1 = 0f, // fallbacks unused (both bound)
            routeStyle = routeStyle,
        )

    private fun lookup(id: String): FloatArray? = when (id) {
        "from" -> fromBounds
        "to" -> toBounds
        else -> null
    }

    @Test
    fun straightIsTheResolvedSegment() {
        val route = ConnectorRouter.route(
            payload(ConnectorCodec.ROUTE_STRAIGHT, ConnectorCodec.ANCHOR_E, ConnectorCodec.ANCHOR_W),
            ::lookup,
        )
        assertFalse(route.curved)
        assertArrayEquals(floatArrayOf(100f, 50f, 300f, 50f), route.points, 1e-4f)
    }

    @Test
    fun elbowSegmentsAreAxisAligned() {
        // N→N forces a detour; every segment must still be orthogonal.
        val route = ConnectorRouter.route(
            payload(ConnectorCodec.ROUTE_ELBOW, ConnectorCodec.ANCHOR_N, ConnectorCodec.ANCHOR_N),
            ::lookup,
        )
        val p = route.points
        assertFalse(route.curved)
        assertTrue(p.size >= 4)
        for (s in 0 until p.size / 2 - 1) {
            val dx = abs(p[s * 2 + 2] - p[s * 2])
            val dy = abs(p[s * 2 + 3] - p[s * 2 + 1])
            assertTrue("segment $s must be axis-aligned (dx=$dx dy=$dy)", dx < 1e-3f || dy < 1e-3f)
        }
    }

    @Test
    fun elbowExitsAlongAnchorNormals() {
        val route = ConnectorRouter.route(
            payload(ConnectorCodec.ROUTE_ELBOW, ConnectorCodec.ANCHOR_E, ConnectorCodec.ANCHOR_W),
            ::lookup,
        )
        val p = route.points
        // Starts at the E anchor and immediately moves +x.
        assertEquals(100f, p[0], 1e-4f)
        assertEquals(50f, p[1], 1e-4f)
        assertTrue("first move must exit east", p[2] > p[0] && abs(p[3] - p[1]) < 1e-3f)
        // Ends at the W anchor, arriving from -x.
        val n = p.size
        assertEquals(300f, p[n - 2], 1e-4f)
        assertEquals(50f, p[n - 1], 1e-4f)
        assertTrue("last move must arrive from the west", p[n - 4] < p[n - 2])
    }

    @Test
    fun elbowClearsBothBoundItems() {
        // S→S between vertically stacked items: the naive straight segment
        // would cut through both; the elbow must not cross either inflated box.
        val route = ConnectorRouter.route(
            payload(ConnectorCodec.ROUTE_ELBOW, ConnectorCodec.ANCHOR_E, ConnectorCodec.ANCHOR_E),
            ::lookup,
        )
        val p = route.points
        // Skip the first/last segments (they start/end on the items' edges).
        for (s in 1 until p.size / 2 - 2) {
            assertFalse(
                "interior segment $s crosses the from item",
                segmentCrosses(p, s, fromBounds),
            )
            assertFalse(
                "interior segment $s crosses the to item",
                segmentCrosses(p, s, toBounds),
            )
        }
    }

    @Test
    fun curvedControlPointsFollowAnchorNormals() {
        val route = ConnectorRouter.route(
            payload(ConnectorCodec.ROUTE_CURVED, ConnectorCodec.ANCHOR_E, ConnectorCodec.ANCHOR_W),
            ::lookup,
        )
        assertTrue(route.curved)
        val p = route.points
        assertEquals(8, p.size)
        // c1 east of the start, level with it; c2 west of the end.
        assertTrue(p[2] > p[0])
        assertEquals(p[1], p[3], 1e-4f)
        assertTrue(p[4] < p[6])
        assertEquals(p[7], p[5], 1e-4f)
    }

    @Test
    fun flattenSpansTheCurve() {
        val route = ConnectorRouter.route(
            payload(ConnectorCodec.ROUTE_CURVED, ConnectorCodec.ANCHOR_E, ConnectorCodec.ANCHOR_W),
            ::lookup,
        )
        val flat = ConnectorRouter.flatten(route)
        assertEquals(route.points[0], flat[0], 1e-4f)
        assertEquals(route.points[1], flat[1], 1e-4f)
        assertEquals(route.points[6], flat[flat.size - 2], 1e-4f)
        assertEquals(route.points[7], flat[flat.size - 1], 1e-4f)
        assertTrue(flat.size > 8)
    }

    @Test
    fun freeEndpointsRouteWithoutBounds() {
        val free = ConnectorCodec.ConnectorPayload(
            fromItemId = null, fromAnchor = ConnectorCodec.ANCHOR_CENTER,
            toItemId = null, toAnchor = ConnectorCodec.ANCHOR_CENTER,
            x0 = 0f, y0 = 0f, x1 = 200f, y1 = 80f,
            routeStyle = ConnectorCodec.ROUTE_ELBOW,
        )
        val route = ConnectorRouter.route(free) { null }
        val p = route.points
        assertEquals(0f, p[0], 1e-4f)
        assertEquals(0f, p[1], 1e-4f)
        assertEquals(200f, p[p.size - 2], 1e-4f)
        assertEquals(80f, p[p.size - 1], 1e-4f)
    }

    @Test
    fun routingIsDeterministic() {
        val pl = payload(ConnectorCodec.ROUTE_ELBOW, ConnectorCodec.ANCHOR_N, ConnectorCodec.ANCHOR_S)
        val a = ConnectorRouter.route(pl, ::lookup).points
        val b = ConnectorRouter.route(pl, ::lookup).points
        assertArrayEquals(a, b, 0f)
    }

    private fun segmentCrosses(p: FloatArray, s: Int, rect: FloatArray): Boolean {
        val minX = min(p[s * 2], p[s * 2 + 2])
        val maxX = max(p[s * 2], p[s * 2 + 2])
        val minY = min(p[s * 2 + 1], p[s * 2 + 3])
        val maxY = max(p[s * 2 + 1], p[s * 2 + 3])
        return maxX > rect[0] && minX < rect[2] && maxY > rect[1] && minY < rect[3]
    }
}
