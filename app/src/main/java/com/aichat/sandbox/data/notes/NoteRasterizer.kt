package com.aichat.sandbox.data.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.ShapeRenderer
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.aichat.sandbox.ui.components.notes.TextItemRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Off-screen rasterizer for note geometry (sub-phase 2.2).
 *
 * Unifies the bitmap pipeline used by thumbnails (sub-phase 1.10), AI requests
 * (sub-phase 2.5), and exports (Phase 4). The core entry point is [render]
 * which takes a pre-computed bounds rectangle so callers that already know
 * what they want to draw (e.g. a lasso selection) don't pay to recompute it.
 *
 * Output is always `ARGB_8888`; the longest edge is clamped to `maxEdgePx`
 * and the scale itself is clamped to `[MIN_SCALE, MAX_SCALE]` so a tiny note
 * doesn't blow up to a 4x render and a giant note doesn't bottom out at zero.
 *
 * Pure framework APIs only — safe to call on `Dispatchers.Default`.
 */
object NoteRasterizer {

    /** World-units of padding added around the geometry bounds. */
    const val MARGIN_WORLD: Float = 24f

    /** Lower bound on the world→pixel scale factor. */
    const val MIN_SCALE: Float = 0.1f

    /** Upper bound on the world→pixel scale factor. */
    const val MAX_SCALE: Float = 4.0f

    private const val STROKE_KIND: String = "stroke"

    private const val GRID_SPACING_WORLD: Float = 32f
    private const val MIN_GRID_BITMAP_SPACING_PX: Float = 6f
    private const val DOT_RADIUS_PX: Float = 1.4f
    private val GRID_COLOR: Int = Color.argb(40, 0, 0, 0)
    private const val PAPER_COLOR: Int = Color.WHITE

