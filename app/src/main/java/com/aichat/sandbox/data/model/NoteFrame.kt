package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 8.1 — frame primitive.
 *
 * A named rectangle living inside a note's infinite canvas. Frames do not
 * clip rendering — items keep their global positions; the frame's role is
 * twofold:
 *
 *  - It defines an exportable region. `NoteRasterizer.renderForFrame` and the
 *    SVG / PNG / PDF exporters can bound their render to a frame's rect so
 *    "Export this frame" lands a clean crop.
 *  - It is the substrate Phase 9 uses for notebook pages — a notebook is just
 *    a note whose frames are uniform-size and rendered sequentially.
 *
 * Items are not scoped to a frame; "contains" is computed lazily via
 * bounding-box intersection at export time.
 */
@Entity(
    tableName = "note_frames",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId"), Index("noteId", "ordinal")],
)
data class NoteFrame(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val name: String,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val ordinal: Int,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** World-space bounds rect as `[minX, minY, maxX, maxY]`. */
    fun bounds(): FloatArray = floatArrayOf(minX, minY, maxX, maxY)
}
