package com.aichat.sandbox.ui.components.notes

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Sub-phase 14.2 — connector route geometry.
 *
 * Pure: turns a connector payload + resolved endpoints into the polyline /
 * cubic actually drawn, so the surface, the rasterizer, the SVG exporter and
 * the JVM tests all route identically. Three styles:
 *
 * - **Straight** — the resolved segment, unchanged Phase 11.2 behaviour.
 * - **Elbow** — an orthogonal polyline. Each bound end exits along its
 *   anchor's outward normal through a fixed stub, then candidate Manhattan
 *   routes (H-then-V, V-then-H, and the two Z routes through the midpoint)
 *   are scored by obstacle crossings, bends and length — lowest wins. The
 *   "light obstacle-avoid heuristic" clears the two items the connector is
 *   bound to (inflated by a clearance margin); it does not path-find around
 *   unrelated canvas content.
 * - **Curved** — a single cubic whose control points sit along the anchor
 *   normals (chord direction for free / centre ends).
 */
object ConnectorRouter {

    /** How far an elbow exits perpendicular to its anchor before turning. */
    const val STUB_WORLD: Float = 24f

    /** Obstacle inflation for the elbow scoring pass. */
    const val CLEARANCE_WORLD: Float = 8f

    /**
     * How far outside its endpoint envelope a routed (non-straight)
     * connector may reach — bounds queries inflate by this.
     */
    const val ROUTE_BOUNDS_MARGIN_WORLD: Float = STUB_WORLD + CLEARANCE_WORLD

    /** Segments used when flattening a curved route for hit-testing. */
    private const val CURVE_FLATTEN_SEGMENTS = 16

    /**
     * A drawable route. When [curved], [points] is the cubic
     * `[x0, y0, c1x, c1y, c2x, c2y, x1, y1]`; otherwise a polyline
     * `[x0, y0, …, xn, yn]` with at least two points.
     */
    class Route(val points: FloatArray, val curved: Boolean)

    /**
     * Resolve [payload]'s endpoints and bound-item bounds through
     * [boundsLookup] (same contract as [ConnectorResolver.resolve]) and
     * route them.
     */
    fun route(
        payload: ConnectorCodec.ConnectorPayload,
        boundsLookup: (String) -> FloatArray?,
    ): Route {
        val endpoints = ConnectorResolver.resolve(payload, boundsLookup)
        return route(
            payload,
            endpoints,
            fromBounds = payload.fromItemId?.let(boundsLookup),
            toBounds = payload.toItemId?.let(boundsLookup),
        )
    }

    /** Route pre-resolved [endpoints] (`[x0, y0, x1, y1]`). */
    fun route(
        payload: ConnectorCodec.ConnectorPayload,
        endpoints: FloatArray,
        fromBounds: FloatArray?,
        toBounds: FloatArray?,
    ): Route {
        val x0 = endpoints[0]; val y0 = endpoints[1]
        val x1 = endpoints[2]; val y1 = endpoints[3]
        if (hypot(x1 - x0, y1 - y0) < 1e-3f) {
            return Route(floatArrayOf(x0, y0, x1, y1), curved = false)
        }
        val fromDir = if (fromBounds != null) anchorDir(payload.fromAnchor) else null
        val toDir = if (toBounds != null) anchorDir(payload.toAnchor) else null
        return when (payload.routeStyle) {
            ConnectorCodec.ROUTE_ELBOW ->
                elbow(x0, y0, fromDir, x1, y1, toDir, fromBounds, toBounds)
            ConnectorCodec.ROUTE_CURVED ->
                curved(x0, y0, fromDir, x1, y1, toDir)
            else -> Route(floatArrayOf(x0, y0, x1, y1), curved = false)
        }
    }

    /**
     * Flatten [route] to a polyline for hit-testing. Polyline routes return
     * their own points; curves sample [CURVE_FLATTEN_SEGMENTS] chords.
     */
    fun flatten(route: Route): FloatArray {
        if (!route.curved) return route.points
        val p = route.points
        val out = FloatArray((CURVE_FLATTEN_SEGMENTS + 1) * 2)
        for (i in 0..CURVE_FLATTEN_SEGMENTS) {
            val t = i / CURVE_FLATTEN_SEGMENTS.toFloat()
            val u = 1f - t
            val a = u * u * u
            val b = 3f * u * u * t
            val c = 3f * u * t * t
            val d = t * t * t
            out[i * 2] = a * p[0] + b * p[2] + c * p[4] + d * p[6]
            out[i * 2 + 1] = a * p[1] + b * p[3] + c * p[5] + d * p[7]
        }
        return out
    }

    /** `[fromX, fromY, tipX, tipY]` for the arrowhead at the route's end. */
    fun endTangent(route: Route): FloatArray {
        val p = route.points
        return if (route.curved) {
            floatArrayOf(p[4], p[5], p[6], p[7])
        } else {
            floatArrayOf(p[p.size - 4], p[p.size - 3], p[p.size - 2], p[p.size - 1])
        }
    }

    /** `[fromX, fromY, tipX, tipY]` for the arrowhead at the route's start. */
    fun startTangent(route: Route): FloatArray {
        val p = route.points
        return floatArrayOf(p[2], p[3], p[0], p[1])
    }

    /** Outward unit normal of an anchor, or null for CENTER (no preferred exit). */
    private fun anchorDir(anchor: Byte): FloatArray? = when (anchor) {
        ConnectorCodec.ANCHOR_N -> floatArrayOf(0f, -1f)
        ConnectorCodec.ANCHOR_E -> floatArrayOf(1f, 0f)
        ConnectorCodec.ANCHOR_S -> floatArrayOf(0f, 1f)
        ConnectorCodec.ANCHOR_W -> floatArrayOf(-1f, 0f)
        else -> null
    }

