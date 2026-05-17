package com.aichat.sandbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.aichat.sandbox.data.local.NoteDao
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.NoteRasterizer
import com.aichat.sandbox.data.notes.ThumbnailRenderer
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
) {

    // Singleton-scoped background scope for fire-and-forget thumbnail work.
    // The editor ViewModel is cleared the moment the user navigates back, so a
    // ViewModel-scoped launch would be canceled before the bitmap lands on disk.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun observeNotes(): Flow<List<Note>> = noteDao.observeNotes()

    suspend fun getNote(noteId: String): Note? = withContext(Dispatchers.IO) {
        noteDao.getNote(noteId)
    }

    suspend fun getItems(noteId: String): List<NoteItem> = withContext(Dispatchers.IO) {
        noteDao.getItems(noteId)
    }

    suspend fun saveNote(note: Note, items: List<NoteItem>) = withContext(Dispatchers.IO) {
        noteDao.saveNote(note, items)
    }

    suspend fun deleteNote(noteId: String) = withContext(Dispatchers.IO) {
        // Item rows cascade via the foreign key, but the cached PNG isn't part
        // of the database — delete it explicitly so the files dir doesn't grow.
        thumbnailFile(noteId).delete()
        noteDao.deleteNote(noteId)
    }

    /**
     * Schedule a thumbnail render on the repository's background scope. Safe to
     * call from a ViewModel's `save()` path — the work outlives the ViewModel
     * because the scope is singleton-scoped.
     */
    fun renderThumbnailAsync(noteId: String) {
        backgroundScope.launch { renderThumbnail(noteId) }
    }

    /**
     * Render and persist the thumbnail for [noteId]. Atomic on disk (writes to
     * `<id>.png.tmp` then renames). Updates `thumbnailPath` on the note row so
     * the list can pick it up via the existing `observeNotes` flow.
     *
     * Returns the absolute path of the written PNG, or `null` if the note was
     * deleted between the lookup and the render.
     */
    suspend fun renderThumbnail(noteId: String): String? = withContext(Dispatchers.Default) {
        val note = noteDao.getNote(noteId) ?: return@withContext null
        val items = noteDao.getItems(noteId)
        // Thumbnails use a plain paper background — grid styling reads as noise
        // at 512px and would change every list cell's appearance subtly. Empty
        // notes drop through to ThumbnailRenderer's titled stub.
        val bitmap = NoteRasterizer.renderNote(
            note = note,
            items = items,
            maxEdgePx = ThumbnailRenderer.MAX_DIM_PX,
            backgroundStyle = BackgroundLayer.STYLE_PLAIN,
        ) ?: ThumbnailRenderer.renderStub(note)
        val dir = thumbnailDir().apply { if (!exists()) mkdirs() }
        val finalFile = File(dir, "$noteId.png")
        val tmpFile = File(dir, "$noteId.png.tmp")
        val written = try {
            FileOutputStream(tmpFile).use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            if (finalFile.exists()) finalFile.delete()
            tmpFile.renameTo(finalFile)
        } finally {
            bitmap.recycle()
        }
        if (!written) {
            tmpFile.delete()
            return@withContext null
        }
        val path = finalFile.absolutePath
        // Re-fetch in case the note was edited between the read and the write
        // — we only want to overwrite `thumbnailPath`, not whatever the user
        // changed in the meantime.
        val latest = noteDao.getNote(noteId)
        if (latest != null && latest.thumbnailPath != path) {
            noteDao.upsertNote(latest.copy(thumbnailPath = path))
        }
        path
    }

    /**
     * Backfill thumbnails for any persisted note whose `thumbnailPath` is null
     * or points at a file that's been deleted (e.g. user wiped app storage).
     * Runs on `Dispatchers.Default`; safe to call from a ViewModel init block.
     */
    suspend fun renderMissingThumbnails(): Int = withContext(Dispatchers.Default) {
        val candidates = noteDao.getNotesMissingThumbnail()
        var rendered = 0
        for (note in candidates) {
            val existing = note.thumbnailPath?.let(::File)
            if (existing != null && existing.exists()) continue
            if (renderThumbnail(note.id) != null) rendered++
        }
        rendered
    }

    private fun thumbnailDir(): File = File(context.filesDir, "note-thumbs")

    private fun thumbnailFile(noteId: String): File = File(thumbnailDir(), "$noteId.png")
}
