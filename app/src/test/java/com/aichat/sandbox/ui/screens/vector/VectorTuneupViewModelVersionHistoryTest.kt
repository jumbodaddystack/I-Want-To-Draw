package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.repository.buildOriginalVersion
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorDocumentValidator
import com.aichat.sandbox.data.vector.VectorDrawableOptimizer
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Exercises the Phase 6 ViewModel orchestration — auto-creating a project,
 * persisting each generated version with parent lineage, branching from a
 * selected version, reopening a project, and resolving the export target.
 *
 * The real [VectorTuneupViewModel] couples to Android-only collaborators
 * (DataStore-backed `PreferencesManager`, a `Context`-backed exporter), so this
 * test drives the same logic through the pure [VectorTuneupReducer] plus a
 * faithful in-memory double of `VectorTuneupRepository`. The persistence
 * decisions under test (which parent, which source, which export version, how a
 * staged version folds into state) all live in the reducer and repository
 * contract, which this test pins.
 */
class VectorTuneupViewModelVersionHistoryTest {

    private val reducer = VectorTuneupReducer()
    private val repo = FakeVectorTuneupRepository()

    private val validXml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="line"
                android:pathData="M2,2 L4,4 L6,6 L8,8 L10,10 L12,12 L14,14 L16,16 L18,18 L20,20"
                android:strokeColor="#FF0000" android:strokeWidth="1" />
        </vector>
    """.trimIndent()

    // ---- harness mirroring VectorTuneupViewModel ----

    private suspend fun ensureProject(state: VectorTuneupUiState): VectorTuneupUiState {
        if (state.projectId != null) return state
        val parsed = reducer.parseInput(state)
        val xml = parsed.original?.xml ?: parsed.inputXml
        val project = repo.createProjectFromXml(parsed.projectTitle, xml)
        return reducer.loadProject(parsed, project, repo.getVersions(project.id))
    }

    private suspend fun optimize(state: VectorTuneupUiState): VectorTuneupUiState {
        val ready = ensureProject(state)
        val source = ready.sourceVersion!!
        val document = AndroidVectorDrawableParser.parse(source.xml)
        val result = VectorDrawableOptimizer.optimize(document, source.xml, ready.options)
        val version = repo.addVersion(
            projectId = ready.projectId!!,
            parentId = source.persistedId,
            label = "Optimized",
            instruction = "Local optimize",
            mode = VectorTuneupMode.OPTIMIZE,
            xml = result.xml,
            reportSummary = VectorTuneupReducer.summarize(result.report),
        )
        return reducer.stagePersistedVersion(ready, version, repo.getVersions(ready.projectId!!))
    }

    private suspend fun addGenerated(
        state: VectorTuneupUiState,
        mode: VectorTuneupMode,
        label: String,
        editPlanJson: String? = null,
        sceneJson: String? = null,
    ): VectorTuneupUiState {
        val ready = ensureProject(state)
        val source = ready.sourceVersion!!
        val version = repo.addVersion(
            projectId = ready.projectId!!,
            parentId = source.persistedId,
            label = label,
            instruction = "instruction",
            mode = mode,
            xml = source.xml,
            reportSummary = "summary",
            editPlanJson = editPlanJson,
            sceneJson = sceneJson,
        )
        return reducer.stagePersistedVersion(ready, version, repo.getVersions(ready.projectId!!))
    }

    // ---- tests ----

    @Test
    fun optimizeCreatesProjectWhenUnsaved() = runTest {
        val start = VectorTuneupUiState(inputXml = validXml)
        assertFalse(start.isSaved)

        val state = optimize(start)
        assertTrue(state.isSaved)
        assertEquals(1, repo.projects.size)
        // original + optimized
        assertEquals(2, state.versions.size)
        assertNotNull(state.candidate)
        assertEquals("Optimized", state.candidate?.label)
    }

    @Test
    fun optimizeAddsChildVersion() = runTest {
        val state = optimize(VectorTuneupUiState(inputXml = validXml))
        val original = state.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        val optimized = state.versions.first { it.mode == VectorTuneupMode.OPTIMIZE }
        assertEquals(original.id, optimized.parentId)
    }

    @Test
    fun aiTuneupAddsAiTuneupVersion() = runTest {
        val state = addGenerated(
            VectorTuneupUiState(inputXml = validXml),
            VectorTuneupMode.AI_TUNE_UP,
            "AI Tune-Up",
            editPlanJson = "{\"schema\":1}",
        )
        val v = state.versions.first { it.mode == VectorTuneupMode.AI_TUNE_UP }
        assertEquals("AI Tune-Up", v.label)
        assertEquals(state.activeVersionId, v.id)
        assertEquals("{\"schema\":1}", repo.getVersionRaw(v.id)?.editPlanJson)
    }

    @Test
    fun redrawAddsAiRedrawVersion() = runTest {
        val state = addGenerated(
            VectorTuneupUiState(inputXml = validXml),
            VectorTuneupMode.AI_REDRAW,
            "AI Redraw",
            sceneJson = "{\"schema\":1}",
        )
        val v = state.versions.first { it.mode == VectorTuneupMode.AI_REDRAW }
        assertEquals("AI Redraw", v.label)
        assertEquals("{\"schema\":1}", repo.getVersionRaw(v.id)?.sceneJson)
    }

    @Test
    fun selectVersionThenOptimizeBranchesFromSelected() = runTest {
        // First optimize → versions v0 (original) and v1 (optimized), active v1.
        val first = optimize(VectorTuneupUiState(inputXml = validXml))
        val original = first.versions.first { it.mode == VectorTuneupMode.ORIGINAL }

        // Select the original, then optimize again: the new child must branch
        // from the selected original, not from the latest (v1).
        val selected = reducer.selectVersion(first, original.id)
        val second = optimize(selected)

        val children = second.versions.filter { it.mode == VectorTuneupMode.OPTIMIZE }
        assertEquals(2, children.size)
        assertTrue(
            "newest optimize branches from the selected original",
            children.any { it.parentId == original.id && it.id == second.activeVersionId },
        )
    }

    @Test
    fun exportVersionUsesRequestedVersion() = runTest {
        val state = optimize(VectorTuneupUiState(inputXml = validXml))
        val original = state.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        // Explicit id wins over the selected/active candidate.
        val resolved = reducer.resolveExportVersion(state, original.id)
        assertEquals(original.id, resolved?.id)
        // Default (no id) falls back to the active candidate.
        assertEquals(state.activeVersionId, reducer.resolveExportVersion(state, null)?.id)
    }

    @Test
    fun loadProjectRestoresActiveVersion() = runTest {
        val created = optimize(VectorTuneupUiState(inputXml = validXml))
        val projectId = created.projectId!!
        val original = created.versions.first { it.mode == VectorTuneupMode.ORIGINAL }

        // Make the original active, then reopen from scratch.
        repo.setActiveVersion(projectId, original.id)
        val project = repo.getProject(projectId)!!
        val reopened = reducer.loadProject(VectorTuneupUiState(), project, repo.getVersions(projectId))

        assertEquals(original.id, reopened.activeVersionId)
        assertNull("active original means no compared candidate", reopened.candidate)
        assertEquals(original.id, reopened.original?.id)
    }
}

/**
 * Faithful in-memory double of `VectorTuneupRepository`: it reuses the real
 * parser/analyzer/validator and `buildOriginalVersion` so its created projects
 * match production, but stores rows in maps instead of Room. `createdAt` is a
 * monotonic counter so version ordering is deterministic in fast tests.
 */
private class FakeVectorTuneupRepository {
    val projects = LinkedHashMap<String, VectorTuneupProject>()
    private val versions = LinkedHashMap<String, VectorTuneupVersion>()
    private var clock = 0L

    suspend fun createProjectFromXml(title: String, xml: String): VectorTuneupProject {
        val now = clock++
        val projectId = UUID.randomUUID().toString()
        val document = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(document, xml)
        val warnings = (document.warnings + VectorDocumentValidator.validate(document)).distinct()
        val original = buildOriginalVersion(projectId, xml, metrics, warnings, now)
        versions[original.id] = original
        val project = VectorTuneupProject(
            id = projectId,
            title = title.ifBlank { "Vector Tune-Up" },
            sourceXml = xml,
            activeVersionId = original.id,
            createdAt = now,
            updatedAt = now,
        )
        projects[projectId] = project
        return project
    }

    suspend fun addVersion(
        projectId: String,
        parentId: String?,
        label: String,
        instruction: String,
        mode: VectorTuneupMode,
        xml: String,
        reportSummary: String?,
        editPlanJson: String? = null,
        sceneJson: String? = null,
        makeActive: Boolean = true,
    ): VectorTuneupVersion {
        val now = clock++
        val document = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(document, xml)
        val version = VectorTuneupVersion(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            parentId = parentId,
            label = label,
            instruction = instruction,
            mode = mode,
            xml = xml,
            metrics = metrics,
            warnings = emptyList(),
            reportSummary = reportSummary,
            editPlanJson = editPlanJson,
            sceneJson = sceneJson,
            previewPngPath = null,
            createdAt = now,
        )
        versions[version.id] = version
        if (makeActive) {
            projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = version.id, updatedAt = now) }
        }
        return version
    }

    suspend fun getProject(projectId: String): VectorTuneupProject? = projects[projectId]

    suspend fun getVersions(projectId: String): List<VectorTuneupVersion> =
        versions.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }

    fun getVersionRaw(versionId: String): VectorTuneupVersion? = versions[versionId]

    suspend fun setActiveVersion(projectId: String, versionId: String) {
        projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = versionId, updatedAt = clock++) }
    }
}
