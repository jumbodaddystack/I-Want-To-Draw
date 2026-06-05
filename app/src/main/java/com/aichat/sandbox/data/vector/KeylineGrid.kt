package com.aichat.sandbox.data.vector

/**
 * Phase 3 — Material keyline-grid geometry (pure, JVM-testable).
 *
 * A normalized description of a Material icon keyline template expressed on a
 * square viewport whose [edge] defaults to `24f` (the icon-doc viewport edge).
 * Every figure derives from [edge] by ratio, so a 48-edge grid is exactly the
 * 24-grid ×2 and a 108-edge grid is ×4.5 — the overlay (`KeylineOverlay`) scales
 * these viewport-space figures through `ViewportController.worldToScreen*`
 * exactly like the canvas scales anchors, and the integer-grid snapper consumes
 * the same numbers, so the guide the user sees and the grid they snap to never
 * diverge.
 *
 * Standard Material keyline sizes, expressed on `edge = 24`:
 *  - square live area = 18 (inset 3),
 *  - circle Ø = 20 (radius 10, centered),
 *  - vertical rect = 16 × 20, horizontal rect = 20 × 16,
 *  - rounded-square = 18 live with a 2 corner radius.
 *
 * No Android imports — see `KeylineGridTest`.
 */
enum class KeylineShape { SQUARE, CIRCLE, ROUNDED_SQUARE, RECT_HORIZONTAL, RECT_VERTICAL }

data class KeylineGrid(
    /** Viewport edge (square icon space). All figures scale linearly off this. */
    val edge: Float = 24f,
    /** Safe-zone inset (the 24dp live-area padding). Material default = 1 on the 24 grid. */
    val padding: Float = 1f,
    /** Which keyline shape figures the overlay draws. */
    val shapes: Set<KeylineShape> = setOf(KeylineShape.SQUARE, KeylineShape.CIRCLE),
) {
    /** A keyline figure — drawn by the overlay, never exported. */
    sealed interface Figure

    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Figure
    data class Circle(val cx: Float, val cy: Float, val r: Float) : Figure
    data class RoundRect(
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
        val corner: Float,
    ) : Figure

    /** The 24dp live area: the viewport inset by [padding] on every side. */
    fun safeZone(): RoundRect = RoundRect(
        l = padding,
        t = padding,
        r = edge - padding,
        b = edge - padding,
        corner = 0f,
    )

    /**
     * The keyline guides: the four safe-zone edges plus the centre cross (a
     * vertical and a horizontal line through `edge/2`). All are full-span lines so
     * the overlay can draw them edge-to-edge.
     */
    fun keylineLines(): List<Line> {
        val half = edge / 2f
        return listOf(
            // Safe-zone edges.
            Line(padding, 0f, padding, edge),
            Line(edge - padding, 0f, edge - padding, edge),
            Line(0f, padding, edge, padding),
            Line(0f, edge - padding, edge, edge - padding),
            // Centre cross.
            Line(half, 0f, half, edge),
            Line(0f, half, edge, half),
        )
    }

    /** The keyline shape figures selected in [shapes], each scaled off [edge]. */
    fun shapeFigures(): List<Figure> = shapes.map { shape ->
        when (shape) {
            KeylineShape.SQUARE -> liveSquare(corner = 0f)
            KeylineShape.ROUNDED_SQUARE -> liveSquare(corner = edge * (2f / 24f))
            KeylineShape.CIRCLE -> Circle(edge / 2f, edge / 2f, edge * (10f / 24f))
            // 16 wide × 20 tall (insets 4 / 2 on the 24 grid).
            KeylineShape.RECT_VERTICAL -> RoundRect(
                edge * (4f / 24f), edge * (2f / 24f),
                edge - edge * (4f / 24f), edge - edge * (2f / 24f),
                0f,
            )
            // 20 wide × 16 tall (insets 2 / 4 on the 24 grid).
            KeylineShape.RECT_HORIZONTAL -> RoundRect(
                edge * (2f / 24f), edge * (4f / 24f),
                edge - edge * (2f / 24f), edge - edge * (4f / 24f),
                0f,
            )
        }
    }

    /** The 18-on-24 live square, inset 3, with the supplied [corner] radius. */
    private fun liveSquare(corner: Float): RoundRect {
        val inset = edge * (3f / 24f)
        return RoundRect(inset, inset, edge - inset, edge - inset, corner)
    }
}

object KeylinePresets {
    /** The exact Material 24dp keyline grid (square + circle, 1dp safe-zone inset). */
    val MATERIAL_24 = KeylineGrid(edge = 24f, padding = 1f)

    /**
     * The Material grid scaled to [vp]'s viewport edge: every figure (and the
     * safe-zone padding) tracks `viewportWidth / 24`, so a 48 viewport yields ×2
     * of the 24 grid and a 108 viewport yields ×4.5.
     */
    fun forViewport(vp: VectorViewport): KeylineGrid {
        val edge = vp.viewportWidth
        val ratio = if (MATERIAL_24.edge > 0f) edge / MATERIAL_24.edge else 1f
        return MATERIAL_24.copy(edge = edge, padding = MATERIAL_24.padding * ratio)
    }
}
