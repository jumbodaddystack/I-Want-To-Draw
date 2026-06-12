package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.NoteFrameDao
import com.aichat.sandbox.data.local.NoteSearchDao
import com.aichat.sandbox.data.local.NoteSearchResultRow
import com.aichat.sandbox.data.local.NotebookDao
import com.aichat.sandbox.data.model.Notebook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 9.3 — cross-notebook OCR search.
 *
 * Wraps the FTS4 DAO with input sanitisation (FTS4 chokes on unbalanced
 * quotes and operators) and stitches the notebook + page metadata onto each
 * row so the caller can route the user to the right page in the scroller.
 */
@Singleton
class NoteSearchRepository @Inject constructor(
    private val searchDao: NoteSearchDao,
    private val notebookDao: NotebookDao,
    private val noteFrameDao: NoteFrameDao,
) {

    /**
     * Search `notes.ocrText` for [query]. Treats every alphanumeric run as
     * a prefix term (`foo bar` → `foo* bar*`) which is what users expect
     * when they type "rea" and want notes containing "react" / "real".
     *
     * Empty / whitespace-only queries return an empty list rather than
     * matching every row.
     */
    suspend fun search(query: String, limit: Int = 100): List<NoteSearchResult> =
        withContext(Dispatchers.IO) {
            val match = sanitizeQuery(query) ?: return@withContext emptyList()
            val rows = searchDao.search(match, limit)
            if (rows.isEmpty()) return@withContext emptyList()
            // Fetch the notebook header per result; we don't have many results
            // (limit defaults to 100) so the N+1 is fine for v1.
            val notebookCache = HashMap<String, Notebook?>()
            rows.map { row -> toResult(row, notebookCache) }
        }

    /**
     * Phase 16.3 — same FTS search restricted to icon notes, returning the
     * full [com.aichat.sandbox.data.model.Note] rows the Icons gallery
     * grid renders. Empty / whitespace queries return an empty list.
     */
    suspend fun searchIcons(query: String, limit: Int = 100) =
        withContext(Dispatchers.IO) {
            val match = sanitizeQuery(query) ?: return@withContext emptyList()
            searchDao.searchIcons(match, limit)
        }

    private suspend fun toResult(
        row: NoteSearchResultRow,
        notebookCache: HashMap<String, Notebook?>,
    ): NoteSearchResult {
        val notebook = row.notebookId?.let { id ->
            if (!notebookCache.containsKey(id)) {
                notebookCache[id] = notebookDao.getNotebook(id)
            }
            notebookCache[id]
        }
        // Page ordinal hint: if this is a notebook page, we report the
        // first page (ordinal 0) as the jump target. A proper per-snippet
        // page resolver would re-OCR the bytes against each frame's
        // bounds; that's deferred — search-to-page accuracy is "open the
        // notebook and scroll", which the UI does on its own.
        val pageOrdinal = if (notebook != null) {
            noteFrameDao.getFrames(row.noteId).minByOrNull { it.ordinal }?.ordinal ?: 0
        } else 0
        return NoteSearchResult(
            noteId = row.noteId,
            title = row.title,
            notebook = notebook,
            pageOrdinal = pageOrdinal,
            updatedAt = row.updatedAt,
            thumbnailPath = row.thumbnailPath,
            snippet = row.snippet,
        )
    }

    /**
     * Convert a free-form query into an FTS4 MATCH expression. Strips
     * everything that isn't an alphanumeric / underscore, wraps each
     * remaining run as a prefix term. Returns `null` when no terms remain.
     */
    private fun sanitizeQuery(query: String): String? {
        val terms = query
            .split(Regex("[^A-Za-z0-9_]+"))
            .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.joinToString(separator = " ") { "$it*" }
    }
}

/** Sub-phase 9.3 search row, post-join with the notebook header. */
data class NoteSearchResult(
    val noteId: String,
    val title: String,
    val notebook: Notebook?,
    val pageOrdinal: Int,
    val updatedAt: Long,
    val thumbnailPath: String?,
    val snippet: String,
)
