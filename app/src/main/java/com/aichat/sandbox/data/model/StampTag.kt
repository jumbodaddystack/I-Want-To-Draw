package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Phase 17.5 follow-on — stamp-library tags, mirroring the Phase 17.1
 * [NoteTag] model. One row per (stamp, tag) pair: a junction table rather than
 * a CSV column on `stamps`, so per-tag counts and tag rename/delete stay cheap
 * and the `stamps` entity is untouched. Rows cascade-delete with their stamp.
 *
 * Tags are stored normalized (trimmed, lowercased — see
 * [com.aichat.sandbox.data.notes.IconTags.normalize], shared with icons) so
 * the drawer's search and chip filter can match by plain equality.
 */
@Entity(
    tableName = "stamp_tags",
    primaryKeys = ["stampId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = Stamp::class,
            parentColumns = ["id"],
            childColumns = ["stampId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tag")],
)
data class StampTag(
    val stampId: String,
    val tag: String,
)
