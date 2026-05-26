package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure [VectorTuneupReducer] that backs the Vector Tune-Up
 * workspace. The reducer carries all of the ViewModel's logic and is free of
 * Compose/Hilt/Android dependencies, so the full parse → diagnose → optimize →
 * reset workflow runs as plain JVM unit tests.
 */
class VectorTuneupViewModelTest {

    private val reducer = VectorTuneupReducer()

    private val validXml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="line"
                android:pathData="M2,2 L4,4 L6,6 L8,8 L10,10 L12,12 L14,14 L16,16 L18,18 L20,20"
                android:strokeColor="#FF0000" android:strokeWidth="1" />
        </vector>
    """.trimIndent()

    @Test
    fun parseBlankXmlShowsError() {
        val state = reducer.parseInput(VectorTuneupUiState(inputXml = "   "))
        assertEquals(VectorTuneupReducer.ERROR_BLANK, state.errorMessage)
        assertNull(state.original)
    }

    @Test
    fun parseInvalidXmlShowsParseError() {
        val state = reducer.parseInput(VectorTuneupUiState(inputXml = "<not-a-vector/>"))
        assertEquals(VectorTuneupReducer.ERROR_PARSE, state.errorMessage)
        assertNull(state.original)
    }

    @Test
    fun parseValidXmlCreatesOriginalVersion() {
        val state = reducer.parseInput(VectorTuneupUiState(inputXml = validXml))
        assertNull(state.errorMessage)
        val original = state.original
        assertNotNull(original)
        assertEquals(VectorVersionUi.ID_ORIGINAL, original!!.id)
        assertEquals(1, original.metrics.pathCount)
        assertEquals(VectorTuneupTab.DIAGNOSTICS, state.selectedTab)
    }

    @Test
    fun optimizeValidXmlCreatesCandidate() {
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = validXml))
        val state = reducer.optimize(parsed)
        val candidate = state.candidate
        assertNotNull(candidate)
        assertEquals(VectorVersionUi.ID_CANDIDATE, candidate!!.id)
        assertNotNull(candidate.reportSummary)
        // The collinear polyline should collapse to fewer commands.
        assertTrue(
            "expected fewer commands after optimize",
            candidate.metrics.commandCount < parsed.original!!.metrics.commandCount,
        )
        assertEquals(VectorTuneupTab.COMPARE, state.selectedTab)
        assertFalse(state.isOptimizing)
    }

    @Test
    fun optimizeParsesInputWhenNoOriginalYet() {
        val state = reducer.optimize(VectorTuneupUiState(inputXml = validXml))
        assertNotNull(state.original)
        assertNotNull(state.candidate)
    }

    @Test
    fun updateOptionsChangesStateWithoutOptimizing() {
        val parsed = reducer.parseInput(VectorTuneupUiState(inputXml = validXml))
        val newOptions = VectorOptimizeOptions(tolerance = 1.5f, decimalPlaces = 1)
        val state = reducer.updateOptions(parsed, newOptions)
        assertEquals(newOptions, state.options)
        assertNull("options change must not optimize", state.candidate)
    }

    @Test
    fun onXmlChangedClearsStaleError() {
        val errored = reducer.parseInput(VectorTuneupUiState(inputXml = ""))
        assertNotNull(errored.errorMessage)
        val state = reducer.onXmlChanged(errored, "<vector/>")
        assertNull(state.errorMessage)
        assertEquals("<vector/>", state.inputXml)
    }

    @Test
    fun clearCandidateRemovesCandidateAndLeavesOriginal() {
        val optimized = reducer.optimize(
            reducer.parseInput(VectorTuneupUiState(inputXml = validXml)),
        )
        assertNotNull(optimized.candidate)
        val state = reducer.clearCandidate(optimized)
        assertNull(state.candidate)
        assertNotNull(state.original)
        assertEquals(VectorTuneupTab.DIAGNOSTICS, state.selectedTab)
    }

    @Test
    fun resetClearsState() {
        val populated = reducer.optimize(
            reducer.parseInput(VectorTuneupUiState(inputXml = validXml)),
        )
        assertNotNull(populated.original)
        val state = reducer.reset()
        assertEquals(VectorTuneupUiState(), state)
        assertNull(state.original)
        assertNull(state.candidate)
        assertEquals("", state.inputXml)
    }

    @Test
    fun optimizerFailureShowsErrorAndKeepsOriginal() {
        val throwingReducer = VectorTuneupReducer(
            optimize = { _, _, _ -> throw IllegalStateException("boom") },
        )
        val parsed = throwingReducer.parseInput(VectorTuneupUiState(inputXml = validXml))
        assertNotNull(parsed.original)

        val state = throwingReducer.optimize(parsed)
        assertEquals(VectorTuneupReducer.ERROR_OPTIMIZE, state.errorMessage)
        assertNull(state.candidate)
        assertNotNull("original must be preserved", state.original)
        assertFalse(state.isOptimizing)
    }
}
