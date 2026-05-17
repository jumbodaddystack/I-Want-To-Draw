package com.aichat.sandbox.data.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.BackgroundLayer

/**
 * Thumbnail entry point for the notes list (sub-phase 1.10).
 *
 * Geometry rendering lives in [NoteRasterizer] (sub-phase 2.2). This object
 * keeps the empty-note stub so the list always has something to show, plus
 * the agreed maximum thumbnail edge length.
 */
object ThumbnailRenderer {

    const val MAX_DIM_PX: Int = 512

    private const val STUB_WIDTH_PX: Int = 512
    private const val STUB_HEIGHT_PX: Int = 320
    private val STUB_BG_COLOR: Int = Color.rgb(245, 245, 245)
    private val STUB_TEXT_COLOR: Int = Color.argb(140, 0, 0, 0)
    private const val STUB_TEXT_PX: Float = 32f

    /**
     * Thumbnail for [note]. Renders the geometry via [NoteRasterizer] on a
     * plain paper background (grid styling would just be noise at 512px);
     * falls back to a titled stub for empty notes.
     */
    fun render(note: Note, items: List<NoteItem>): Bitmap =
        NoteRasterizer.renderNote(
            note = note,
            items = items,
            maxEdgePx = MAX_DIM_PX,
            backgroundStyle = BackgroundLayer.STYLE_PLAIN,
        ) ?: renderStub(note)

    /** Titled placeholder for empty notes. Exposed so callers using
     * [NoteRasterizer] directly can fall back without re-invoking it. */
    fun renderStub(note: Note): Bitmap {
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
}
