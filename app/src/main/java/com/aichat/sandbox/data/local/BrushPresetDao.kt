package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.BrushPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface BrushPresetDao {

    /** All presets across both scopes, ordered for the palette chip row. */
    @Query("SELECT * FROM brush_presets ORDER BY tool ASC, ownerScope ASC, ordinal ASC")
    fun observeAll(): Flow<List<BrushPreset>>

    @Query("SELECT * FROM brush_presets WHERE tool = :tool ORDER BY ownerScope ASC, ordinal ASC")
    suspend fun forTool(tool: String): List<BrushPreset>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: BrushPreset)

    @Query("DELETE FROM brush_presets WHERE id = :id AND ownerScope = 'user'")
    suspend fun deleteUserPreset(id: String)
}
