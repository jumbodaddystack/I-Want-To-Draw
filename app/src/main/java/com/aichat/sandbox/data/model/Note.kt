package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val backgroundStyle: String,   // "plain" | "dot" | "line" | "graph"
    val schemaVersion: Int,        // bump when stroke binary format changes
    // Stroke geometry bounds, used for thumbnails & initial viewport.
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val thumbnailPath: String?,    // cached PNG in app files dir
    val ocrText: String?,          // most-recent OCR pass for search / non-vision AI fallback
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Phase 5.2 — serialized `EditorAction` stack (past + future), JSON.
    // Null on rows created before schema v6 and on notes that have never
    // had an editor session. See `EditorActionCodec`.
    val undoLogJson: String? = null,
)
