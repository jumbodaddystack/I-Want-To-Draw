package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 8.3 — saved selection ("stamp") that can be dropped into any
 * note as a reusable object. Stamps are app-scoped (not per-note) so the
 * user can build a personal library and reuse marks across projects.
 *
 * The [payloadJson] field is a [com.aichat.sandbox.data.notes.VectorCanvasJson]
 * blob captured from the source selection. On insert we deserialise and
 * regenerate fresh UUIDs so two inserts of the same stamp don't collide.
 *
 * Images referenced by the stamp live under
 * `filesDir/stamp-images/<stampId>/` so deleting a source note doesn't
 * orphan the stamp's bitmap dependencies — the stamp owns its own copies.
 */
@Entity(
    tableName = "stamps",
    indices = [Index("lastUsedAt")],
)
data class Stamp(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val thumbnailPath: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)
