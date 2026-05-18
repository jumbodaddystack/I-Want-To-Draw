package com.aichat.sandbox.data.repository

import android.content.Context
import com.aichat.sandbox.data.local.NoteAudioDao
import com.aichat.sandbox.data.local.NoteDao
import com.aichat.sandbox.data.local.NoteFrameDao
import com.aichat.sandbox.data.local.NotebookDao
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.data.model.NotebookPageSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 9.1 — notebook lifecycle.
 *
 * Creating a notebook spins up a paired [Note] (carrying `notebookId`) and a
 * first [NoteFrame] sized to the requested page. Deletion cascades through
 * the FK chain (`notebook` → `note` → `note_items` / `note_frames` /
 * `note_audio`) and also wipes auxiliary on-disk assets the FK doesn't
 * cover (thumbnails, image dependencies, audio files).
 */
@Singleton
class NotebookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notebookDao: NotebookDao,
    private val noteDao: NoteDao,
    private val noteFrameDao: NoteFrameDao,
    private val noteAudioDao: NoteAudioDao,
) {

    fun observeNotebooks(): Flow<List<Notebook>> = notebookDao.observeNotebooks()

    suspend fun getNotebook(id: String): Notebook? = withContext(Dispatchers.IO) {
        notebookDao.getNotebook(id)
    }

    suspend fun pageCount(notebookId: String): Int = withContext(Dispatchers.IO) {
        notebookDao.pageCount(notebookId)
    }

    suspend fun underlyingNoteId(notebookId: String): String? = withContext(Dispatchers.IO) {
        notebookDao.underlyingNoteId(notebookId)
    }

    /**
     * Create a notebook with its first page. Returns the new notebook +
     * the id of the underlying note so the caller can navigate straight in.
     */
    suspend fun createNotebook(
        title: String,
        pageSize: NotebookPageSize,
        pageStyle: String,
        coverColorArgb: Int,
    ): NotebookCreation = withContext(Dispatchers.IO) {
        val notebook = Notebook(
            title = title.ifBlank { "Untitled notebook" },
            pageStyle = pageStyle,
            pageWidth = pageSize.width,
            pageHeight = pageSize.height,
            coverColorArgb = coverColorArgb,
        )
        notebookDao.upsert(notebook)
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = notebook.title,
            backgroundStyle = pageStyle,
            schemaVersion = 1,
            minX = 0f,
            minY = 0f,
            maxX = pageSize.width,
            maxY = pageSize.height,
            thumbnailPath = null,
            ocrText = null,
            notebookId = notebook.id,
        )
        // First page lives at the world origin; subsequent pages stack
        // downwards in the notebook scroller (Phase 9.2).
        val firstPage = NoteFrame(
            id = UUID.randomUUID().toString(),
            noteId = note.id,
            name = "Page 1",
            minX = 0f,
            minY = 0f,
            maxX = pageSize.width,
            maxY = pageSize.height,
            ordinal = 0,
        )
        noteDao.upsertNote(note)
        noteFrameDao.upsertFrames(listOf(firstPage))
        NotebookCreation(notebook = notebook, noteId = note.id)
    }

    suspend fun rename(notebookId: String, title: String) = withContext(Dispatchers.IO) {
        val sanitized = title.trim().ifBlank { "Untitled notebook" }
        val now = System.currentTimeMillis()
        notebookDao.rename(notebookId, sanitized, now)
        // Mirror the title onto the underlying note row so the notes table
        // — and thumbnails / OCR scoped to the note — stay consistent.
        val pages = noteDao.getNotesForNotebook(notebookId)
        for (page in pages) {
            noteDao.upsertNote(page.copy(title = sanitized, updatedAt = now))
        }
    }

    suspend fun touchUpdatedAt(notebookId: String) = withContext(Dispatchers.IO) {
        notebookDao.touchUpdatedAt(notebookId, System.currentTimeMillis())
    }

    /**
     * Delete the notebook and all its assets. The FK cascade handles the
     * rows; we sweep the on-disk dependencies (thumbnails, image deps,
     * audio files) ourselves so [filesDir] doesn't accrete orphans.
     */
    suspend fun delete(notebookId: String) = withContext(Dispatchers.IO) {
        val pages = noteDao.getNotesForNotebook(notebookId)
        for (page in pages) {
            // Audio files first (FK cascade would drop the rows but not the
            // bytes on disk).
            val audio = noteAudioDao.getAudio(page.id)
            for (clip in audio) File(clip.filePath).delete()
            // Thumbnail.
            File(File(context.filesDir, "note-thumbs"), "${page.id}.png").delete()
            // Image deps (kind = "image"; payload references relative path).
            val items = noteDao.getItems(page.id)
            for (item in items) {
                if (item.kind != com.aichat.sandbox.data.model.NoteItem.KIND_IMAGE) continue
                val path = com.aichat.sandbox.ui.components.notes.ImageItemCodec
                    .decodeRelativePath(item.payload) ?: continue
                File(context.filesDir, path).delete()
            }
            noteDao.deleteNote(page.id)
        }
        notebookDao.delete(notebookId)
    }

    data class NotebookCreation(val notebook: Notebook, val noteId: String)
}
