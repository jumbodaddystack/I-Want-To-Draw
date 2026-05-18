package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.NoteFrame

@Dao
interface NoteFrameDao {

    @Query("SELECT * FROM note_frames WHERE noteId = :noteId ORDER BY ordinal ASC")
    suspend fun getFrames(noteId: String): List<NoteFrame>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFrames(frames: List<NoteFrame>)

    @Query("DELETE FROM note_frames WHERE id = :frameId")
    suspend fun deleteFrame(frameId: String)

    @Query("DELETE FROM note_frames WHERE noteId = :noteId")
    suspend fun deleteFramesForNote(noteId: String)
}
