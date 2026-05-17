package com.aichat.sandbox.data.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.aichat.sandbox.ui.components.notes.TextItemRenderer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Off-screen rasterizer for note thumbnails (sub-phase 1.10).
 *
 * Renders the union of all item bounds into a bitmap whose longest side is
 * [MAX_DIM_PX]. Empty notes produce a labelled stub so the list always has
 * something to show. Pure framework APIs only — no Compose, no SurfaceView —
 * so it can run on `Dispatchers.Default`.
 */
object ThumbnailRenderer {

    const val MAX_DIM_PX: Int = 512

    private const val MARGIN_WORLD: Float = 24f
    private const val STUB_WIDTH_PX: Int = 512
    private const val STUB_HEIGHT_PX: Int = 320
    private val STUB_BG_COLOR: Int = Color.rgb(245, 245, 245)
    private val STUB_TEXT_COLOR: Int = Color.argb(140, 0, 0, 0)
    private const val STUB_TEXT_PX: Float = 32f
    private const val STROKE_KIND: String = "stroke"

    /**
     * Render [items] onto a bitmap sized to fit the union bounds. When the
     * note has no visible geometry, returns a stub showing the note title.
     */
    fun render(note: Note, items: List<NoteItem>): Bitmap {
        val bounds = computeBounds(items)
        return if (bounds == null) renderStub(note) else renderWithBounds(items, bounds)
    }

    /**
     * Union of every item's bounding box, or `null` if no item contributes
     * any geometry (empty note or all-empty text items).
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

    private fun boundsOf(item: NoteItem): FloatArray? = when (item.kind) {
        STROKE_KIND -> {
            val samples = StrokeCodec.decode(item.payload)
            HitTest.boundsOf(samples, samples.size / StrokeCodec.FLOATS_PER_SAMPLE)
        }
        TextItemCodec.KIND -> TextItemRenderer.boundsOf(item)
        else -> null
    }

    private fun renderStub(note: Note): Bitmap {
        val bmp = Bitmap.createBitmap(STUB_WIDTH_PX, STUB_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(STUB_BG_COLOR)
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = STUB_TEXT_COLOR
            textSize = STUB_TEXT_PX
        }
        val label = note.title.ifBlank { "Empty note" }
        val textWidth = paint.measureText(label)
        val metrics = paint.fontMetrics
        val baselineY = STUB_HEIGHT_PX / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, (STUB_WIDTH_PX - textWidth) / 2f, baselineY, paint)
        return bmp
    }

    private fun renderWithBounds(items: List<NoteItem>, bounds: FloatArray): Bitmap {
        val padMinX = bounds[0] - MARGIN_WORLD
        val padMinY = bounds[1] - MARGIN_WORLD
        val padMaxX = bounds[2] + MARGIN_WORLD
        val padMaxY = bounds[3] + MARGIN_WORLD
        val worldW = max(1f, padMaxX - padMinX)
        val worldH = max(1f, padMaxY - padMinY)
        val scale = MAX_DIM_PX / max(worldW, worldH)
        val bitmapW = max(1, (worldW * scale).roundToInt())
        val bitmapH = max(1, (worldH * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-padMinX, -padMinY)

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
            }
        }
        canvas.restore()
        return bmp
    }
}
