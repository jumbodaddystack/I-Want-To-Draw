package com.aichat.sandbox.data.vector

/**
 * Phase 5 — source model for the **semantic redraw** workflow.
 *
 * Phase 4 constrained the model to editing existing paths. Phase 5 is explicitly
 * transformative: the model proposes a brand-new scene of safe primitives, the
 * app validates it ([VectorSceneParser]), compiles it to VectorDrawable XML
 * ([VectorSceneCompiler]), and previews/exports the candidate. The model never
 * returns raw XML — only this scene description — and every object is validated
 * before it is compiled. Invalid objects are skipped with a reason in
 * [rejected] rather than crashing the pipeline.
 */
data class VectorScene(
    val schema: Int,
    val viewport: VectorViewport,
    val styleIntent: String,
    val objects: List<VectorSceneObject>,
    val rejected: List<RejectedObject> = emptyList(),
) {
    /** An object the parser could not accept, preserved with a short [reason]. */
    data class RejectedObject(
        val raw: String,
        val reason: String,
    )

    companion object {
        const val SCHEMA: Int = 1
    }
}

/**
 * One safe primitive in a [VectorScene]. Each carries a stable [id] (preserved
 * as the compiled path name) and a [style]. Geometry has already been validated
 * and clamped to the viewport by [VectorSceneParser] before compilation.
 */
sealed interface VectorSceneObject {
    val id: String
    val style: VectorSceneStyle

    data class Path(
        override val id: String,
        val pathData: String,
        override val style: VectorSceneStyle,
    ) : VectorSceneObject

    data class Line(
        override val id: String,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        override val style: VectorSceneStyle,
    ) : VectorSceneObject

    data class Rect(
        override val id: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val radius: Float = 0f,
        override val style: VectorSceneStyle,
    ) : VectorSceneObject

    data class Ellipse(
        override val id: String,
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val rotation: Float = 0f,
        override val style: VectorSceneStyle,
    ) : VectorSceneObject

    data class Polygon(
        override val id: String,
        val points: List<VectorPoint>,
        val closed: Boolean = true,
        override val style: VectorSceneStyle,
    ) : VectorSceneObject
}

/**
 * Scene-level style. Mirrors the safe subset of [VectorStyle] the compiler can
 * emit. Line cap/join default to `round` for a clean hand-drawn look; the
 * compiler only emits the stroke attributes when [strokeColor] is present.
 */
data class VectorSceneStyle(
    val strokeColor: String? = null,
    val fillColor: String? = null,
    val strokeWidth: Float? = null,
    val strokeLineCap: String? = "round",
    val strokeLineJoin: String? = "round",
    val strokeAlpha: Float? = null,
    val fillAlpha: Float? = null,
)

/**
 * Geometry bounds + viewport clamping for scene objects.
 *
 * Redraw output must stay inside the original viewport. For the geometric
 * primitives (line/rect/ellipse/polygon) coordinates are clamped into
 * `0..viewportWidth` / `0..viewportHeight`, refusing to produce zero-size
 * rects/ellipses. Free-form [VectorSceneObject.Path] data is *not* rewritten —
 * if its bounds sit wildly outside the viewport (beyond [PATH_MARGIN]) the path
 * is rejected instead.
 */
object VectorSceneBounds {

    /** Allowed slack outside the viewport for path objects, as a fraction. */
    const val PATH_MARGIN: Float = 0.1f

    /** Geometry smaller than this (viewport units) is treated as degenerate. */
    private const val EPS: Float = 0.01f

    fun objectBounds(obj: VectorSceneObject): VectorBounds? = when (obj) {
        is VectorSceneObject.Line ->
            boundsOf(listOf(obj.x0 to obj.y0, obj.x1 to obj.y1))
        is VectorSceneObject.Rect ->
            VectorBounds(obj.x, obj.y, obj.x + obj.width, obj.y + obj.height)
        is VectorSceneObject.Ellipse ->
            VectorBounds(obj.cx - obj.rx, obj.cy - obj.ry, obj.cx + obj.rx, obj.cy + obj.ry)
        is VectorSceneObject.Polygon ->
            boundsOf(obj.points.map { it.x to it.y })
        is VectorSceneObject.Path ->
            pathBounds(obj.pathData)
    }

    /**
     * Returns [obj] fitted to [viewport], or null if it collapses to nothing
     * (zero-size rect/ellipse) or — for path objects — sits too far outside the
     * viewport to be salvageable.
     */
    fun clampObjectToViewport(
        obj: VectorSceneObject,
        viewport: VectorViewport,
    ): VectorSceneObject? {
        val w = viewport.viewportWidth
        val h = viewport.viewportHeight
        return when (obj) {
            is VectorSceneObject.Line -> obj.copy(
                x0 = obj.x0.coerceIn(0f, w),
                y0 = obj.y0.coerceIn(0f, h),
                x1 = obj.x1.coerceIn(0f, w),
                y1 = obj.y1.coerceIn(0f, h),
            )
            is VectorSceneObject.Rect -> {
                val x0 = obj.x.coerceIn(0f, w)
                val y0 = obj.y.coerceIn(0f, h)
                val x1 = (obj.x + obj.width).coerceIn(0f, w)
                val y1 = (obj.y + obj.height).coerceIn(0f, h)
                val nw = x1 - x0
                val nh = y1 - y0
                if (nw <= EPS || nh <= EPS) {
                    null
                } else {
                    obj.copy(
                        x = x0,
                        y = y0,
                        width = nw,
                        height = nh,
                        radius = obj.radius.coerceIn(0f, minOf(nw, nh) / 2f),
                    )
                }
            }
            is VectorSceneObject.Ellipse -> {
                val minX = (obj.cx - obj.rx).coerceIn(0f, w)
                val maxX = (obj.cx + obj.rx).coerceIn(0f, w)
                val minY = (obj.cy - obj.ry).coerceIn(0f, h)
                val maxY = (obj.cy + obj.ry).coerceIn(0f, h)
                val nrx = (maxX - minX) / 2f
                val nry = (maxY - minY) / 2f
                if (nrx <= EPS || nry <= EPS) {
                    null
                } else {
                    obj.copy(cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, rx = nrx, ry = nry)
                }
            }
            is VectorSceneObject.Polygon -> obj.copy(
                points = obj.points.map {
                    VectorPoint(it.x.coerceIn(0f, w), it.y.coerceIn(0f, h))
                },
            )
            is VectorSceneObject.Path -> {
                val b = pathBounds(obj.pathData) ?: return null
                val mx = w * PATH_MARGIN
                val my = h * PATH_MARGIN
                val inside = b.minX >= -mx && b.minY >= -my &&
                    b.maxX <= w + mx && b.maxY <= h + my
                if (inside) obj else null
            }
        }
    }

    private fun pathBounds(pathData: String): VectorBounds? {
        val commands = PathDataParser.parse(pathData).commands
        if (commands.isEmpty()) return null
        val sampled = VectorPathSampler.sample(commands)
        return boundsOf(sampled.points.map { it.x to it.y })
    }

    private fun boundsOf(points: List<Pair<Float, Float>>): VectorBounds? {
        if (points.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((x, y) in points) {
            minX = minOf(minX, x); minY = minOf(minY, y)
            maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        return VectorBounds(minX, minY, maxX, maxY)
    }
}
