package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.Stamp
import kotlinx.coroutines.flow.Flow

@Dao
interface StampDao {

    /** All stamps ordered by lastUsedAt DESC (with newly-created first). */
    @Query("""
        SELECT * FROM stamps
        ORDER BY COALESCE(lastUsedAt, createdAt) DESC
    """)
    fun observeAll(): Flow<List<Stamp>>

    @Query("SELECT * FROM stamps WHERE id = :stampId")
    suspend fun getStamp(stampId: String): Stamp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stamp: Stamp)

    @Query("UPDATE stamps SET lastUsedAt = :timestamp WHERE id = :stampId")
    suspend fun touchLastUsed(stampId: String, timestamp: Long)

    @Query("UPDATE stamps SET name = :name WHERE id = :stampId")
    suspend fun rename(stampId: String, name: String)

    @Query("DELETE FROM stamps WHERE id = :stampId")
    suspend fun delete(stampId: String)
}
