package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    /**
     * Standalone notes only — notebook pages (rows with non-null
     * `notebookId`) belong to the notebooks list (Phase 9.1).
     */
    @Query("SELECT * FROM notes WHERE notebookId IS NULL ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<Note>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: Note)

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
