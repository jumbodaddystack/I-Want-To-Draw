package com.aichat.sandbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.aichat.sandbox.data.local.NoteDao
import com.aichat.sandbox.data.local.NoteFrameDao
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.notes.HandwritingOcr
import com.aichat.sandbox.data.notes.NoteRasterizer
import com.aichat.sandbox.data.notes.OcrResult
import com.aichat.sandbox.data.notes.ThumbnailRenderer
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val noteFrameDao: NoteFrameDao,
    private val ocr: HandwritingOcr,
) {

    // Singleton-scoped background scope for fire-and-forget thumbnail and OCR
    // work. The editor ViewModel is cleared the moment the user navigates
    // back, so a ViewModel-scoped launch would be canceled before the bitmap
    // lands on disk (or the OCR text reaches the DB).
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Per-note debounce for `runOcrAsync` so a save burst (multi-stroke
    // session ending in a quick title edit) coalesces into one recognizer
    // call. Recognition itself is fast; the model-download lifecycle is the
    // expensive bit and ML Kit already memoizes that internally.
    private val lastOcrAt = ConcurrentHashMap<String, Long>()
    private val ocrLocks = ConcurrentHashMap<String, Mutex>()

    private val _ocrJobsInFlight = MutableStateFlow<Set<String>>(emptySet())

    /** Set of note ids currently mid-OCR. The editor watches this to decide
     *  whether to render its "transcribing…" indicator. */
    val ocrJobsInFlight: StateFlow<Set<String>> = _ocrJobsInFlight.asStateFlow()

    fun observeNotes(): Flow<List<Note>> = noteDao.observeNotes()

    fun observeNote(noteId: String): Flow<Note?> = noteDao.observeNote(noteId)

    suspend fun getNote(noteId: String): Note? = withContext(Dispatchers.IO) {
        noteDao.getNote(noteId)
    }

    suspend fun getItems(noteId: String): List<NoteItem> = withContext(Dispatchers.IO) {
        noteDao.getItems(noteId)
    }

    suspend fun saveNote(note: Note, items: List<NoteItem>) = withContext(Dispatchers.IO) {
        noteDao.saveNote(note, items)
    }

    /** Sub-phase 6.4 — atomic save including the layer list. */
    suspend fun saveNoteWithLayers(
        note: Note,
        items: List<NoteItem>,
        layers: List<NoteLayer>,
    ) = withContext(Dispatchers.IO) {
        noteDao.saveNoteWithLayers(note, items, layers)
    }

    suspend fun getLayers(noteId: String): List<NoteLayer> = withContext(Dispatchers.IO) {
        noteDao.getLayers(noteId)
    }

    /** Sub-phase 8.1 — load the frame list (ordered) for [noteId]. */
    suspend fun getFrames(noteId: String): List<NoteFrame> = withContext(Dispatchers.IO) {
        noteFrameDao.getFrames(noteId)
    }

    /**
     * Sub-phase 8.1 — replace the frame set for [noteId] atomically.
     * Deletes any frame on this note not present in [frames] and upserts
     * the rest. Empty list clears every frame.
     */
    suspend fun saveFrames(noteId: String, frames: List<NoteFrame>) = withContext(Dispatchers.IO) {
        noteFrameDao.deleteFramesForNote(noteId)
        if (frames.isNotEmpty()) noteFrameDao.upsertFrames(frames)
    }

    suspend fun deleteNote(noteId: String) = withContext(Dispatchers.IO) {
        // Item rows cascade via the foreign key, but the cached PNG isn't part
        // of the database — delete it explicitly so the files dir doesn't grow.
        thumbnailFile(noteId).delete()
        // Sub-phase 6.7 — collect referenced image paths *before* the cascade
        // removes the rows, then unlink each file. Orphan files left after a
        // crash are swept by [com.aichat.sandbox.data.notes.NoteImageStore.sweepOrphans].
        val items = noteDao.getItems(noteId)
        for (item in items) {
            if (item.kind != NoteItem.KIND_IMAGE) continue
            val path = com.aichat.sandbox.ui.components.notes.ImageItemCodec
                .decodeRelativePath(item.payload) ?: continue
            File(context.filesDir, path).delete()
        }
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

    /**
     * Observable lifecycle for the ML Kit Digital Ink model — wired through so
     * the editor TopAppBar can show "downloading…" on first launch without
     * pulling [HandwritingOcr] into the UI layer.
     */
    val ocrModelState = ocr.state

    /**
     * Run handwriting OCR over the persisted strokes of [noteId] and write
     * the recognized text back to `Note.ocrText`. Returns the [OcrResult] so
     * callers (e.g. the AI service in 2.5) can use the per-word alternates
     * without re-querying. Safe to call on `Dispatchers.IO`; the model itself
     * runs off the main thread regardless.
     *
     * Failures are non-fatal: an empty result is written through (or left
     * untouched if the recognizer flat-out errored) so the note still saves
     * cleanly. The OCR text is intentionally allowed to be empty for
     * stroke-less / doodle-only notes — vision models don't need it.
     */
    suspend fun runOcr(noteId: String): OcrResult = withContext(Dispatchers.Default) {
        // Per-note mutex prevents two save-driven OCR jobs from racing on the
        // same row; a global mutex would needlessly serialize background work
        // across unrelated notes.
        val lock = ocrLocks.getOrPut(noteId) { Mutex() }
        lock.withLock {
            _ocrJobsInFlight.update { it + noteId }
            try {
                val items = noteDao.getItems(noteId).filter { it.kind == STROKE_KIND }
                if (items.isEmpty()) return@withLock OcrResult.EMPTY
                val result = ocr.recognize(items)
                // Only write through if we actually got text — clobbering a
                // good prior transcription with an empty string on a transient
                // model failure would silently break the non-vision AI
                // fallback (sub-phase 2.5).
                if (result.text.isNotEmpty()) {
                    noteDao.updateOcrText(noteId, result.text, System.currentTimeMillis())
                }
                result
            } finally {
                _ocrJobsInFlight.update { it - noteId }
            }
        }
    }

    /**
     * Fire-and-forget OCR with a 2-second per-note debounce. Repeated calls
     * inside the window are dropped; the next call after the window opens
     * runs again. Used by `NoteEditorViewModel.save` so the user isn't held on
     * the save path while ML Kit grinds.
     *
     * Returns `true` if a job was scheduled, `false` if the call was
     * debounced.
     */
    fun runOcrAsync(noteId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastOcrAt[noteId]
        if (last != null && now - last < OCR_DEBOUNCE_MS) return false
        lastOcrAt[noteId] = now
        backgroundScope.launch { runOcr(noteId) }
        return true
    }

    private fun thumbnailDir(): File = File(context.filesDir, "note-thumbs")

    private fun thumbnailFile(noteId: String): File = File(thumbnailDir(), "$noteId.png")

    companion object {
        private const val STROKE_KIND: String = "stroke"

        /** Per-note debounce window applied by [runOcrAsync]. */
        const val OCR_DEBOUNCE_MS: Long = 2_000L
    }
}
