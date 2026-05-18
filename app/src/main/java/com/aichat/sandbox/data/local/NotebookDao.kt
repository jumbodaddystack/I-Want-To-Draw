package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.Notebook
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeNotebooks(): Flow<List<Notebook>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getNotebook(id: String): Notebook?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: Notebook)

    @Query("UPDATE notebooks SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE notebooks SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, updatedAt: Long)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    /** Total frame count over the underlying note — used for the cover's "N pages" caption. */
    @Query(
        "SELECT COUNT(*) FROM note_frames WHERE noteId IN " +
            "(SELECT id FROM notes WHERE notebookId = :notebookId)"
    )
    suspend fun pageCount(notebookId: String): Int

    /** Underlying note id for [notebookId] — the editor navigates by note id. */
    @Query("SELECT id FROM notes WHERE notebookId = :notebookId LIMIT 1")
    suspend fun underlyingNoteId(notebookId: String): String?
}
