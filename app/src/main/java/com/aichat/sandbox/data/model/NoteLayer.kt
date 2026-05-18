package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 6.4 — per-note layer entity.
 *
 * Each note carries one or more layers. Items reference a layer via
 * [NoteItem.layerId]; `null` resolves to a virtual "default layer" with
 * `opacityPercent=100`, `visible=true`, `locked=false`, `ordinal=Int.MAX_VALUE`
 * so legacy / unparented items always render on top until the user assigns
 * them somewhere.
 *
 * Render order is `(ordinal ASC, NoteItem.zIndex ASC)` — the layer with the
 * lowest ordinal renders first (i.e. underneath), so the layer panel's
 * top-of-list convention (newest layers on top) maps cleanly to
 * `ordinal = currentMax + 1`.
 */
@Entity(
    tableName = "note_layers",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId")],
)
data class NoteLayer(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val name: String,
    val opacityPercent: Int,   // 0..100
    val visible: Boolean,
    val locked: Boolean,
    val ordinal: Int,
)
