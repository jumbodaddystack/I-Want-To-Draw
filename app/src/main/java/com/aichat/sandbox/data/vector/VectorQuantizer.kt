package com.aichat.sandbox.data.vector

import kotlin.math.round

/**
 * Phase 3 — pure affine + grid-quantize passes over a [VectorDocument].
 *
 * The geometry in an editor-authored document is already vector (all-cubic
 * absolute coords out of the Phase 1 model), so there is nothing to average or
 * resample on export — the production pipeline only needs to **round** each
 * coordinate onto the icon grid before serialization, which the existing writers
 * ([AndroidVectorDrawableWriter] / [VectorSvgWriter]) then emit from `commands`
 * unchanged. Both passes are pure, deterministic, and unit-tested via re-parse.
 *
 * [mapCoordinates] is the shared coordinate walker (also reused by
 * [IconSizeSet] for size derivation); [quantize] is the lossless grid-snap built
 * on top of it.
 *
 * The transform passed to [mapCoordinates] is assumed **separable per-axis**
 * (`f(x, y) = (gx(x), gy(y))`) — scale and grid-quantize both are — so single-axis
 * commands (`H`/`V`) can recover their mapped coordinate by probing the relevant
 * axis with a `0` placeholder on the other.
 */
object VectorQuantizer {

    /**
     * Return a copy of [doc] with every coordinate in every path's [commands]
     * mapped through [fn]. Paths whose [commands] are null (the data could not be
     * parsed) pass through verbatim so their original `pathData` survives; mapped
     * paths have their `pathData` re-rendered from the new commands so the model
     * stays self-consistent. The viewport and styles are untouched.
     */
    fun mapCoordinates(
        doc: VectorDocument,
        fn: (x: Float, y: Float) -> Pair<Float, Float>,
    ): VectorDocument = doc.copy(root = mapGroup(doc.root, fn))

    private fun mapGroup(
        group: VectorGroup,
        fn: (Float, Float) -> Pair<Float, Float>,
    ): VectorGroup = group.copy(
        children = group.children.map { child ->
            when (child) {
                is VectorNode.GroupNode -> VectorNode.GroupNode(mapGroup(child.group, fn))
                is VectorNode.PathNode -> VectorNode.PathNode(mapPath(child.path, fn))
            }
        },
    )

    private fun mapPath(path: VectorPath, fn: (Float, Float) -> Pair<Float, Float>): VectorPath {
        val cmds = path.commands ?: return path
        val mapped = cmds.map { mapCommand(it, fn) }
        return path.copy(commands = mapped, pathData = PathDataFormatter.format(mapped))
    }

    private fun mapCommand(
        cmd: PathCommand,
        fn: (Float, Float) -> Pair<Float, Float>,
    ): PathCommand = when (cmd) {
        is PathCommand.MoveTo -> fn(cmd.x, cmd.y).let { (x, y) -> cmd.copy(x = x, y = y) }
        is PathCommand.LineTo -> fn(cmd.x, cmd.y).let { (x, y) -> cmd.copy(x = x, y = y) }
        is PathCommand.HorizontalTo -> cmd.copy(x = fn(cmd.x, 0f).first)
        is PathCommand.VerticalTo -> cmd.copy(y = fn(0f, cmd.y).second)
        is PathCommand.CubicTo -> {
            val (x1, y1) = fn(cmd.x1, cmd.y1)
            val (x2, y2) = fn(cmd.x2, cmd.y2)
            val (x, y) = fn(cmd.x, cmd.y)
            cmd.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2, x = x, y = y)
        }
        is PathCommand.SmoothCubicTo -> {
            val (x2, y2) = fn(cmd.x2, cmd.y2)
            val (x, y) = fn(cmd.x, cmd.y)
            cmd.copy(x2 = x2, y2 = y2, x = x, y = y)
        }
        is PathCommand.QuadTo -> {
            val (x1, y1) = fn(cmd.x1, cmd.y1)
            val (x, y) = fn(cmd.x, cmd.y)
            cmd.copy(x1 = x1, y1 = y1, x = x, y = y)
        }
        is PathCommand.SmoothQuadTo -> fn(cmd.x, cmd.y).let { (x, y) -> cmd.copy(x = x, y = y) }
        // Arc radii/rotation are out of scope (Phase 3); only the endpoint moves.
        is PathCommand.ArcTo -> fn(cmd.x, cmd.y).let { (x, y) -> cmd.copy(x = x, y = y) }
        is PathCommand.Close -> cmd
    }

    /**
     * Round every absolute coordinate to the nearest multiple of [step] (default
     * `1f` = integer grid), clamped into the viewport box `[0, viewportWidth]` ×
     * `[0, viewportHeight]`. Idempotent — re-quantizing an already-quantized
     * document is a no-op — and command kinds (`M`/`L`/`C`/`Z`…) are preserved.
     *
     * Use `step = viewportWidth / targetDp` for a true device-pixel grid; the
     * default `1f` quantizes directly onto the icon viewport (24/48/108).
     */
    fun quantize(doc: VectorDocument, step: Float = 1f): VectorDocument {
        if (step <= 0f) return doc
        val maxX = doc.viewport.viewportWidth
        val maxY = doc.viewport.viewportHeight
        return mapCoordinates(doc) { x, y ->
            val qx = (round(x / step) * step).coerceIn(0f, maxX)
            val qy = (round(y / step) * step).coerceIn(0f, maxY)
            qx to qy
        }
    }
}
