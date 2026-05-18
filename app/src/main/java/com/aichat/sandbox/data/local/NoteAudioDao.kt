package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.NoteAudio
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteAudioDao {

    @Query("SELECT * FROM note_audio WHERE noteId = :noteId ORDER BY recordedAt ASC")
    fun observeAudio(noteId: String): Flow<List<NoteAudio>>

    @Query("SELECT * FROM note_audio WHERE noteId = :noteId ORDER BY recordedAt ASC")
    suspend fun getAudio(noteId: String): List<NoteAudio>

    @Query("SELECT * FROM note_audio WHERE id = :id")
    suspend fun getAudioById(id: String): NoteAudio?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(audio: NoteAudio)

    @Query("DELETE FROM note_audio WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM note_audio WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)

    /** All recorded audio rows, used by the launch-time orphan sweep. */
    @Query("SELECT * FROM note_audio")
    suspend fun getAll(): List<NoteAudio>
}
