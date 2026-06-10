package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    /**
     * Standalone notes only — notebook pages (rows with non-null
     * `notebookId`) belong to the notebooks list (Phase 9.1), and icons
     * (`isIcon = 1`) belong to the Icons list.
     */
    @Query("SELECT * FROM notes WHERE notebookId IS NULL AND isIcon = 0 ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<Note>>

    /**
     * Standalone icons only — the dedicated Icons destination. Mirrors
     * [observeNotes] but selects the icon rows it now excludes.
     */
    @Query("SELECT * FROM notes WHERE notebookId IS NULL AND isIcon = 1 ORDER BY updatedAt DESC")
    fun observeIcons(): Flow<List<Note>>

    /** Pages owned by a single notebook, ordered for display. */
    @Query("SELECT * FROM notes WHERE notebookId = :notebookId ORDER BY createdAt ASC")
    suspend fun getNotesForNotebook(notebookId: String): List<Note>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNote(noteId: String): Note?

    /**
     * Reactive variant used by the chat-side pinned-note chip (sub-phase 4.4)
     * — the chip's title updates as the user renames the pinned note without
     * needing a chat-level refresh.
     */
    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun observeNote(noteId: String): Flow<Note?>

    /** Notes that have never been thumbnailed yet — used for the backfill pass. */
    @Query("SELECT * FROM notes WHERE thumbnailPath IS NULL")
    suspend fun getNotesMissingThumbnail(): List<Note>

    @Query("SELECT * FROM note_items WHERE noteId = :noteId ORDER BY zIndex ASC")
    suspend fun getItems(noteId: String): List<NoteItem>

    /**
     * MUST stay [Upsert] (insert-or-UPDATE), never `@Insert(REPLACE)`:
     * SQLite's `INSERT OR REPLACE` deletes the conflicting row before
     * re-inserting, which fires the `ON DELETE CASCADE` on note_items /
     * note_layers / note_frames / note_audio and silently wipes the note's
     * entire content.
     */
    @Upsert
    suspend fun upsertNote(note: Note)

    /**
     * Partial update for the thumbnail pipeline — only touches
     * `thumbnailPath` so a concurrent editor save can't be clobbered by a
     * stale full-row write.
     */
    @Query("UPDATE notes SET thumbnailPath = :path WHERE id = :noteId")
    suspend fun updateThumbnailPath(noteId: String, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<NoteItem>)

    @Query("DELETE FROM note_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<String>)

    @Query("DELETE FROM note_items WHERE noteId = :noteId")
    suspend fun deleteItemsForNote(noteId: String)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNote(noteId: String)

    /**
     * Partial update for the OCR pipeline (sub-phase 2.4). Bumps `updatedAt`
     * to invalidate any cached `Flow<List<Note>>` consumers without touching
     * the bytes the user actually edits (title, items, background style).
     */
    @Query("UPDATE notes SET ocrText = :text, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun updateOcrText(noteId: String, text: String, updatedAt: Long)

    // ── Layers (sub-phase 6.4) ───────────────────────────────────────────

    @Query("SELECT * FROM note_layers WHERE noteId = :noteId ORDER BY ordinal ASC")
    suspend fun getLayers(noteId: String): List<NoteLayer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLayers(layers: List<NoteLayer>)

    @Query("DELETE FROM note_layers WHERE id = :layerId")
    suspend fun deleteLayer(layerId: String)

    @Query("DELETE FROM note_layers WHERE noteId = :noteId")
    suspend fun deleteLayersForNote(noteId: String)

    @Transaction
    suspend fun saveNote(note: Note, items: List<NoteItem>) {
        upsertNote(note)
        deleteItemsForNote(note.id)
        if (items.isNotEmpty()) {
            upsertItems(items)
        }
    }

    /**
     * Sub-phase 6.4 — atomic save of note + items + layers. Layers are
     * persisted before items so the FK back-references stay valid throughout
     * the cascade.
     */
    @Transaction
    suspend fun saveNoteWithLayers(
        note: Note,
        items: List<NoteItem>,
        layers: List<NoteLayer>,
    ) {
        upsertNote(note)
        deleteItemsForNote(note.id)
        deleteLayersForNote(note.id)
        if (layers.isNotEmpty()) {
            upsertLayers(layers)
        }
        if (items.isNotEmpty()) {
            upsertItems(items)
        }
    }
}
