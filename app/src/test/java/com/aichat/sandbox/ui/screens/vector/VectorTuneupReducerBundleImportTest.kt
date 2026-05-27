package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 10 — pure reducer transitions for bundle import + version-graph status. */
class VectorTuneupReducerBundleImportTest {

    private val reducer = VectorTuneupReducer()

    private val metrics = VectorMetrics(
        xmlBytes = 10, pathCount = 1, groupCount = 0, commandCount = 1,
        parsedCommandCount = 1, unsupportedPathCount = 0, estimatedPointCount = 1,
        colorCounts = emptyMap(), strokePathCount = 1, fillPathCount = 0,
        zeroLengthPathCount = 0, tinySegmentEstimate = 0, duplicateCoordinateEstimate = 0,
        bounds = null, warnings = emptyList(),
    )

    private fun version(id: String, parentId: String?, mode: VectorTuneupMode) = VectorTuneupVersion(
        id = id, projectId = "p", parentId = parentId, label = id, instruction = "",
        mode = mode, xml = "<vector/>", metrics = metrics, warnings = emptyList(),
        reportSummary = null, editPlanJson = null, sceneJson = null, previewPngPath = null, createdAt = 1L,
    )

    private fun project(activeId: String) = VectorTuneupProject(
        id = "p", title = "Art (Imported)", sourceXml = "<vector/>",
        activeVersionId = activeId, createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun bundleImportTextUpdatesState() {
        val state = reducer.onBundleImportTextChanged(VectorTuneupUiState(), "{ \"schema\": 1 }")
        assertEquals("{ \"schema\": 1 }", state.bundleImportText)
    }

    @Test
    fun bundleImportStartSetsBusy() {
        val state = reducer.startBundleImport(VectorTuneupUiState())
        assertTrue(state.isImportingBundle)
        assertTrue(state.isBusy)
    }

    @Test
    fun bundleImportSuccessLoadsProjectAndVersions() {
        val versions = listOf(
            version("a", null, VectorTuneupMode.ORIGINAL),
            version("b", "a", VectorTuneupMode.OPTIMIZE),
        )
        val state = reducer.bundleImportSucceeded(
            reducer.startBundleImport(VectorTuneupUiState()),
            project("b"),
            versions,
            warnings = emptyList(),
        )
        assertEquals("p", state.projectId)
        assertEquals(2, state.versions.size)
        assertEquals("b", state.activeVersionId)
        assertFalse(state.isImportingBundle)
        assertEquals(VectorTuneupTab.HISTORY, state.selectedTab)
        assertNotNull(state.bundleImportStatusMessage)
        assertTrue(state.bundleImportText.isEmpty())
    }

    @Test
    fun bundleImportSuccessWarningCountInStatus() {
        val state = reducer.bundleImportSucceeded(
            VectorTuneupUiState(),
            project("a"),
            listOf(version("a", null, VectorTuneupMode.ORIGINAL)),
            warnings = listOf(VectorWarning("X", "x"), VectorWarning("Y", "y")),
        )
        assertEquals("Imported project with 2 warning(s).", state.bundleImportStatusMessage)
    }

    @Test
    fun bundleImportFailurePreservesCurrentProject() {
        // A project is already open.
        val open = reducer.loadProject(
            VectorTuneupUiState(),
            project("a"),
            listOf(version("a", null, VectorTuneupMode.ORIGINAL)),
        )
        val failed = reducer.bundleImportFailed(
            reducer.startBundleImport(open),
            VectorTuneupReducer.BUNDLE_IMPORT_FAILED,
        )
        assertEquals("p", failed.projectId)
        assertEquals(1, failed.versions.size)
        assertFalse(failed.isImportingBundle)
        assertEquals(VectorTuneupReducer.BUNDLE_IMPORT_FAILED, failed.bundleImportStatusMessage)
    }

    @Test
    fun versionGraphActionFailedShowsMessage() {
        val state = reducer.versionGraphActionFailed(
            VectorTuneupUiState(),
            VectorTuneupReducer.VERSION_DELETE_HAS_CHILDREN,
        )
        assertEquals(VectorTuneupReducer.VERSION_DELETE_HAS_CHILDREN, state.bundleImportStatusMessage)
    }
}
