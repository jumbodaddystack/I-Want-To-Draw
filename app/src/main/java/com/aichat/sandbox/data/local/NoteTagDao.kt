package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteTag
import kotlinx.coroutines.flow.Flow

/**
 * Phase 17.1 — tag access for the Icons gallery. All write paths go through
 * [setTags] (delete + insert in one transaction) so a note's tag set is
 * always replaced atomically; rows cascade-delete with their note.
 */
@Dao
interface NoteTagDao {

    @Query("SELECT tag FROM note_tags WHERE noteId = :noteId ORDER BY tag ASC")
    suspend fun getTagsFor(noteId: String): List<String>

    @Query("SELECT tag FROM note_tags WHERE noteId = :noteId ORDER BY tag ASC")
    fun observeTagsFor(noteId: String): Flow<List<String>>

    /**
     * Every distinct tag carried by at least one icon, with its icon count —
     * feeds the gallery's filter-chip row. Joined against `notes` so a tag
     * whose only bearer stopped being an icon (or was deleted mid-cascade)
     * never renders a dead chip.
     */
    @Query(
        """
        SELECT t.tag AS tag, COUNT(*) AS count
        FROM note_tags t
        JOIN notes n ON n.id = t.noteId
        WHERE n.isIcon = 1
        GROUP BY t.tag
        ORDER BY t.tag ASC
        """
    )
    fun observeIconTagCounts(): Flow<List<TagCount>>

    /** Ids only — used to intersect FTS search results with a tag filter. */
    @Query("SELECT noteId FROM note_tags WHERE tag = :tag")
    suspend fun getNoteIdsWithTag(tag: String): List<String>

    /**
     * Icons carrying [tag], in the gallery's usual recency order. Mirrors
     * `NoteDao.observeIcons` plus the junction join.
     */
    @Query(
        """
        SELECT n.* FROM notes n
        JOIN note_tags t ON t.noteId = n.id
        WHERE t.tag = :tag AND n.notebookId IS NULL AND n.isIcon = 1
        ORDER BY n.updatedAt DESC
        """
    )
    fun observeIconsWithTag(tag: String): Flow<List<Note>>

    @Query("DELETE FROM note_tags WHERE noteId = :noteId")
    suspend fun deleteTagsFor(noteId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<NoteTag>)

    /** Replace the full tag set for [noteId] atomically. Empty list clears. */
    @Transaction
    suspend fun setTags(noteId: String, tags: List<String>) {
        deleteTagsFor(noteId)
        if (tags.isNotEmpty()) {
            insertTags(tags.map { NoteTag(noteId = noteId, tag = it) })
        }
    }
}

/** One gallery filter chip: the tag plus how many icons carry it. */
data class TagCount(
    val tag: String,
    val count: Int,
)
