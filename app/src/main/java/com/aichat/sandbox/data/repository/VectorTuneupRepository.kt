package com.aichat.sandbox.data.repository

import android.content.Context
import android.util.Log
import com.aichat.sandbox.data.local.VectorTuneupDao
import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.model.VectorTuneupProjectEntity
import com.aichat.sandbox.data.model.VectorTuneupVersionEntity
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorDocumentValidator
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorWarning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---- Domain models (Room entities never leave this layer) ----

/** A Vector Art Tune-Up project: the imported source XML plus its version tree. */
data class VectorTuneupProject(
    val id: String,
    val title: String,
    val sourceXml: String,
    val activeVersionId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One version (original or generated candidate) within a project. */
data class VectorTuneupVersion(
    val id: String,
    val projectId: String,
    val parentId: String?,
    val label: String,
    val instruction: String,
    val mode: VectorTuneupMode,
    val xml: String,
    val metrics: VectorMetrics,
    val warnings: List<VectorWarning>,
    val reportSummary: String?,
    val editPlanJson: String?,
    val sceneJson: String?,
    val previewPngPath: String?,
    val createdAt: Long,
)

// ---- Entity <-> domain mappers (pure; unit-tested directly) ----

internal fun VectorTuneupProjectEntity.toDomain(): VectorTuneupProject = VectorTuneupProject(
    id = id,
    title = title,
    sourceXml = sourceXml,
    activeVersionId = activeVersionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun VectorTuneupProject.toEntity(): VectorTuneupProjectEntity = VectorTuneupProjectEntity(
    id = id,
    title = title,
    sourceXml = sourceXml,
    activeVersionId = activeVersionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun VectorTuneupVersionEntity.toDomain(): VectorTuneupVersion = VectorTuneupVersion(
    id = id,
    projectId = projectId,
    parentId = parentId,
    label = label,
    instruction = instruction,
    mode = runCatching { VectorTuneupMode.valueOf(mode) }.getOrDefault(VectorTuneupMode.MANUAL_EDIT),
    xml = xml,
    metrics = VectorTuneupPersistenceJson.metricsFromJson(metricsJson),
    warnings = VectorTuneupPersistenceJson.warningsFromJson(warningsJson),
    reportSummary = reportSummary,
    editPlanJson = editPlanJson,
    sceneJson = sceneJson,
    previewPngPath = previewPngPath,
    createdAt = createdAt,
)

internal fun VectorTuneupVersion.toEntity(): VectorTuneupVersionEntity = VectorTuneupVersionEntity(
    id = id,
    projectId = projectId,
    parentId = parentId,
    label = label,
    instruction = instruction,
    mode = mode.name,
    xml = xml,
    metricsJson = VectorTuneupPersistenceJson.metricsToJson(metrics),
    warningsJson = VectorTuneupPersistenceJson.warningsToJson(warnings),
    reportSummary = reportSummary,
    editPlanJson = editPlanJson,
    sceneJson = sceneJson,
    previewPngPath = previewPngPath,
    createdAt = createdAt,
)

/** Builds the [VectorTuneupMode.ORIGINAL] root version for a freshly created project. */
internal fun buildOriginalVersion(
    projectId: String,
    xml: String,
    metrics: VectorMetrics,
    warnings: List<VectorWarning>,
    createdAt: Long = System.currentTimeMillis(),
): VectorTuneupVersion = VectorTuneupVersion(
    id = UUID.randomUUID().toString(),
    projectId = projectId,
    parentId = null,
    label = "Original",
    instruction = "Imported source XML",
    mode = VectorTuneupMode.ORIGINAL,
    xml = xml,
    metrics = metrics,
    warnings = warnings,
    reportSummary = null,
    editPlanJson = null,
    sceneJson = null,
    previewPngPath = null,
    createdAt = createdAt,
)

/**
 * Phase 6 — durable storage for Vector Art Tune-Up projects and their version
 * history. The in-memory Phase 3–5 workflow (paste → optimize → AI tune-up →
 * redraw) is unchanged; this layer simply lets the workspace save a project,
 * accumulate child versions with parent lineage, reopen after restart, and
 * export any version.
 *
 * All IO runs on [Dispatchers.IO]. Room entities are mapped to the domain
 * models above so the UI never touches `@Entity` types. Raster previews are
 * still deferred, so [previewPngPath] stays null and [prunePreviewCache] is a
 * safe placeholder that only ever touches the dedicated preview directory.
 */
@Singleton
class VectorTuneupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: VectorTuneupDao,
) {

    fun observeProjects(): Flow<List<VectorTuneupProject>> =
        dao.observeProjects().map { rows -> rows.map { it.toDomain() } }

    fun observeVersions(projectId: String): Flow<List<VectorTuneupVersion>> =
        dao.observeVersions(projectId).map { rows -> rows.map { it.toDomain() } }

    suspend fun getProject(projectId: String): VectorTuneupProject? = withContext(Dispatchers.IO) {
        dao.getProject(projectId)?.toDomain()
    }

    suspend fun getVersion(versionId: String): VectorTuneupVersion? = withContext(Dispatchers.IO) {
        dao.getVersion(versionId)?.toDomain()
    }

    suspend fun getVersions(projectId: String): List<VectorTuneupVersion> = withContext(Dispatchers.IO) {
        dao.getVersions(projectId).map { it.toDomain() }
    }

    /**
     * Creates a project from imported [xml]: analyzes it with the deterministic
     * foundation, stores the project (preserving [xml] exactly as `sourceXml`),
     * inserts the [VectorTuneupMode.ORIGINAL] version, and marks it active.
     */
    suspend fun createProjectFromXml(
        title: String,
        xml: String,
    ): VectorTuneupProject = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val projectId = UUID.randomUUID().toString()

        val document = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(document, xml)
        val warnings = (document.warnings + VectorDocumentValidator.validate(document)).distinct()

        val project = VectorTuneupProjectEntity(
            id = projectId,
            title = title.ifBlank { DEFAULT_TITLE },
            sourceXml = xml,
            activeVersionId = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertProject(project)

        val original = buildOriginalVersion(projectId, xml, metrics, warnings, now)
        dao.upsertVersion(original.toEntity())
        dao.setActiveVersion(projectId, original.id, now)

        project.copy(activeVersionId = original.id).toDomain()
    }

    /** Inserts a new child [VectorTuneupVersion] and (by default) makes it active. */
    suspend fun addVersion(
        projectId: String,
        parentId: String?,
        label: String,
        instruction: String,
        mode: VectorTuneupMode,
        xml: String,
        metrics: VectorMetrics,
        warnings: List<VectorWarning>,
        reportSummary: String?,
        editPlanJson: String? = null,
        sceneJson: String? = null,
        makeActive: Boolean = true,
    ): VectorTuneupVersion = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val version = VectorTuneupVersion(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            parentId = parentId,
            label = label,
            instruction = instruction,
            mode = mode,
            xml = xml,
            metrics = metrics,
            warnings = warnings,
            reportSummary = reportSummary,
            editPlanJson = editPlanJson,
            sceneJson = sceneJson,
            previewPngPath = null,
            createdAt = now,
        )
        dao.upsertVersion(version.toEntity())
        if (makeActive) dao.setActiveVersion(projectId, version.id, now)
        version
    }

    suspend fun setActiveVersion(projectId: String, versionId: String) = withContext(Dispatchers.IO) {
        dao.setActiveVersion(projectId, versionId, System.currentTimeMillis())
    }

    suspend fun renameProject(projectId: String, title: String) = withContext(Dispatchers.IO) {
        if (title.isNotBlank()) {
            dao.renameProject(projectId, title.trim(), System.currentTimeMillis())
        }
    }

    /** Deletes a project (versions cascade) and any preview files it owned. */
    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val previewPaths = dao.getVersions(projectId).mapNotNull { it.previewPngPath }
        dao.deleteProject(projectId)
        previewPaths.forEach { path ->
            runCatching { File(path).takeIf(File::exists)?.delete() }
                .onFailure { Log.w(TAG, "Failed to delete preview $path", it) }
        }
    }

    /**
     * Placeholder-safe preview-cache cleanup. Raster previews are still deferred
     * (Phase 7+), so the preview directory normally does not exist and this is a
     * no-op. When it does exist, stale files older than [PREVIEW_TTL_MS] are
     * pruned. It never touches anything outside [previewDir].
     */
    suspend fun prunePreviewCache() = withContext(Dispatchers.IO) {
        val dir = previewDir()
        if (!dir.exists()) return@withContext
        val cutoff = System.currentTimeMillis() - PREVIEW_TTL_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
                    .onFailure { Log.w(TAG, "Failed to prune preview ${file.name}", it) }
            }
        }
    }

    private fun previewDir(): File = File(context.cacheDir, PREVIEW_SUBDIR)

    companion object {
        private const val TAG = "VectorTuneupRepo"
        const val DEFAULT_TITLE = "Vector Tune-Up"
        const val PREVIEW_SUBDIR = "vector-tuneup-previews"

        /** Previews older than this are considered stale by [prunePreviewCache]. */
        const val PREVIEW_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
