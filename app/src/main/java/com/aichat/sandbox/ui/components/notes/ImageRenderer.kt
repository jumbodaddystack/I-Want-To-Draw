package com.aichat.sandbox.ui.components.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.aichat.sandbox.data.model.NoteItem
import java.io.File

/**
 * Sub-phase 6.7 — image item rasterizer.
 *
 * Backed by an LRU bitmap cache keyed by absolute path. Cache is capped by
 * total byte-size ([CACHE_BYTES_CAP]); the on-screen path triggers decode on
 * miss, and re-renders during pan / zoom are pure blits.
 *
 * The caller resolves the base directory (almost always `context.filesDir`)
 * and passes it through [draw]; this keeps the renderer free of Android
 * Context coupling so unit tests can substitute a tmp dir.
 */
object ImageRenderer {

    /** 64 MB hard cap on the cache — bumped here in one place for tuning. */
    const val CACHE_BYTES_CAP: Int = 64 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES_CAP) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val scratchMatrix = Matrix()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    /** Drop a cached entry (called after delete / replace). */
    fun evict(absolutePath: String) {
        cache.remove(absolutePath)
    }

    fun evictAll() {
        cache.evictAll()
    }

    /**
     * Paint [item] (kind="image") onto [canvas] in world coordinates. The
     * caller is responsible for any world→canvas transform already applied
     * to [canvas].
     *
     * [filesDir] is the base directory the payload's relative path is
     * resolved against. A missing / unreadable file is rendered as a thin
     * outlined placeholder so the user still sees where the image *was* —
     * silently no-op'ing would let a missing file vanish into thin air.
     */
    fun draw(canvas: Canvas, item: NoteItem, filesDir: File) {
        val payload = ImageItemCodec.decode(item.payload)
        val absPath = File(filesDir, payload.relativePath).absolutePath
        val bitmap = obtain(absPath)
        dstRect.set(payload.minX, payload.minY, payload.maxX, payload.maxY)
        if (bitmap == null) {
            // Placeholder — same rect, light fill, "missing image" feel.
            paint.style = Paint.Style.STROKE
            paint.color = 0x66999999
            paint.strokeWidth = 1f
            canvas.drawRect(dstRect, paint)
            paint.style = Paint.Style.FILL
            return
        }
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        val rotated = payload.rotationRad != 0f
        if (rotated) {
            canvas.save()
            val cx = (payload.minX + payload.maxX) * 0.5f
            val cy = (payload.minY + payload.maxY) * 0.5f
            scratchMatrix.reset()
            scratchMatrix.postRotate(
                Math.toDegrees(payload.rotationRad.toDouble()).toFloat(),
                cx, cy,
            )
            canvas.concat(scratchMatrix)
        }
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        if (rotated) canvas.restore()
    }

    /** Decoded bitmap for [absolutePath] (cached). Null on missing / corrupt files. */
    fun obtain(absolutePath: String): Bitmap? {
        cache.get(absolutePath)?.let { return it }
        return try {
            val file = File(absolutePath)
            if (!file.exists()) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(absolutePath, opts) ?: return null
            cache.put(absolutePath, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
