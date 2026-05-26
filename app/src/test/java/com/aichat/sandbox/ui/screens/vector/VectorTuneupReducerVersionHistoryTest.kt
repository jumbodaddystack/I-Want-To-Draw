package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupPersistenceJson
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer coverage for the Phase 6 project/version-history transitions:
 * loading a saved project, selecting/branching from a prior version, staging a
 * newly persisted version, and clearing the history on reset.
 */
class VectorTuneupReducerVersionHistoryTest {

    private val reducer = VectorTuneupReducer()

    private fun version(
        id: String,
        mode: VectorTuneupMode,
        parentId: String?,
        label: String,
        createdAt: Long,
    ) = VectorTuneupVersion(
        id = id,
        projectId = "proj",
        parentId = parentId,
        label = label,
        instruction = "",
        mode = mode,
        xml = "<vector/>",
        metrics = VectorTuneupPersistenceJson.EMPTY_METRICS,
        warnings = emptyList(),
        reportSummary = null,
        editPlanJson = null,
        sceneJson = null,
        previewPngPath = null,
        createdAt = createdAt,
    )

    private val original = version("v0", VectorTuneupMode.ORIGINAL, null, "Original", 1L)
    private val optimized = version("v1", VectorTuneupMode.OPTIMIZE, "v0", "Optimized", 2L)

    private fun project(activeVersionId: String?) = VectorTuneupProject(
        id = "proj",
        title = "My Project",
        sourceXml = "<vector/>",
        activeVersionId = activeVersionId,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun loadProjectPopulatesVersionsAndSelectedVersion() {
        val state = reducer.loadProject(
            VectorTuneupUiState(),
            project(activeVersionId = "v1"),
            listOf(original, optimized),
        )
        assertEquals("proj", state.projectId)
        assertEquals("My Project", state.projectTitle)
        assertTrue(state.isSaved)
        assertEquals(2, state.versions.size)
        assertEquals("v1", state.activeVersionId)
        assertEquals("v1", state.selectedVersionId)
        // original derived from the ORIGINAL-mode version, candidate from the active one
        assertEquals("v0", state.original?.id)
        assertEquals("v1", state.candidate?.id)
        // input field restored to the project's exact source XML
        assertEquals("<vector/>", state.inputXml)
        assertEquals(VectorTuneupTab.COMPARE, state.selectedTab)
    }

    @Test
    fun selectVersionChangesSelectedVersion() {
        val loaded = reducer.loadProject(
            VectorTuneupUiState(),
            project(activeVersionId = "v1"),
            listOf(original, optimized),
        )
        val selectedOriginal = reducer.selectVersion(loaded, "v0")
        assertEquals("v0", selectedOriginal.selectedVersionId)
        assertNull("selecting the original clears the compared candidate", selectedOriginal.candidate)

        val selectedOpt = reducer.selectVersion(selectedOriginal, "v1")
        assertEquals("v1", selectedOpt.selectedVersionId)
        assertEquals("v1", selectedOpt.candidate?.id)
    }

    @Test
    fun stagePersistedVersionAddsAndSelectsVersion() {
        val loaded = reducer.loadProject(
            VectorTuneupUiState(),
            project(activeVersionId = "v0"),
            listOf(original),
        )
        assertEquals(1, loaded.versions.size)

        val staged = reducer.stagePersistedVersion(loaded, optimized, listOf(original, optimized))
        assertEquals(2, staged.versions.size)
        assertEquals("v1", staged.activeVersionId)
        assertEquals("v1", staged.selectedVersionId)
        assertEquals("v1", staged.candidate?.id)
        assertEquals("v0", staged.original?.id)
        assertEquals(VectorTuneupTab.COMPARE, staged.selectedTab)
    }

    @Test
    fun branchFromSelectedVersionUsesSelectedAsParent() {
        val loaded = reducer.loadProject(
            VectorTuneupUiState(),
            project(activeVersionId = "v1"),
            listOf(original, optimized),
        )
        // Select the older original, then the source for the next op must be it.
        val selected = reducer.selectVersion(loaded, "v0")
        assertEquals("v0", selected.sourceVersion?.id)
        assertEquals("v0", selected.sourceVersion?.persistedId)

        // With nothing selected, the active candidate is the source.
        assertEquals("v1", loaded.sourceVersion?.id)
    }

    @Test
    fun resetClearsProjectHistoryState() {
        val loaded = reducer.loadProject(
            VectorTuneupUiState(),
            project(activeVersionId = "v1"),
            listOf(original, optimized),
        )
        assertTrue(loaded.isSaved)

        val reset = reducer.reset()
        assertNull(reset.projectId)
        assertTrue(reset.versions.isEmpty())
        assertNull(reset.activeVersionId)
        assertNull(reset.selectedVersionId)
        assertEquals(VectorTuneupUiState(), reset)
    }
}
