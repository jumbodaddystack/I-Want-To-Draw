package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "note_items",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId"), Index("noteId", "zIndex")],
)
data class NoteItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val zIndex: Int,
    val kind: String,        // "stroke" | "text"
    val tool: String?,       // for strokes: "pen" | "highlighter" | "pencil" | "eraser"
    val colorArgb: Int,
    val baseWidthPx: Float,
    // Strokes: packed binary points (x,y,p,tilt). Text: UTF-8 body, font/size encoded.
    val payload: ByteArray,
) {
    // Hand-written equals/hashCode because ByteArray uses reference equality by default.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoteItem) return false
        if (id != other.id) return false
        if (noteId != other.noteId) return false
        if (zIndex != other.zIndex) return false
        if (kind != other.kind) return false
        if (tool != other.tool) return false
        if (colorArgb != other.colorArgb) return false
        if (baseWidthPx != other.baseWidthPx) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + zIndex
        result = 31 * result + kind.hashCode()
        result = 31 * result + (tool?.hashCode() ?: 0)
        result = 31 * result + colorArgb
        result = 31 * result + baseWidthPx.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
