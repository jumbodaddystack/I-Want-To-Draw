package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorResolverTest {

    private val boundsA = floatArrayOf(0f, 0f, 100f, 50f)     // centre (50, 25)
    private val boundsB = floatArrayOf(200f, 100f, 300f, 200f) // centre (250, 150)

    private fun payload(
        fromId: String? = "a",
        toId: String? = "b",
        fromAnchor: Byte = ConnectorCodec.ANCHOR_E,
        toAnchor: Byte = ConnectorCodec.ANCHOR_W,
    ) = ConnectorCodec.ConnectorPayload(
        fromItemId = fromId,
        fromAnchor = fromAnchor,
        toItemId = toId,
        toAnchor = toAnchor,
        x0 = -1f, y0 = -2f, x1 = -3f, y1 = -4f,
    )

    private val lookup: (String) -> FloatArray? = { id ->
        when (id) {
            "a" -> boundsA
            "b" -> boundsB
            else -> null
        }
    }

    @Test
    fun anchorPoints() {
        assertArrayEquals(floatArrayOf(50f, 0f), ConnectorResolver.anchorPoint(boundsA, ConnectorCodec.ANCHOR_N), 1e-4f)
        assertArrayEquals(floatArrayOf(100f, 25f), ConnectorResolver.anchorPoint(boundsA, ConnectorCodec.ANCHOR_E), 1e-4f)
        assertArrayEquals(floatArrayOf(50f, 50f), ConnectorResolver.anchorPoint(boundsA, ConnectorCodec.ANCHOR_S), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 25f), ConnectorResolver.anchorPoint(boundsA, ConnectorCodec.ANCHOR_W), 1e-4f)
        assertArrayEquals(floatArrayOf(50f, 25f), ConnectorResolver.anchorPoint(boundsA, ConnectorCodec.ANCHOR_CENTER), 1e-4f)
    }

    @Test
    fun boundEndpointsResolveToAnchors() {
        val resolved = ConnectorResolver.resolve(payload(), lookup)
        assertArrayEquals(floatArrayOf(100f, 25f, 200f, 150f), resolved, 1e-4f)
    }

    @Test
    fun freeEndpointsUseFallback() {
        val resolved = ConnectorResolver.resolve(payload(fromId = null, toId = null), lookup)
        assertArrayEquals(floatArrayOf(-1f, -2f, -3f, -4f), resolved, 1e-4f)
    }

    @Test
    fun deletedTargetFallsBack() {
        // "b" deleted: the end keeps the stored fallback, the start stays bound.
        val resolved = ConnectorResolver.resolve(
            payload(toId = "gone"), lookup,
        )
        assertArrayEquals(floatArrayOf(100f, 25f, -3f, -4f), resolved, 1e-4f)
    }

    @Test
    fun nearestAnchorPicksTheClosestEdge() {
        assertEquals(ConnectorCodec.ANCHOR_N, ConnectorResolver.nearestAnchor(boundsA, 50f, 2f))
        assertEquals(ConnectorCodec.ANCHOR_E, ConnectorResolver.nearestAnchor(boundsA, 95f, 25f))
        assertEquals(ConnectorCodec.ANCHOR_S, ConnectorResolver.nearestAnchor(boundsA, 50f, 49f))
        assertEquals(ConnectorCodec.ANCHOR_W, ConnectorResolver.nearestAnchor(boundsA, 4f, 25f))
    }

    @Test
    fun edgeAnchorPointsAreInNESWOrder() {
        val pts = ConnectorResolver.edgeAnchorPoints(boundsA)
        assertArrayEquals(floatArrayOf(50f, 0f), pts[0], 1e-4f)
        assertArrayEquals(floatArrayOf(100f, 25f), pts[1], 1e-4f)
        assertArrayEquals(floatArrayOf(50f, 50f), pts[2], 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 25f), pts[3], 1e-4f)
    }
}
