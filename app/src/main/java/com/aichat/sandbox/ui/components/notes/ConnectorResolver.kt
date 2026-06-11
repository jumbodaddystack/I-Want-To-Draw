package com.aichat.sandbox.ui.components.notes

/**
 * Sub-phase 11.2 — render-time endpoint resolution for connectors.
 *
 * Pure: bounds of potential binding targets arrive through a lookup lambda
 * so the same code runs under the JVM tests, the [DrawingSurface] scene
 * pass, and the exporters. A bound endpoint whose target id no longer
 * resolves falls back to the payload's stored fallback coordinate — the
 * "delete unbinds, fallback geometry kept" rule from the master plan.
 */
object ConnectorResolver {

    /**
     * Resolve [payload]'s endpoints to `[x0, y0, x1, y1]` (world units).
     * [boundsLookup] maps an item id to its current `[minX, minY, maxX, maxY]`
     * bounds, or null when the item doesn't exist (deleted / never existed).
     */
    fun resolve(
        payload: ConnectorCodec.ConnectorPayload,
        boundsLookup: (String) -> FloatArray?,
    ): FloatArray {
        val out = floatArrayOf(payload.x0, payload.y0, payload.x1, payload.y1)
        payload.fromItemId?.let { id ->
            boundsLookup(id)?.let { b ->
                val p = anchorPoint(b, payload.fromAnchor)
                out[0] = p[0]; out[1] = p[1]
            }
        }
        payload.toItemId?.let { id ->
            boundsLookup(id)?.let { b ->
                val p = anchorPoint(b, payload.toAnchor)
                out[2] = p[0]; out[3] = p[1]
            }
        }
        return out
    }

    /** World point of [anchor] on the bounds rect `[minX, minY, maxX, maxY]`. */
    fun anchorPoint(bounds: FloatArray, anchor: Byte): FloatArray {
        val cx = (bounds[0] + bounds[2]) * 0.5f
        val cy = (bounds[1] + bounds[3]) * 0.5f
        return when (anchor) {
            ConnectorCodec.ANCHOR_N -> floatArrayOf(cx, bounds[1])
            ConnectorCodec.ANCHOR_E -> floatArrayOf(bounds[2], cy)
            ConnectorCodec.ANCHOR_S -> floatArrayOf(cx, bounds[3])
            ConnectorCodec.ANCHOR_W -> floatArrayOf(bounds[0], cy)
            else -> floatArrayOf(cx, cy)
        }
    }

    /** The four edge anchors, in N/E/S/W order — used by the tool's highlight. */
    fun edgeAnchorPoints(bounds: FloatArray): Array<FloatArray> = arrayOf(
        anchorPoint(bounds, ConnectorCodec.ANCHOR_N),
        anchorPoint(bounds, ConnectorCodec.ANCHOR_E),
        anchorPoint(bounds, ConnectorCodec.ANCHOR_S),
        anchorPoint(bounds, ConnectorCodec.ANCHOR_W),
    )

    /**
     * The edge anchor of [bounds] nearest to (`px`, `py`). The centre anchor
     * is deliberately never auto-picked — binding to an edge reads better,
     * and CENTER stays available to future explicit pickers.
     */
    fun nearestAnchor(bounds: FloatArray, px: Float, py: Float): Byte {
        var best = ConnectorCodec.ANCHOR_N
        var bestDist = Float.MAX_VALUE
        for (anchor in byteArrayOf(
            ConnectorCodec.ANCHOR_N, ConnectorCodec.ANCHOR_E,
            ConnectorCodec.ANCHOR_S, ConnectorCodec.ANCHOR_W,
        )) {
            val p = anchorPoint(bounds, anchor)
            val dx = p[0] - px
            val dy = p[1] - py
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = anchor
            }
        }
        return best
    }
}
