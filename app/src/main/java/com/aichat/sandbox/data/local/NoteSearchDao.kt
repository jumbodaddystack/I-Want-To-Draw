package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Query

@Dao
interface NoteSearchDao {

    /**
     * Sub-phase 9.3 — cross-note + cross-notebook OCR search.
     *
     * Joins `notes_ocr_fts` back to `notes` to pull the metadata the UI
     * needs to render the row. The FTS4 `snippet()` builtin returns a
     * highlighted excerpt with `<b>` markers around matched terms.
     *
     * Pattern argument follows FTS4 MATCH syntax — typical use is a
     * pre-sanitized `prefix*` term per word.
     */
    @Query(
        """
        SELECT n.id AS noteId,
               n.title AS title,
               n.notebookId AS notebookId,
               n.updatedAt AS updatedAt,
               n.thumbnailPath AS thumbnailPath,
               snippet(notes_ocr_fts, '<b>', '</b>', '…', -1, 16) AS snippet
        FROM notes_ocr_fts
        JOIN notes n ON n.rowid = notes_ocr_fts.docid
        WHERE notes_ocr_fts MATCH :match
        ORDER BY n.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun search(match: String, limit: Int = 100): List<NoteSearchResultRow>
}

/**
 * Sub-phase 9.3 search row. Carries enough to display the result and route
 * the user back to the right note + page in the notebook scroller.
 */
data class NoteSearchResultRow(
    val noteId: String,
    val title: String,
    val notebookId: String?,
    val updatedAt: Long,
    val thumbnailPath: String?,
    val snippet: String,
)
