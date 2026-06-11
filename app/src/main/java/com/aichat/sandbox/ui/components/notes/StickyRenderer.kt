package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aichat.sandbox.data.model.NoteItem

/**
 * Sub-phase 11.1 — sticky-note rasterizer.
 *
 * Soft drop shadow + rounded rect + the body laid out with an auto-shrinking
 * font: the largest size ≤ the payload's base `fontSize` whose StaticLayout
 * fits the inset rect. Very long bodies clip at the rect bottom rather than
 * overflow onto the canvas.
 *
 * Layouts cache per item id keyed on `(body, fontSize, width)` and evict via
 * [evictUnused], mirroring [TextItemRenderer]'s cache discipline.
 */
object StickyRenderer {

    private val cache: HashMap<String, CacheEntry> = HashMap()

    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = SHADOW_COLOR
    }
    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    fun draw(canvas: Canvas, item: NoteItem, drawBody: Boolean = true) {
        val payload = decoded(item) ?: return
        if (payload.width <= 0f || payload.height <= 0f) return
        val rect = RectF(payload.minX, payload.minY, payload.maxX, payload.maxY)
        val r = StickyCodec.CORNER_RADIUS_WORLD

        // Soft shadow: a translucent rounded rect offset down-right. Cheap
        // and scene-bitmap-friendly (no shadow layers / blur passes).
        rect.offset(SHADOW_OFFSET_WORLD, SHADOW_OFFSET_WORLD)
        canvas.drawRoundRect(rect, r, r, shadowPaint)
        rect.offset(-SHADOW_OFFSET_WORLD, -SHADOW_OFFSET_WORLD)

        fillPaint.color = payload.fillArgb
        canvas.drawRoundRect(rect, r, r, fillPaint)

        if (!drawBody || payload.body.isEmpty()) return
        val inset = StickyCodec.TEXT_INSET_WORLD
        val maxW = (payload.width - 2 * inset).toInt()
        val maxH = payload.height - 2 * inset
        if (maxW < 1 || maxH < 1f) return
        val layout = layoutFor(item.id, payload, maxW, maxH)
        canvas.save()
        canvas.translate(payload.minX + inset, payload.minY + inset)
        canvas.clipRect(0f, 0f, maxW.toFloat(), maxH)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Drop cache entries whose id isn't in [keepIds]. */
    fun evictUnused(keepIds: Set<String>) {
        if (cache.isEmpty()) return
        cache.keys.retainAll(keepIds)
    }

    private fun decoded(item: NoteItem): StickyCodec.StickyPayload? {
        if (item.kind != StickyCodec.KIND) return null
        return try {
            StickyCodec.decode(item.payload)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun layoutFor(
        id: String,
        payload: StickyCodec.StickyPayload,
        maxWidth: Int,
        maxHeight: Float,
    ): StaticLayout {
        val existing = cache[id]
        if (existing != null &&
            existing.body == payload.body &&
            existing.baseFontSize == payload.fontSize &&
            existing.width == maxWidth &&
            existing.height == maxHeight
        ) {
            return existing.layout
        }
        val layout = buildAutoFitLayout(payload.body, payload.fontSize, maxWidth, maxHeight)
        cache[id] = CacheEntry(
            body = payload.body,
            baseFontSize = payload.fontSize,
            width = maxWidth,
            height = maxHeight,
            layout = layout,
        )
        return layout
    }

    /**
     * Largest font ≤ [baseFontSize] (stepping down to [MIN_FONT_SIZE]) whose
     * layout height fits [maxHeight]. Falls back to the minimum size when
     * even that overflows — the draw pass clips the tail.
     */
    private fun buildAutoFitLayout(
        body: String,
        baseFontSize: Float,
        maxWidth: Int,
        maxHeight: Float,
    ): StaticLayout {
        var size = baseFontSize.coerceAtLeast(MIN_FONT_SIZE)
        while (true) {
            val layout = buildLayout(body, size, maxWidth)
            if (layout.height <= maxHeight || size <= MIN_FONT_SIZE) return layout
            size = (size - FONT_STEP).coerceAtLeast(MIN_FONT_SIZE)
        }
    }

    private fun buildLayout(body: String, fontSize: Float, maxWidth: Int): StaticLayout {
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = TEXT_COLOR
            textSize = fontSize
        }
        return StaticLayout.Builder
            .obtain(body, 0, body.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private data class CacheEntry(
        val body: String,
        val baseFontSize: Float,
        val width: Int,
        val height: Float,
        val layout: StaticLayout,
    )

    private const val SHADOW_OFFSET_WORLD = 3f
    private val SHADOW_COLOR = Color.argb(40, 0, 0, 0)
    private const val TEXT_COLOR = Color.BLACK
    private const val MIN_FONT_SIZE = 9f
    private const val FONT_STEP = 1.5f
}
