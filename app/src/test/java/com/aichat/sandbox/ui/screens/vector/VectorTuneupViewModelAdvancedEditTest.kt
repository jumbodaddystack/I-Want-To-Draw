package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.repository.buildOriginalVersion
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorBatchRestyle
import com.aichat.sandbox.data.vector.VectorBatchRestyleApplier
import com.aichat.sandbox.data.vector.VectorDocumentValidator
import com.aichat.sandbox.data.vector.VectorManualEdit
import com.aichat.sandbox.data.vector.VectorManualEditApplier
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorPathCatalog
import com.aichat.sandbox.data.vector.allPaths
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 7 — exercises the ViewModel's manual-edit orchestration through the pure
 * [VectorTuneupReducer] plus a faithful in-memory double of
 * `VectorTuneupRepository` (the real ViewModel couples to Android-only
 * collaborators). Pins: every manual edit creates a `MANUAL_EDIT` child branched
 * from the selected source version, and existing versions are preserved.
 */
class VectorTuneupViewModelAdvancedEditTest {

    private val reducer = VectorTuneupReducer()
    private val repo = FakeRepo()

    private val xml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="a" android:pathData="M0,0 L10,10"
                android:strokeColor="#FF0000" android:strokeWidth="1"/>
            <path android:name="b" android:pathData="M0,5 L20,5"
                android:strokeColor="#00FF00" android:strokeWidth="1"/>
        </vector>
    """.trimIndent()

    // ---- harness mirroring VectorTuneupViewModel manual-edit flow ----

    private suspend fun ensureProject(state: VectorTuneupUiState): VectorTuneupUiState {
        if (state.projectId != null) return state
        val parsed = reducer.parseInput(state)
        val project = repo.createProjectFromXml(parsed.projectTitle, parsed.original?.xml ?: parsed.inputXml)
        return reducer.loadProject(parsed, project, repo.getVersions(project.id))
    }

    private fun idOf(sourceXml: String, name: String): String =
        VectorPathCatalog.catalog(AndroidVectorDrawableParser.parse(sourceXml)).first { it.name == name }.id

    private suspend fun applyManualEdit(
        state: VectorTuneupUiState,
        label: String,
        build: (List<String>) -> List<VectorManualEdit>,
    ): VectorTuneupUiState {
        val ready = ensureProject(state)
        val source = ready.sourceVersion!!
        val document = AndroidVectorDrawableParser.parse(source.xml)
        val result = VectorManualEditApplier.apply(document, source.xml, build(ready.selectedPathIds.toList()))
        val version = repo.addVersion(
            projectId = ready.projectId!!,
            parentId = source.persistedId,
            label = label,
            instruction = result.summary,
            mode = VectorTuneupMode.MANUAL_EDIT,
            xml = result.xml,
            reportSummary = result.summary,
        )
        return reducer.stageManualEditVersion(ready, version, repo.getVersions(ready.projectId!!))
    }

    private suspend fun applyBatchRestyle(state: VectorTuneupUiState, restyle: VectorBatchRestyle): VectorTuneupUiState {
        val ready = ensureProject(state)
        val source = ready.sourceVersion!!
        val document = AndroidVectorDrawableParser.parse(source.xml)
        val result = VectorBatchRestyleApplier.apply(document, source.xml, restyle)
        val version = repo.addVersion(
            projectId = ready.projectId!!,
            parentId = source.persistedId,
            label = "Batch Restyle",
            instruction = result.summary,
            mode = VectorTuneupMode.MANUAL_EDIT,
            xml = result.xml,
            reportSummary = result.summary,
        )
        return reducer.stageManualEditVersion(ready, version, repo.getVersions(ready.projectId!!))
    }

    // ---- tests ----

    @Test
    fun manualDeleteCreatesManualEditVersion() = runTest {
        val ready = ensureProject(VectorTuneupUiState(inputXml = xml))
        val source = ready.sourceVersion!!
        val dropId = idOf(source.xml, "b")
        val selected = reducer.togglePathSelection(ready, dropId)

        val after = applyManualEdit(selected, "Delete Paths") { ids ->
            listOf(VectorManualEdit.DeletePaths(ids))
        }

        val v = after.versions.first { it.mode == VectorTuneupMode.MANUAL_EDIT }
        assertEquals("Delete Paths", v.label)
        assertEquals(source.persistedId, v.parentId)
        // The candidate's XML no longer contains the deleted path.
        val reparsed = AndroidVectorDrawableParser.parse(after.candidate!!.xml)
        assertEquals(1, reparsed.allPaths().size)
        assertEquals("a", reparsed.allPaths().single().name)
        // Selection is cleared after a successful edit.
        assertTrue(after.selectedPathIds.isEmpty())
        assertNotNull(after.manualEditStatusMessage)
    }

    @Test
    fun manualRecolorCreatesChildOfSelectedVersion() = runTest {
        // Create a project, then explicitly select the ORIGINAL as the source.
        val ready = ensureProject(VectorTuneupUiState(inputXml = xml))
        val original = ready.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        val selectedOriginal = reducer.selectVersion(ready, original.id)

        val recolorId = idOf(original.xml, "a")
        val withSelection = reducer.togglePathSelection(selectedOriginal, recolorId)
        val after = applyManualEdit(withSelection, "Recolor") { ids ->
            listOf(VectorManualEdit.RecolorPaths(ids, strokeColor = "#000000"))
        }

        val v = after.versions.first { it.mode == VectorTuneupMode.MANUAL_EDIT }
        assertEquals(original.id, v.parentId)
        val reparsed = AndroidVectorDrawableParser.parse(after.candidate!!.xml)
        assertEquals("#000000", reparsed.allPaths().first { it.name == "a" }.style.strokeColor)
    }

    @Test
    fun batchRestyleCreatesManualEditVersion() = runTest {
        val after = applyBatchRestyle(
            VectorTuneupUiState(inputXml = xml),
            VectorBatchRestyle(VectorBatchRestyle.Target.AllStroked, strokeWidth = 1.2f),
        )
        val v = after.versions.first { it.mode == VectorTuneupMode.MANUAL_EDIT }
        assertEquals("Batch Restyle", v.label)
        val reparsed = AndroidVectorDrawableParser.parse(after.candidate!!.xml)
        assertTrue(reparsed.allPaths().all { it.style.strokeWidth == 1.2f })
    }
}

/** In-memory double of `VectorTuneupRepository` (mirrors the Phase 6 test fake). */
private class FakeRepo {
    private val projects = LinkedHashMap<String, VectorTuneupProject>()
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
        val project = VectorTuneupProject(projectId, title.ifBlank { "Vector Tune-Up" }, xml, original.id, now, now)
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
    ): VectorTuneupVersion {
        val now = clock++
        val document = AndroidVectorDrawableParser.parse(xml)
        val version = VectorTuneupVersion(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            parentId = parentId,
            label = label,
            instruction = instruction,
            mode = mode,
            xml = xml,
            metrics = VectorMetricsAnalyzer.analyze(document, xml),
            warnings = emptyList(),
            reportSummary = reportSummary,
            editPlanJson = null,
            sceneJson = null,
            previewPngPath = null,
            createdAt = now,
        )
        versions[version.id] = version
        projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = version.id, updatedAt = now) }
        return version
    }

    fun getVersions(projectId: String): List<VectorTuneupVersion> =
        versions.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }
}
