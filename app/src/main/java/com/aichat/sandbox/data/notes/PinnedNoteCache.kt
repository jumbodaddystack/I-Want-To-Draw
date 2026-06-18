package com.aichat.sandbox.data.notes

import android.content.Context
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Render cache for chat-side pinned notes (sub-phase 4.4 of
 * `docs/STYLUS_NOTES_PHASE_4.md`).
 *
 * A pinned note is re-attached on every turn — so the same PNG is requested
 * many times in a row. We rasterize once via [NoteRasterizer], stash the
 * bytes on disk in `cacheDir/pinned-notes/`, and reuse them until the note's
 * [Note.updatedAt] changes. Invalidation is implicit: any edit that goes
 * through `NoteRepository.saveNote` bumps `updatedAt`, so the next pin render
 * will see a stale stamp and re-rasterize.
 *
 * The cache is process-scoped (in-memory map of last-known stamps) but its
 * underlying file lives in the disk cache so a process restart doesn't pay
 * the rasterizer again — the disk file is still valid as long as its mtime
 * matches the persisted stamp. We over-conservatively re-render on process
 * restart because tracking the disk stamp across processes adds complexity
 * for negligible gain.
 *
 * Per-note [Mutex] serialises concurrent `getOrRender` calls for the same
 * note id so two near-simultaneous sends don't both pay the rasterizer pass.
 */
@Singleton
class PinnedNoteCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private data class CachedEntry(val updatedAt: Long, val bytes: ByteArray)

    private val entries = ConcurrentHashMap<String, CachedEntry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Return cached PNG bytes for [note] if the cache stamp matches its
     * `updatedAt`, otherwise rasterize [items], stash, and return the fresh
     * bytes. Returns `null` only if the note has no renderable geometry
     * — callers should treat that as "nothing to attach".
     */
    suspend fun getOrRender(note: Note, items: List<NoteItem>): ByteArray? {
        val cached = entries[note.id]
        if (cached != null && cached.updatedAt == note.updatedAt) {
            return cached.bytes
        }
        val lock = locks.getOrPut(note.id) { Mutex() }
        return lock.withLock {
            // Re-check after acquiring the lock — another caller may have
            // beaten us to the render while we were waiting.
            val raceWinner = entries[note.id]
            if (raceWinner != null && raceWinner.updatedAt == note.updatedAt) {
                return@withLock raceWinner.bytes
            }
            val bytes = renderToPngBytes(note, items) ?: return@withLock null
            entries[note.id] = CachedEntry(note.updatedAt, bytes)
            writeToDisk(note.id, bytes)
            bytes
        }
    }

    /**
     * Drop the cached render for [noteId]. Used when the user unpins (so a
     * subsequent re-pin doesn't serve a render from a long-gone session) and
     * when the underlying note row vanishes mid-session.
     */
    fun invalidate(noteId: String) {
        entries.remove(noteId)
        locks.remove(noteId)
        runCatching { cacheFile(noteId).delete() }
    }

    /** Test / introspection hook. */
    internal fun cachedStamp(noteId: String): Long? = entries[noteId]?.updatedAt

    private suspend fun renderToPngBytes(note: Note, items: List<NoteItem>): ByteArray? =
        withContext(Dispatchers.Default) {
            val bounds = NoteRasterizer.computeBounds(items) ?: return@withContext null
            val bitmap = NoteRasterizer.render(
                items = items,
                bounds = bounds,
                maxEdgePx = MAX_EDGE_PX,
                backgroundStyle = note.backgroundStyle,
            )
            try {
                NoteRasterizer.toPng(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun writeToDisk(noteId: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val dir = cacheDir().apply { if (!exists()) mkdirs() }
            val finalFile = File(dir, "$noteId.png")
            val tmpFile = File(dir, "$noteId.png.tmp")
            try {
                FileOutputStream(tmpFile).use { os -> os.write(bytes) }
                if (finalFile.exists()) finalFile.delete()
                tmpFile.renameTo(finalFile)
            } catch (_: Throwable) {
                // Disk persistence is best-effort — the in-memory map is the
                // source of truth for the current process. Silently swallow.
                runCatching { tmpFile.delete() }
            }
        }
    }

    private fun cacheDir(): File = File(context.cacheDir, CACHE_SUBDIR)
    private fun cacheFile(noteId: String): File = File(cacheDir(), "$noteId.png")

    companion object {
        /** Sized for pinned-note raster previews. */
        const val MAX_EDGE_PX: Int = 1536
        private const val CACHE_SUBDIR: String = "pinned-notes"
    }
}
