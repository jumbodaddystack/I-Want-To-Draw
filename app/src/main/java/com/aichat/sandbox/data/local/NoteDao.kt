package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNote(noteId: String): Note?

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

    @Transaction
    suspend fun saveNote(note: Note, items: List<NoteItem>) {
        upsertNote(note)
        deleteItemsForNote(note.id)
        if (items.isNotEmpty()) {
            upsertItems(items)
        }
    }
}
