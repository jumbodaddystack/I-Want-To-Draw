package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

@Dao
interface NoteSearchDao {

    /**
     * Sub-phase 9.3 — cross-note + cross-notebook search over `title` +
     * handwriting OCR text (the index gained the title column in v18).
     *
     * Joins `notes_ocr_fts` back to `notes` to pull the metadata the UI
     * needs to render the row. The FTS4 `snippet()` builtin returns a
     * highlighted excerpt with `<b>` markers around matched terms; the `-1`
     * column argument lets it pick whichever column actually matched, so a
     * title-only hit shows the highlighted title instead of an empty string.
     *
     * Pattern argument follows FTS4 MATCH syntax — typical use is a
     * pre-sanitized `prefix*` term per word.
     *
     * `notes_ocr_fts` is created by `createNotesSearchIndex` (Migrations.kt)
     * and not registered as a Room entity, so Room's compile-time query
     * verifier doesn't know the table exists. `@SkipQueryVerification`
     * bypasses that check.
     */
    @SkipQueryVerification
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

    /**
     * Phase 16.3 — the Icons gallery's filter: same FTS index, restricted
     * to icon notes, returning full [com.aichat.sandbox.data.model.Note]
     * rows so the gallery grid renders them exactly like the unfiltered
     * list (thumbnail, title, artboard dimensions).
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT n.* FROM notes_ocr_fts
        JOIN notes n ON n.rowid = notes_ocr_fts.docid
        WHERE notes_ocr_fts MATCH :match AND n.isIcon = 1
        ORDER BY n.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchIcons(match: String, limit: Int = 100): List<com.aichat.sandbox.data.model.Note>
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
