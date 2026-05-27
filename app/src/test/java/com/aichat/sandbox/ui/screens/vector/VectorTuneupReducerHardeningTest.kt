package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.VectorInputHealth
import com.aichat.sandbox.data.vector.VectorInputLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11 — reducer-level hardening: import-text routing, large-input health on
 * the input field, the expensive-AI consent toggle, and friendly file-import
 * failures. All pure, no Compose/Android.
 */
class VectorTuneupReducerHardeningTest {

    private val reducer = VectorTuneupReducer()

    private val androidXml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="p" android:pathData="M0,0 L10,10"
                android:strokeColor="#FF0000" android:strokeWidth="1"/>
        </vector>
    """.trimIndent()

    private val bundleJson =
        """{ "schema": 1, "kind": "vector_tuneup_project", "project": {}, "versions": [] }"""

    @Test
    fun fileImportRejectsOversizedText() {
        val oversized = "a".repeat(VectorInputLimits.MAX_PASTE_CHARS + 1)
        assertEquals(VectorTuneupReducer.ImportRoute.TOO_LARGE, reducer.classifyImportText(oversized))
    }

    @Test
    fun formatDetectionRedirectsBundleToBundleImport() {
        assertEquals(VectorTuneupReducer.ImportRoute.BUNDLE, reducer.classifyImportText(bundleJson))
        assertEquals(VectorTuneupReducer.ImportRoute.VECTOR, reducer.classifyImportText(androidXml))
    }

    @Test
    fun importVectorTextParsesAndNamesFile() {
        val state = reducer.importVectorText(VectorTuneupUiState(), "icon.xml", androidXml)
        assertTrue(state.hasOriginal)
        assertNull(state.errorMessage)
        assertEquals(VectorTuneupTab.DIAGNOSTICS, state.selectedTab)
        assertTrue(state.fileImportStatusMessage!!.contains("icon.xml"))
    }

    @Test
    fun onXmlChangedAssessesInputHealth() {
        val unsafe = "a".repeat(6 * 1024 * 1024)
        val state = reducer.onXmlChanged(VectorTuneupUiState(), unsafe)
        assertEquals(VectorInputHealth.Severity.UNSAFE, state.inputHealth.severity)
        assertTrue(state.isInputUnsafe)
        assertTrue(state.expensiveAiBlocked)
    }

    @Test
    fun accessibilityStateDoesNotBreakReducer() {
        // Toggling the consent flag must not disturb any other workflow state.
        val base = reducer.onXmlChanged(VectorTuneupUiState(), androidXml).copy(projectTitle = "Keep me")
        val allowed = reducer.setAllowExpensiveOnLargeInput(base, true)
        assertTrue(allowed.allowExpensiveOnLargeInput)
        assertEquals(base.inputXml, allowed.inputXml)
        assertEquals(base.projectTitle, allowed.projectTitle)
        assertEquals(base.detectedImportFormat, allowed.detectedImportFormat)

        val reverted = reducer.setAllowExpensiveOnLargeInput(allowed, false)
        assertFalse(reverted.allowExpensiveOnLargeInput)
    }

    @Test
    fun extremeInputBlocksAiUntilConsent() {
        val extreme = "a".repeat((2.5 * 1024 * 1024).toInt())
        val state = reducer.onXmlChanged(VectorTuneupUiState(), extreme)
        assertEquals(VectorInputHealth.Severity.EXTREME, state.inputHealth.severity)
        assertTrue(state.expensiveAiBlocked)
        val allowed = reducer.setAllowExpensiveOnLargeInput(state, true)
        assertFalse(allowed.expensiveAiBlocked)
    }

    @Test
    fun fileImportFailedSetsFriendlyStatus() {
        val state = reducer.fileImportFailed(VectorTuneupUiState(), VectorTuneupReducer.FILE_TOO_LARGE)
        assertEquals(VectorTuneupReducer.FILE_TOO_LARGE, state.fileImportStatusMessage)
    }
}
