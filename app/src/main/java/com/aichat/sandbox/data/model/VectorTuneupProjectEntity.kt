package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Phase 6 — a durable Vector Art Tune-Up project.
 *
 * A project owns the exact [sourceXml] that was imported and a tree of
 * [VectorTuneupVersionEntity] rows (the original plus every generated
 * candidate). [activeVersionId] points at the version currently surfaced in the
 * workspace; it is left null for the brief window between inserting the project
 * row and inserting the original version, then maintained by app logic. There
 * is intentionally no foreign key on [activeVersionId] (it would create a
 * circular project↔version dependency at creation time).
 */
@Entity(
    tableName = "vector_tuneup_projects",
    indices = [Index("updatedAt")],
)
data class VectorTuneupProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceXml: String,
    val activeVersionId: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
