package com.aichat.sandbox.data.notes

import android.content.Context
import android.graphics.Bitmap
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 8.2 — per-frame thumbnail renderer.
 *
 * Caches by `(frameId, contentHash)` so repeated panning across frames
 * doesn't re-rasterise the same content. The content hash is derived from
 * the ids + payload sizes of items intersecting the frame's bounds —
 * sufficient to invalidate when the user erases or transforms strokes,
 * without forcing a full bitmap diff.
 *
 * Thumbnails are produced at [MAX_EDGE_PX] (256 px longest edge); the
 * navigator scales them down further to its display size, which keeps the
 * bitmap memory budget bounded at ~256 KB per cached frame.
 */
@Singleton
class FrameThumbnailRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private data class CacheEntry(val hash: Long, val bitmap: Bitmap)

    private val cache: ConcurrentHashMap<String, CacheEntry> = ConcurrentHashMap()

    /**
     * Get (or render) the thumbnail for [frame] against [items]. Hashing
     * keys off item ids + payload size + the frame's rect; pure geometry
     * mutations on intersecting items will bump the hash.
     */
    suspend fun thumbnailFor(
        frame: NoteFrame,
        items: List<NoteItem>,
    ): Bitmap = withContext(Dispatchers.Default) {
        val intersecting = items.filter { item ->
            val b = NoteRasterizer.computeBounds(listOf(item)) ?: return@filter false
            rectsIntersect(b, frame.bounds())
        }
        val hash = computeHash(frame, intersecting)
        val cached = cache[frame.id]
        if (cached != null && cached.hash == hash) {
            return@withContext cached.bitmap
        }
        cached?.bitmap?.recycle()
        val bitmap = NoteRasterizer.renderForFrame(
            items = items,
            frameBounds = frame.bounds(),
            maxEdgePx = MAX_EDGE_PX,
            filesDir = context.filesDir,
        )
        cache[frame.id] = CacheEntry(hash, bitmap)
        bitmap
    }

    /** Invalidate the cache entry for [frameId]. */
    fun invalidate(frameId: String) {
        cache.remove(frameId)?.bitmap?.recycle()
    }

    /** Drop all cached thumbnails (e.g. on note close). */
    fun clear() {
        for ((_, entry) in cache) entry.bitmap.recycle()
        cache.clear()
    }

    private fun computeHash(frame: NoteFrame, items: List<NoteItem>): Long {
        var h = 1469598103934665603L  // FNV-1a 64-bit offset
        fun mix(value: Long) {
            h = h xor value
            h *= 1099511628211L
        }
        mix(frame.minX.toRawBits().toLong())
        mix(frame.minY.toRawBits().toLong())
        mix(frame.maxX.toRawBits().toLong())
        mix(frame.maxY.toRawBits().toLong())
        for (item in items) {
            mix(item.id.hashCode().toLong())
            mix(item.payload.size.toLong())
            mix(item.colorArgb.toLong())
            mix(item.baseWidthPx.toRawBits().toLong())
        }
        return h
    }

    private fun rectsIntersect(a: FloatArray, b: FloatArray): Boolean =
        !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3])

    companion object {
        const val MAX_EDGE_PX: Int = 256
    }
}
