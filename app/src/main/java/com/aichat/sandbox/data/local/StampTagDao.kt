package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aichat.sandbox.data.model.StampTag
import kotlinx.coroutines.flow.Flow

/**
 * Phase 17.5 follow-on — tag access for the stamp library, mirroring
 * [NoteTagDao]. All writes go through [setTags] (delete + insert in one
 * transaction) so a stamp's tag set is replaced atomically; rows cascade with
 * the stamp.
 */
@Dao
interface StampTagDao {

    @Query("SELECT tag FROM stamp_tags WHERE stampId = :stampId ORDER BY tag ASC")
    suspend fun getTagsFor(stampId: String): List<String>

    /** Every (stamp, tag) row — folded into a `stampId → tags` map for the drawer. */
    @Query("SELECT * FROM stamp_tags ORDER BY tag ASC")
    fun observeAll(): Flow<List<StampTag>>

    /**
     * Distinct tags carried by at least one stamp, with their counts — feeds
     * the drawer's filter-chip row.
     */
    @Query(
        """
        SELECT tag AS tag, COUNT(*) AS count
        FROM stamp_tags
        GROUP BY tag
        ORDER BY tag ASC
        """
    )
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("DELETE FROM stamp_tags WHERE stampId = :stampId")
    suspend fun deleteTagsFor(stampId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<StampTag>)

    /** Replace the full tag set for [stampId] atomically. Empty list clears. */
    @Transaction
    suspend fun setTags(stampId: String, tags: List<String>) {
        deleteTagsFor(stampId)
        if (tags.isNotEmpty()) {
            insertTags(tags.map { StampTag(stampId = stampId, tag = it) })
        }
    }
}
