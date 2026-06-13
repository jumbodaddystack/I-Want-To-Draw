package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Phase 17.1 — icon tags. One row per (note, tag) pair: a junction table
 * rather than a CSV column on `notes`, so per-tag counts and tag
 * rename/delete stay cheap and the `notes` entity (plus its FTS sync
 * triggers) is untouched.
 *
 * Tags are stored normalized (trimmed, lowercased — see
 * [com.aichat.sandbox.data.notes.IconTags.normalize]) so the gallery's
 * chip-filter and count queries can match by plain equality.
 */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tag")],
)
data class NoteTag(
    val noteId: String,
    val tag: String,
)
