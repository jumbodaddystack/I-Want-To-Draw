package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupPersistenceJson
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.vector.VectorWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8 — pure-reducer coverage for preview + visual-diff transitions. */
class VectorTuneupReducerPreviewTest {

    private val reducer = VectorTuneupReducer()

    private val xml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="a" android:pathData="M0,0 L10,10" android:strokeColor="#FF0000" android:strokeWidth="1"/>
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
        listOf(
            version("v0", VectorTuneupMode.ORIGINAL, null),
            version("v1", VectorTuneupMode.OPTIMIZE, "v0"),
        ),
    )

    @Test
    fun setVisualDiffModeUpdatesState() {
        val state = VectorTuneupUiState()
        assertEquals(VectorVisualDiffMode.SIDE_BY_SIDE, state.visualDiffMode)

        val overlay = reducer.setVisualDiffMode(state, VectorVisualDiffMode.OVERLAY_BOUNDS)
        assertEquals(VectorVisualDiffMode.OVERLAY_BOUNDS, overlay.visualDiffMode)

        val heatmap = reducer.setVisualDiffMode(overlay, VectorVisualDiffMode.PATH_COUNT_HEATMAP)
        assertEquals(VectorVisualDiffMode.PATH_COUNT_HEATMAP, heatmap.visualDiffMode)
    }

    @Test
    fun previewWarningsDoNotClearVersionSelection() {
        val loaded = loadedState().let { reducer.selectVersion(it, "v1") }
        val before = loaded.copy()
        val warnings = listOf(
            VectorWarning(VectorWarning.Codes.PREVIEW_SKIPPED_UNPARSED_PATH, "skipped"),
        )

        val withWarnings = reducer.setPreviewWarnings(loaded, warnings)

        assertEquals(warnings, withWarnings.previewWarnings)
        // Selection, candidate, active version, and history are all preserved.
        assertEquals(before.selectedVersionId, withWarnings.selectedVersionId)
        assertEquals(before.candidate, withWarnings.candidate)
        assertEquals(before.activeVersionId, withWarnings.activeVersionId)
        assertEquals(before.versions, withWarnings.versions)
    }

    @Test
    fun setVisualDiffModePreservesEverythingElse() {
        val loaded = loadedState()
        val switched = reducer.setVisualDiffMode(loaded, VectorVisualDiffMode.OVERLAY_BOUNDS)
        assertEquals(loaded.versions, switched.versions)
        assertEquals(loaded.selectedVersionId, switched.selectedVersionId)
        assertTrue(switched.isSaved)
    }
}
