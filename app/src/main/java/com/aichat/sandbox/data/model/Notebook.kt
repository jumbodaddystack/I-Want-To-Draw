package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 9.1 — handwriting-notebook header.
 *
 * A notebook pins page size + style and owns exactly one underlying [Note]
 * (referenced via `Note.notebookId`). The 1:1 looks redundant but it lets the
 * existing item / frame / layer / rendering machinery stay untouched — a
 * notebook simply constrains the frames inside its note to be uniform pages.
 */
@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    /** "plain" | "dot" | "line" | "graph". Mirrors [Note.backgroundStyle]. */
    val pageStyle: String,
    /** World units; matches the bounds used by every page frame. */
    val pageWidth: Float,
    val pageHeight: Float,
    val defaultBrushPresetId: String? = null,
    val coverColorArgb: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** A4 portrait in world units (1 unit ≈ 1 px @ 72 dpi reference). */
        const val A4_PORTRAIT_WIDTH: Float = 595f
        const val A4_PORTRAIT_HEIGHT: Float = 842f

        /** US Letter portrait. */
        const val LETTER_PORTRAIT_WIDTH: Float = 612f
        const val LETTER_PORTRAIT_HEIGHT: Float = 792f

        /** Half-letter landscape. */
        const val HALF_LETTER_LANDSCAPE_WIDTH: Float = 612f
        const val HALF_LETTER_LANDSCAPE_HEIGHT: Float = 396f

        /** Gutter (world units) between consecutive pages. */
        const val PAGE_GUTTER: Float = 24f

        const val STYLE_PLAIN: String = "plain"
        const val STYLE_DOT: String = "dot"
        const val STYLE_LINE: String = "line"
        const val STYLE_GRAPH: String = "graph"
    }
}

/** Page size presets exposed in [NewNotebookSheet]. */
enum class NotebookPageSize(
    val displayName: String,
    val width: Float,
    val height: Float,
) {
    A4_PORTRAIT("A4 portrait", Notebook.A4_PORTRAIT_WIDTH, Notebook.A4_PORTRAIT_HEIGHT),
    LETTER_PORTRAIT("Letter portrait", Notebook.LETTER_PORTRAIT_WIDTH, Notebook.LETTER_PORTRAIT_HEIGHT),
    HALF_LETTER_LANDSCAPE(
        "Half-letter landscape",
        Notebook.HALF_LETTER_LANDSCAPE_WIDTH,
        Notebook.HALF_LETTER_LANDSCAPE_HEIGHT,
    ),
}