    /**
     * Render [items] cropped to [bounds] into a fresh ARGB bitmap. The longest
     * bitmap edge is bounded by [maxEdgePx]; [marginWorld] adds breathing room
     * around the bounds before scaling.
     *
     * Returns a non-null bitmap as long as [bounds] describes a valid rect
     * (width and height ≥ 0). The caller owns the bitmap and should `recycle`
     * it when finished.
     */
    fun render(
        items: List<NoteItem>,
        bounds: FloatArray,
        maxEdgePx: Int,
        backgroundStyle: String = BackgroundLayer.STYLE_PLAIN,
        marginWorld: Float = MARGIN_WORLD,
    ): Bitmap {
        require(bounds.size == 4) { "bounds must be [minX, minY, maxX, maxY]" }
        require(maxEdgePx > 0) { "maxEdgePx must be > 0 (got $maxEdgePx)" }
        val padMinX = bounds[0] - marginWorld
        val padMinY = bounds[1] - marginWorld
        val padMaxX = bounds[2] + marginWorld
        val padMaxY = bounds[3] + marginWorld
        val worldW = max(1f, padMaxX - padMinX)
        val worldH = max(1f, padMaxY - padMinY)
        val scale = computeScale(worldW, worldH, maxEdgePx)
        val bitmapW = max(1, (worldW * scale).roundToInt())
        val bitmapH = max(1, (worldH * scale).roundToInt())

        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, backgroundStyle, padMinX, padMinY, scale, bitmapW, bitmapH)

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-padMinX, -padMinY)
        drawItems(canvas, items)
        canvas.restore()
        return bitmap
    }

    /**
     * Render only [items], computing bounds on the fly. Returns `null` if the
     * selection has no visible geometry (e.g. only empty text boxes).
     */
    fun renderSelection(
        items: List<NoteItem>,
        maxEdgePx: Int = 1024,
        backgroundStyle: String = BackgroundLayer.STYLE_PLAIN,
    ): Bitmap? {
        val bounds = computeBounds(items) ?: return null
        return render(items, bounds, maxEdgePx, backgroundStyle)
    }

    /**
     * Render a whole [note] at its native background style. Returns `null` if
     * the note is empty so the caller can choose to draw a placeholder.
     */
    fun renderNote(
        note: Note,
        items: List<NoteItem>,
        maxEdgePx: Int = 1024,
        backgroundStyle: String = note.backgroundStyle,
    ): Bitmap? {
        val bounds = computeBounds(items) ?: return null
        return render(items, bounds, maxEdgePx, backgroundStyle)
    }

    /**
     * Paint the paper colour + grid pattern onto [canvas] in world units,
     * clipped to [worldBounds]. Used by the PDF export pipeline (sub-phase
     * 4.2) where a canvas transform is already in effect — drawing in world
     * coords avoids re-deriving page-space math for every tile.
     *
     * [effectiveScale] is the world→canvas scale that will be applied when
     * the strokes paint; we use it to drop the grid when the on-device
     * spacing would render as solid ink. Defaults to `1f` (tile mode); pass
     * the fit-page scale in [Mode.FIT_ONE_PAGE].
     */
    fun drawBackgroundInWorld(
        canvas: Canvas,
        backgroundStyle: String,
        worldBounds: FloatArray,
        effectiveScale: Float = 1f,
    ) {
        require(worldBounds.size == 4) { "worldBounds must be [minX, minY, maxX, maxY]" }
        // Paper fill is drawn first so even degenerate / empty notes export a
        // white background instead of a transparent PDF page.
        val paper = Paint().apply {
            isAntiAlias = false
            color = PAPER_COLOR
            style = Paint.Style.FILL
        }
        canvas.drawRect(worldBounds[0], worldBounds[1], worldBounds[2], worldBounds[3], paper)
        if (backgroundStyle == BackgroundLayer.STYLE_PLAIN) return
        val canvasSpacing = GRID_SPACING_WORLD * effectiveScale
        if (canvasSpacing < MIN_GRID_BITMAP_SPACING_PX) return

        val paint = Paint().apply {
            isAntiAlias = true
            color = GRID_COLOR
            strokeWidth = 1f / effectiveScale.coerceAtLeast(0.0001f)
        }
        val firstX = floor(worldBounds[0] / GRID_SPACING_WORLD) * GRID_SPACING_WORLD
        val firstY = floor(worldBounds[1] / GRID_SPACING_WORLD) * GRID_SPACING_WORLD
        // Convert the on-device dot radius back into world units so it prints
        // visually consistent with the bitmap path regardless of scale.
        val dotRadiusWorld = DOT_RADIUS_PX / effectiveScale.coerceAtLeast(0.0001f)
        when (backgroundStyle) {
            BackgroundLayer.STYLE_DOT -> {
                paint.style = Paint.Style.FILL
                var wy = firstY
                while (wy <= worldBounds[3]) {
                    if (wy >= worldBounds[1]) {
                        var wx = firstX
                        while (wx <= worldBounds[2]) {
                            if (wx >= worldBounds[0]) {
                                canvas.drawCircle(wx, wy, dotRadiusWorld, paint)
                            }
                            wx += GRID_SPACING_WORLD
                        }
                    }
                    wy += GRID_SPACING_WORLD
                }
            }
            BackgroundLayer.STYLE_LINE -> {
                paint.style = Paint.Style.STROKE
                var wy = firstY
                while (wy <= worldBounds[3]) {
                    if (wy >= worldBounds[1]) {
                        canvas.drawLine(worldBounds[0], wy, worldBounds[2], wy, paint)
                    }
                    wy += GRID_SPACING_WORLD
                }
            }
            BackgroundLayer.STYLE_GRAPH -> {
                paint.style = Paint.Style.STROKE
                var wy = firstY
                while (wy <= worldBounds[3]) {
                    if (wy >= worldBounds[1]) {
                        canvas.drawLine(worldBounds[0], wy, worldBounds[2], wy, paint)
                    }
                    wy += GRID_SPACING_WORLD
                }
                var wx = firstX
                while (wx <= worldBounds[2]) {
                    if (wx >= worldBounds[0]) {
                        canvas.drawLine(wx, worldBounds[1], wx, worldBounds[3], paint)
                    }
                    wx += GRID_SPACING_WORLD
                }
            }
        }
    }

    /** PNG-encode [bitmap]. Quality is ignored by PNG but kept for symmetry. */
    fun toPng(bitmap: Bitmap, quality: Int = 100): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, quality, out)
        return out.toByteArray()
    }

    /**
     * Union of every item's bounding box, or `null` if no item contributes
     * any geometry. Reusable by callers that need the bounds without paying
     * for a render (e.g. positioning a "Insert as text box" reply).
     */
    fun computeBounds(items: List<NoteItem>): FloatArray? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (item in items) {
            val b = boundsOf(item) ?: continue
            if (b[0] < minX) minX = b[0]
            if (b[1] < minY) minY = b[1]
            if (b[2] > maxX) maxX = b[2]
            if (b[3] > maxY) maxY = b[3]
        }
        if (minX > maxX || minY > maxY) return null
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * World→pixel scale factor for fitting a (worldWidth × worldHeight) rect
     * inside a square of [maxEdgePx]. Clamped to `[MIN_SCALE, MAX_SCALE]`.
     */
    internal fun computeScale(worldWidth: Float, worldHeight: Float, maxEdgePx: Int): Float {
        val longest = max(1f, max(worldWidth, worldHeight))
        return (maxEdgePx / longest).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun boundsOf(item: NoteItem): FloatArray? = when (item.kind) {
        STROKE_KIND -> {
            val samples = StrokeCodec.decode(item.payload)
            HitTest.boundsOf(samples, samples.size / StrokeCodec.FLOATS_PER_SAMPLE)
        }
        TextItemCodec.KIND -> TextItemRenderer.boundsOf(item)
        Shape.KIND -> ShapeCodec.boundsOf(ShapeCodec.decode(item.payload).shape)
        else -> null
    }

    /**
     * Draw [items] onto [canvas] in the order they should paint. The caller is
     * responsible for any world→canvas transform: if a canvas matrix is in
     * effect we paint in world units, otherwise items land at their raw
     * coordinates. Exposed so callers that draw onto a non-bitmap target
     * (the PDF export pipeline in 4.2 in particular) can reuse the per-item
     * paint configuration without duplicating the decode loop.
     */
    fun drawItems(canvas: Canvas, items: List<NoteItem>) {
        val sorted = items.sortedBy { it.zIndex }
        val paint = Paint()
        val path = Path()
        val matrix = Matrix()
        for (item in sorted) {
            when (item.kind) {
                STROKE_KIND -> {
                    StrokeRenderer.configureToolPaint(paint, item.tool, item.colorArgb)
                    val samples = StrokeCodec.decode(item.payload)
                    val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                    StrokeRenderer.drawStrokePath(
                        canvas = canvas,
                        paint = paint,
                        samples = samples,
                        sampleCount = count,
                        baseWidthPx = item.baseWidthPx,
                        tool = item.tool,
                        scratchPath = path,
                    )
                }
                TextItemCodec.KIND -> TextItemRenderer.draw(canvas, item, matrix)
                Shape.KIND -> ShapeRenderer.draw(canvas, item, paint)
            }
        }
    }

    /**
     * Paper colour + (optionally) the per-note grid pattern. Drawn in pixel
     * space — line weights stay constant on the output bitmap regardless of
     * world→pixel scale, matching the on-screen [BackgroundLayer] feel.
     */
    private fun drawBackground(
        canvas: Canvas,
        style: String,
        worldOriginX: Float,
        worldOriginY: Float,
        scale: Float,
        bitmapW: Int,
        bitmapH: Int,
    ) {
        canvas.drawColor(PAPER_COLOR)
        if (style == BackgroundLayer.STYLE_PLAIN) return
        val pxSpacing = GRID_SPACING_WORLD * scale
        if (pxSpacing < MIN_GRID_BITMAP_SPACING_PX) return

        val paint = Paint().apply {
            isAntiAlias = true
            color = GRID_COLOR
            strokeWidth = 1f
        }
        // World coord of the first gridline at or above the bitmap's top-left,
        // snapped to the grid. Then walk in pixel space using the constant
        // pxSpacing step so accumulated float error stays small.
        val firstWorldX = floor(worldOriginX / GRID_SPACING_WORLD) * GRID_SPACING_WORLD
        val firstWorldY = floor(worldOriginY / GRID_SPACING_WORLD) * GRID_SPACING_WORLD
        val startPxX = (firstWorldX - worldOriginX) * scale
        val startPxY = (firstWorldY - worldOriginY) * scale
        val widthF = bitmapW.toFloat()
        val heightF = bitmapH.toFloat()

        when (style) {
            BackgroundLayer.STYLE_DOT -> {
                paint.style = Paint.Style.FILL
                val cols = ceil((bitmapW - startPxX) / pxSpacing).toInt() + 1
                val rows = ceil((bitmapH - startPxY) / pxSpacing).toInt() + 1
                var sy = startPxY
                for (r in 0 until rows) {
                    if (sy in -DOT_RADIUS_PX..(heightF + DOT_RADIUS_PX)) {
                        var sx = startPxX
                        for (c in 0 until cols) {
                            if (sx in -DOT_RADIUS_PX..(widthF + DOT_RADIUS_PX)) {
                                canvas.drawCircle(sx, sy, DOT_RADIUS_PX, paint)
                            }
                            sx += pxSpacing
                        }
                    }
                    sy += pxSpacing
                }
            }
            BackgroundLayer.STYLE_LINE -> {
                paint.style = Paint.Style.STROKE
                drawHorizontals(canvas, paint, startPxY, pxSpacing, widthF, heightF)
            }
            BackgroundLayer.STYLE_GRAPH -> {
                paint.style = Paint.Style.STROKE
                drawHorizontals(canvas, paint, startPxY, pxSpacing, widthF, heightF)
                drawVerticals(canvas, paint, startPxX, pxSpacing, widthF, heightF)
            }
        }
    }

    private fun drawHorizontals(
        canvas: Canvas,
        paint: Paint,
        startPxY: Float,
        pxSpacing: Float,
        widthF: Float,
        heightF: Float,
    ) {
        var sy = startPxY
        while (sy <= heightF) {
            if (sy >= 0f) canvas.drawLine(0f, sy, widthF, sy, paint)
            sy += pxSpacing
        }
    }

    private fun drawVerticals(
        canvas: Canvas,
        paint: Paint,
        startPxX: Float,
        pxSpacing: Float,
        widthF: Float,
        heightF: Float,
    ) {
        var sx = startPxX
        while (sx <= widthF) {
            if (sx >= 0f) canvas.drawLine(sx, 0f, sx, heightF, paint)
            sx += pxSpacing
        }
    }
}
