package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorBundleImportResult
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorPortableBundle
import com.aichat.sandbox.data.vector.VectorPortableBundleParser
import com.aichat.sandbox.data.vector.VectorPortableBundleValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 10 — ViewModel orchestration for bundle import + version-graph actions.
 *
 * Mirrors [VectorTuneupViewModelVersionHistoryTest]: the real ViewModel couples
 * to Android-only collaborators, so this drives the same logic through the pure
 * [VectorTuneupReducer] plus a faithful in-memory repository double that reuses
 * the real parser/validator.
 */
class VectorTuneupViewModelBundleImportTest {

    private val reducer = VectorTuneupReducer()
    private val repo = FakeBundleVmRepository()

    private fun androidXml(name: String) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="$name" android:pathData="M0,0 L10,10"
                android:strokeColor="#FF0000" android:strokeWidth="1"/>
        </vector>
    """.trimIndent()

    private val dummyMetrics = VectorMetrics(
        xmlBytes = 0, pathCount = 0, groupCount = 0, commandCount = 0,
        parsedCommandCount = 0, unsupportedPathCount = 0, estimatedPointCount = 0,
        colorCounts = emptyMap(), strokePathCount = 0, fillPathCount = 0,
        zeroLengthPathCount = 0, tinySegmentEstimate = 0, duplicateCoordinateEstimate = 0,
        bounds = null, warnings = emptyList(),
    )

    private fun bundleJson(): String = VectorPortableBundle.build(
        VectorPortableBundle.ProjectInfo("Art", 1L, 2L),
        listOf(
            VectorPortableBundle.VersionInfo(
                id = "o", parentId = null, label = "Original", mode = "ORIGINAL",
                instruction = "", xml = androidXml("o"), metrics = dummyMetrics,
                warnings = emptyList(), reportSummary = null, createdAt = 10L,
            ),
            VectorPortableBundle.VersionInfo(
                id = "p", parentId = "o", label = "Optimized", mode = "OPTIMIZE",
                instruction = "", xml = androidXml("p"), metrics = dummyMetrics,
                warnings = emptyList(), reportSummary = null, createdAt = 20L,
            ),
        ),
    )

    // ---- harness mirroring VectorTuneupViewModel ----

    private suspend fun importBundleFromText(state: VectorTuneupUiState): VectorTuneupUiState {
        val text = state.bundleImportText
        if (text.isBlank()) return reducer.bundleImportFailed(state, VectorTuneupReducer.BUNDLE_IMPORT_BLANK)
        val start = reducer.startBundleImport(state)
        val result = repo.importBundle(text)
        val project = result.project
            ?: return reducer.bundleImportFailed(start, VectorTuneupReducer.BUNDLE_IMPORT_FAILED)
        val versions = repo.getVersions(project.id)
        return reducer.bundleImportSucceeded(start, project, versions, result.warnings)
    }

    private suspend fun duplicateSelectedVersion(state: VectorTuneupUiState): VectorTuneupUiState {
        val projectId = state.projectId
        val sourceId = state.sourceVersion?.persistedId
        if (projectId == null || sourceId == null) {
            return reducer.versionGraphActionFailed(state, VectorTuneupReducer.VERSION_DUPLICATE_FAILED)
        }
        val version = repo.duplicateVersion(projectId, sourceId)
        val all = repo.getVersions(projectId)
        return reducer.stagePersistedVersion(state, version, all).copy(selectedTab = VectorTuneupTab.HISTORY)
    }

    private suspend fun deleteSelectedVersion(state: VectorTuneupUiState): VectorTuneupUiState {
        val projectId = state.projectId
        val selected = state.selectedVersion
        val versionId = selected?.persistedId
        if (projectId == null || selected == null || versionId == null) {
            return reducer.versionGraphActionFailed(state, VectorTuneupReducer.VERSION_DELETE_FAILED)
        }
        if (selected.mode == VectorTuneupMode.ORIGINAL) {
            return reducer.versionGraphActionFailed(state, VectorTuneupReducer.VERSION_DELETE_ORIGINAL)
        }
        val deleted = repo.deleteLeafVersion(projectId, versionId)
        if (!deleted) {
            return reducer.versionGraphActionFailed(state, VectorTuneupReducer.VERSION_DELETE_HAS_CHILDREN)
        }
        val project = repo.getProject(projectId)!!
        val all = repo.getVersions(projectId)
        return reducer.loadProject(state, project, all).copy(selectedTab = VectorTuneupTab.HISTORY)
    }

    // ---- tests ----

    @Test
    fun bundleImportSuccessLoadsProjectAndVersions() = runTest {
        val state = importBundleFromText(VectorTuneupUiState(bundleImportText = bundleJson()))
        assertTrue(state.isSaved)
        assertEquals(2, state.versions.size)
        assertEquals(VectorTuneupTab.HISTORY, state.selectedTab)
        assertNotNull(state.bundleImportStatusMessage)
    }

    @Test
    fun bundleImportFailurePreservesCurrentProject() = runTest {
        val open = importBundleFromText(VectorTuneupUiState(bundleImportText = bundleJson()))
        val openProjectId = open.projectId
        assertNotNull(openProjectId)

        val attempted = importBundleFromText(open.copy(bundleImportText = "{not a bundle"))
        // Current project is untouched; a friendly message is shown.
        assertEquals(openProjectId, attempted.projectId)
        assertEquals(VectorTuneupReducer.BUNDLE_IMPORT_FAILED, attempted.bundleImportStatusMessage)
    }

    @Test
    fun blankBundleTextShowsFriendlyMessage() = runTest {
        val state = importBundleFromText(VectorTuneupUiState(bundleImportText = "   "))
        assertEquals(VectorTuneupReducer.BUNDLE_IMPORT_BLANK, state.bundleImportStatusMessage)
        assertFalse(state.isSaved)
    }

    @Test
    fun duplicateSelectedVersionStagesNewVersion() = runTest {
        val imported = importBundleFromText(VectorTuneupUiState(bundleImportText = bundleJson()))
        val original = imported.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        val selected = reducer.selectVersion(imported, original.id)

        val before = selected.versions.size
        val after = duplicateSelectedVersion(selected)
        assertEquals(before + 1, after.versions.size)
        val dup = after.versions.first { it.id == after.activeVersionId }
        assertEquals(VectorTuneupMode.MANUAL_EDIT, dup.mode)
        assertEquals(original.id, dup.parentId)
    }

    @Test
    fun deleteSelectedVersionReloadsVersions() = runTest {
        val imported = importBundleFromText(VectorTuneupUiState(bundleImportText = bundleJson()))
        val leaf = imported.versions.first { it.mode == VectorTuneupMode.OPTIMIZE }
        val selected = reducer.selectVersion(imported, leaf.id)

        val after = deleteSelectedVersion(selected)
        assertEquals(1, after.versions.size)
        // Active fell back to the original (the deleted leaf's parent).
        val original = after.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        assertEquals(original.id, after.activeVersionId)
    }

    @Test
    fun deleteBlockedShowsFriendlyMessage() = runTest {
        val imported = importBundleFromText(VectorTuneupUiState(bundleImportText = bundleJson()))
        val original = imported.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        val selected = reducer.selectVersion(imported, original.id)

        val after = deleteSelectedVersion(selected)
        assertEquals(VectorTuneupReducer.VERSION_DELETE_ORIGINAL, after.bundleImportStatusMessage)
        // Nothing deleted.
        assertEquals(2, after.versions.size)
    }
}

/** In-memory repository double reusing the real Phase 10 parser/validator. */
private class FakeBundleVmRepository {
    private val projects = LinkedHashMap<String, VectorTuneupProject>()
    private val versions = LinkedHashMap<String, VectorTuneupVersion>()
    private var clock = 0L

    suspend fun importBundle(bundleJson: String): VectorBundleImportResult {
        val parsed = VectorPortableBundleParser.parse(bundleJson)
        val bundle = parsed.bundle ?: return VectorBundleImportResult(null, parsed.warnings)
        val now = clock++
        val plan = VectorPortableBundleValidator.buildImportPlan(bundle, now)
        if (plan.versions.isEmpty()) return VectorBundleImportResult(null, parsed.warnings + plan.warnings)
        val projectId = UUID.randomUUID().toString()
        for (planned in plan.versions) {
            versions[planned.newId] = VectorTuneupVersion(
                id = planned.newId, projectId = projectId, parentId = planned.newParentId,
                label = planned.label, instruction = planned.instruction, mode = planned.mode,
                xml = planned.xml, metrics = planned.metrics, warnings = planned.warnings,
                reportSummary = planned.reportSummary, editPlanJson = null, sceneJson = null,
                previewPngPath = null, createdAt = planned.createdAt,
            )
        }
        val activeId = plan.activeVersionNewId ?: plan.versions.last().newId
        val project = VectorTuneupProject(
            id = projectId, title = plan.projectTitle, sourceXml = plan.sourceXml,
            activeVersionId = activeId, createdAt = now, updatedAt = now,
        )
        projects[projectId] = project
        return VectorBundleImportResult(project, parsed.warnings + plan.warnings)
    }

    suspend fun duplicateVersion(projectId: String, versionId: String): VectorTuneupVersion {
        val source = versions[versionId] ?: error("missing $versionId")
        val now = clock++
        val dup = source.copy(
            id = UUID.randomUUID().toString(), projectId = projectId, parentId = source.id,
            label = "Duplicate", instruction = "Duplicated version", mode = VectorTuneupMode.MANUAL_EDIT,
            reportSummary = "Duplicated from ${source.label}", createdAt = now,
        )
        versions[dup.id] = dup
        projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = dup.id, updatedAt = now) }
        return dup
    }

    suspend fun deleteLeafVersion(projectId: String, versionId: String): Boolean {
        val target = versions[versionId] ?: return false
        if (target.mode == VectorTuneupMode.ORIGINAL) return false
        if (versions.values.any { it.parentId == versionId }) return false
        val now = clock++
        if (projects[projectId]?.activeVersionId == versionId) {
            val parentExists = target.parentId?.let { versions[it] != null } ?: false
            val fallback = if (parentExists) target.parentId else originalId(projectId)
            if (fallback != null) {
                projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = fallback, updatedAt = now) }
            }
        }
        versions.remove(versionId)
        return true
    }

    private fun originalId(projectId: String): String? = versions.values
        .filter { it.projectId == projectId && it.mode == VectorTuneupMode.ORIGINAL }
        .minByOrNull { it.createdAt }?.id

    suspend fun getProject(projectId: String): VectorTuneupProject? = projects[projectId]
    suspend fun getVersions(projectId: String): List<VectorTuneupVersion> =
        versions.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }
}
