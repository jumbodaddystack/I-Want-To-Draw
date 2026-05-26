package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * How a [VectorTuneupVersionEntity] was produced. Persisted as a [String]
 * (the enum name) for Room friendliness; the repository maps it back to this
 * enum for the domain/UI layers.
 */
enum class VectorTuneupMode {
    ORIGINAL,
    OPTIMIZE,
    AI_TUNE_UP,
    AI_REDRAW,
    MANUAL_EDIT,
}

/**
 * Phase 6 — one persisted version in a Vector Art Tune-Up project.
 *
 * Every version records its [parentId] (null only for the [VectorTuneupMode.ORIGINAL]
 * root) so the workspace can show lineage and branch from any prior version.
 * [metricsJson]/[warningsJson] are Gson blobs of the deterministic
 * `VectorMetrics` / `List<VectorWarning>` foundation; [editPlanJson]/[sceneJson]
 * carry the AI artefacts when present. [previewPngPath] is reserved for the
 * still-deferred raster preview cache — it stays null in Phase 6.
 */
@Entity(
    tableName = "vector_tuneup_versions",
    foreignKeys = [
        ForeignKey(
            entity = VectorTuneupProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index("parentId"),
        Index("createdAt"),
    ],
)
data class VectorTuneupVersionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val parentId: String?,
    val label: String,
    val instruction: String,
    val mode: String,
    val xml: String,
    val metricsJson: String,
    val warningsJson: String,
    val reportSummary: String?,
    val editPlanJson: String?,
    val sceneJson: String?,
    val previewPngPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
)
