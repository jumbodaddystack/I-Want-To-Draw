package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupPersistenceJson
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorPathCatalog
import com.aichat.sandbox.data.vector.VectorQualityScorer
import com.aichat.sandbox.data.vector.VectorVersionDiffAnalyzer
import com.aichat.sandbox.data.vector.VectorVersionQualityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — pure-reducer coverage for advanced editing + analysis transitions. */
class VectorTuneupReducerAdvancedEditTest {

    private val reducer = VectorTuneupReducer()

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

    private fun version(id: String, mode: VectorTuneupMode, parentId: String?) = VectorTuneupVersion(
        id = id,
        projectId = "proj",
        parentId = parentId,
        label = mode.name,
        instruction = "",
        mode = mode,
        xml = xml,
        metrics = VectorTuneupPersistenceJson.EMPTY_METRICS,
        warnings = emptyList(),
        reportSummary = null,
        editPlanJson = null,
        sceneJson = null,
        previewPngPath = null,
        createdAt = 1L,
    )

    private fun loadedState(): VectorTuneupUiState = reducer.loadProject(
        VectorTuneupUiState(),
        VectorTuneupProject("proj", "P", xml, "v0", 1L, 2L),
        listOf(version("v0", VectorTuneupMode.ORIGINAL, null)),
    )

    @Test
    fun selectingPathTogglesSelection() {
        val state = VectorTuneupUiState()
        val once = reducer.togglePathSelection(state, "p_001")
        assertEquals(setOf("p_001"), once.selectedPathIds)
        val twice = reducer.togglePathSelection(once, "p_002")
        assertEquals(setOf("p_001", "p_002"), twice.selectedPathIds)
        val off = reducer.togglePathSelection(twice, "p_001")
        assertEquals(setOf("p_002"), off.selectedPathIds)
    }

    @Test
    fun clearPathSelectionEmptiesSelection() {
        val state = VectorTuneupUiState(selectedPathIds = setOf("p_001", "p_002"))
        assertTrue(reducer.clearPathSelection(state).selectedPathIds.isEmpty())
    }

    @Test
    fun refreshSelectedAnalysisLoadsCatalogScoresAndDiff() {
        val document = AndroidVectorDrawableParser.parse(xml)
        val input = VectorVersionQualityInput(xml, document, VectorMetricsAnalyzer.analyze(document, xml))
        val catalog = VectorPathCatalog.catalog(document)
        val scores = VectorQualityScorer.score(input, input)
        val diff = VectorVersionDiffAnalyzer.diff(input, input)

        val state = reducer.loadAnalysisForSelectedVersion(VectorTuneupUiState(), catalog, scores, diff)
        assertEquals(2, state.pathCatalog.size)
        assertNotNull(state.qualityScores)
        assertNotNull(state.selectedDiff)
        assertEquals(catalog, state.pathCatalog)
    }

    @Test
    fun manualEditFailurePreservesExistingVersions() {
        val loaded = loadedState()
        val before = loaded.versions
        val failed = reducer.manualEditFailed(loaded, VectorTuneupReducer.MANUAL_NEED_SELECTION)
        assertEquals(VectorTuneupReducer.MANUAL_NEED_SELECTION, failed.manualEditStatusMessage)
        // Existing versions and selection are untouched by a failure.
        assertEquals(before, failed.versions)
        assertEquals(loaded.activeVersionId, failed.activeVersionId)
        assertTrue(failed.isSaved)
    }
}