    /** Axis-aligned unit vector along the dominant component of (dx, dy). */
    private fun dominantAxisDir(dx: Float, dy: Float): FloatArray =
        if (abs(dx) >= abs(dy)) {
            floatArrayOf(if (dx >= 0f) 1f else -1f, 0f)
        } else {
            floatArrayOf(0f, if (dy >= 0f) 1f else -1f)
        }

    private fun curved(
        x0: Float, y0: Float, fromDir: FloatArray?,
        x1: Float, y1: Float, toDir: FloatArray?,
    ): Route {
        val dist = hypot(x1 - x0, y1 - y0)
        val k = (0.4f * dist).coerceIn(24f, 160f)
        val inv = 1f / dist
        val d0 = fromDir ?: floatArrayOf((x1 - x0) * inv, (y1 - y0) * inv)
        val d1 = toDir ?: floatArrayOf((x0 - x1) * inv, (y0 - y1) * inv)
        return Route(
            floatArrayOf(
                x0, y0,
                x0 + d0[0] * k, y0 + d0[1] * k,
                x1 + d1[0] * k, y1 + d1[1] * k,
                x1, y1,
            ),
            curved = true,
        )
    }

    private fun elbow(
        x0: Float, y0: Float, fromDir: FloatArray?,
        x1: Float, y1: Float, toDir: FloatArray?,
        fromBounds: FloatArray?,
        toBounds: FloatArray?,
    ): Route {
        val d0 = fromDir ?: dominantAxisDir(x1 - x0, y1 - y0)
        val d1 = toDir ?: dominantAxisDir(x0 - x1, y0 - y1)
        val sx0 = x0 + d0[0] * STUB_WORLD
        val sy0 = y0 + d0[1] * STUB_WORLD
        val sx1 = x1 + d1[0] * STUB_WORLD
        val sy1 = y1 + d1[1] * STUB_WORLD
        val mx = (sx0 + sx1) * 0.5f
        val my = (sy0 + sy1) * 0.5f
        // Interior waypoints between the two stub points; every candidate
        // yields an all-orthogonal polyline because stubs are axis-aligned.
        val candidates = listOf(
            floatArrayOf(sx1, sy0),
            floatArrayOf(sx0, sy1),
            floatArrayOf(mx, sy0, mx, sy1),
            floatArrayOf(sx0, my, sx1, my),
        )
        val fromObstacle = fromBounds?.let { inflate(it, CLEARANCE_WORLD) }
        val toObstacle = toBounds?.let { inflate(it, CLEARANCE_WORLD) }
        var best: FloatArray? = null
        var bestScore = Float.MAX_VALUE
        for (interior in candidates) {
            val pts = simplify(
                floatArrayOf(x0, y0, sx0, sy0) + interior + floatArrayOf(sx1, sy1, x1, y1),
            )
            val score = score(pts, fromObstacle, toObstacle)
            if (score < bestScore) {
                bestScore = score
                best = pts
            }
        }
        return Route(best ?: floatArrayOf(x0, y0, x1, y1), curved = false)
    }

    /**
     * Crossings dominate, then bends, then length — so a slightly longer
     * route that clears both items always beats a shorter one through them.
     * The first/last segments are exempt from their own item's obstacle
     * (the route starts on that item's edge by definition).
     */
    private fun score(pts: FloatArray, fromObstacle: FloatArray?, toObstacle: FloatArray?): Float {
        val segCount = pts.size / 2 - 1
        var crossings = 0
        var length = 0f
        for (s in 0 until segCount) {
            val ax = pts[s * 2]; val ay = pts[s * 2 + 1]
            val bx = pts[s * 2 + 2]; val by = pts[s * 2 + 3]
            length += hypot(bx - ax, by - ay)
            if (fromObstacle != null && s != 0 &&
                segmentCrossesRect(ax, ay, bx, by, fromObstacle)
            ) {
                crossings++
            }
            if (toObstacle != null && s != segCount - 1 &&
                segmentCrossesRect(ax, ay, bx, by, toObstacle)
            ) {
                crossings++
            }
        }
        return crossings * 10_000f + (segCount - 1) * 100f + length
    }

    /** Strict interior overlap of an axis-aligned segment with a rect. */
    private fun segmentCrossesRect(
        ax: Float, ay: Float, bx: Float, by: Float,
        rect: FloatArray,
    ): Boolean {
        val minX = min(ax, bx); val maxX = max(ax, bx)
        val minY = min(ay, by); val maxY = max(ay, by)
        return maxX > rect[0] && minX < rect[2] && maxY > rect[1] && minY < rect[3]
    }

    private fun inflate(bounds: FloatArray, by: Float): FloatArray =
        floatArrayOf(bounds[0] - by, bounds[1] - by, bounds[2] + by, bounds[3] + by)

    /** Drop consecutive duplicates and merge collinear axis-aligned runs. */
    private fun simplify(pts: FloatArray): FloatArray {
        val out = ArrayList<Float>(pts.size)
        out += pts[0]; out += pts[1]
        for (i in 1 until pts.size / 2) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            val lastX = out[out.size - 2]; val lastY = out[out.size - 1]
            if (abs(x - lastX) < 1e-4f && abs(y - lastY) < 1e-4f) continue
            if (out.size >= 4) {
                val prevX = out[out.size - 4]; val prevY = out[out.size - 3]
                val collinear =
                    (abs(prevX - lastX) < 1e-4f && abs(lastX - x) < 1e-4f) ||
                        (abs(prevY - lastY) < 1e-4f && abs(lastY - y) < 1e-4f)
                if (collinear) {
                    out[out.size - 2] = x
                    out[out.size - 1] = y
                    continue
                }
            }
            out += x; out += y
        }
        return out.toFloatArray()
    }
}
