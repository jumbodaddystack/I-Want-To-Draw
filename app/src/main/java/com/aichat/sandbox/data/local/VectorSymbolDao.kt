package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.VectorSymbolEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 5 (sub-feature 3) — DAO for the reusable vector-symbol library. Mirrors
 * [StampDao]: a most-recently-used-first observable list plus CRUD.
 */
@Dao
interface VectorSymbolDao {

    /** All symbols ordered by lastUsedAt DESC (with newly-created first). */
    @Query("""
        SELECT * FROM vector_symbols
        ORDER BY COALESCE(lastUsedAt, createdAt) DESC
    """)
    fun observeAll(): Flow<List<VectorSymbolEntity>>

    /** One-shot snapshot of every symbol — used to build the resolver library map. */
    @Query("SELECT * FROM vector_symbols")
    suspend fun observeAllOnce(): List<VectorSymbolEntity>

    @Query("SELECT * FROM vector_symbols WHERE id = :symbolId")
    suspend fun getSymbol(symbolId: String): VectorSymbolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(symbol: VectorSymbolEntity)

    @Query("UPDATE vector_symbols SET lastUsedAt = :timestamp WHERE id = :symbolId")
    suspend fun touchLastUsed(symbolId: String, timestamp: Long)

    @Query("UPDATE vector_symbols SET name = :name WHERE id = :symbolId")
    suspend fun rename(symbolId: String, name: String)

    @Query("DELETE FROM vector_symbols WHERE id = :symbolId")
    suspend fun delete(symbolId: String)
}
