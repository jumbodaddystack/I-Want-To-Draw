package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.repository.VectorBundleImportResult
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.vector.VectorInputLimits
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorPortableBundle
import com.aichat.sandbox.data.vector.VectorPortableBundleParser
import com.aichat.sandbox.data.vector.VectorPortableBundleValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 11 — ViewModel orchestration for file import + large-input AI gating.
 *
 * Like the other Vector Tune-Up ViewModel tests, this drives the real
 * [VectorTuneupReducer] (and the real bundle parser/validator) through a harness
 * that mirrors the ViewModel's branching, since the production ViewModel couples
 * to Android-only collaborators (file reader, repository, AI services).
 */
class VectorTuneupViewModelHardeningTest {

    private val reducer = VectorTuneupReducer()
    private val repo = FakeHardeningRepository()

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
        ),
    )

    // ---- harness mirroring VectorTuneupViewModel ----

    private suspend fun importVectorTextFromFile(
        state: VectorTuneupUiState,
        displayName: String?,
        text: String,
    ): VectorTuneupUiState = when (reducer.classifyImportText(text)) {
        VectorTuneupReducer.ImportRoute.TOO_LARGE ->
            reducer.fileImportFailed(state, VectorTuneupReducer.FILE_TOO_LARGE)
        VectorTuneupReducer.ImportRoute.BUNDLE -> importBundleTextFromFile(state, text)
        VectorTuneupReducer.ImportRoute.VECTOR -> reducer.importVectorText(state, displayName, text)
    }

    private suspend fun importBundleTextFromFile(state: VectorTuneupUiState, text: String): VectorTuneupUiState {
        if (text.length > VectorInputLimits.MAX_PASTE_CHARS) {
            return reducer.fileImportFailed(state, VectorTuneupReducer.FILE_TOO_LARGE)
        }
        val staged = reducer.startBundleImport(reducer.onBundleImportTextChanged(state, text))
        val result = repo.importBundle(text)
        val project = result.project
            ?: return reducer.bundleImportFailed(staged, VectorTuneupReducer.BUNDLE_IMPORT_FAILED)
        val versions = repo.getVersions(project.id)
        return reducer.bundleImportSucceeded(staged, project, versions, result.warnings)
    }

    private fun runAiTuneup(state: VectorTuneupUiState): VectorTuneupUiState {
        if (state.expensiveAiBlocked) {
            val message = if (state.isInputUnsafe) VectorTuneupReducer.AI_BLOCKED_UNSAFE
            else VectorTuneupReducer.AI_BLOCKED_EXTREME
            return reducer.aiFailed(state, message)
        }
        return reducer.startAi(state)
    }

    // ---- tests ----

    @Test
    fun fileImportRejectsOversizedText() = runTest {
        val oversized = "a".repeat(VectorInputLimits.MAX_PASTE_CHARS + 1)
        val state = importVectorTextFromFile(VectorTuneupUiState(), "huge.xml", oversized)
        assertEquals(VectorTuneupReducer.FILE_TOO_LARGE, state.fileImportStatusMessage)
        assertFalse(state.hasOriginal)
    }

    @Test
    fun vectorFileImportParsesIntoInput() = runTest {
        val state = importVectorTextFromFile(VectorTuneupUiState(), "icon.xml", androidXml("p"))
        assertTrue(state.hasOriginal)
        assertEquals(VectorTuneupTab.DIAGNOSTICS, state.selectedTab)
    }

    @Test
    fun bundleFileImportCreatesProject() = runTest {
        val state = importVectorTextFromFile(VectorTuneupUiState(), "project.json", bundleJson())
        assertTrue(state.isSaved)
        assertEquals(VectorTuneupTab.HISTORY, state.selectedTab)
    }

    @Test
    fun unsafeInputBlocksAiTuneup() = runTest {
        val unsafe = "a".repeat(6 * 1024 * 1024)
        val state = runAiTuneup(reducer.onXmlChanged(VectorTuneupUiState(), unsafe))
        assertEquals(VectorTuneupReducer.AI_BLOCKED_UNSAFE, state.aiStatusMessage)
        assertFalse(state.isAiRunning)
    }

    @Test
    fun extremeInputBlocksAiUntilConsent() = runTest {
        val extreme = "a".repeat((2.5 * 1024 * 1024).toInt())
        val blocked = runAiTuneup(reducer.onXmlChanged(VectorTuneupUiState(), extreme))
        assertEquals(VectorTuneupReducer.AI_BLOCKED_EXTREME, blocked.aiStatusMessage)

        val consented = reducer.setAllowExpensiveOnLargeInput(
            reducer.onXmlChanged(VectorTuneupUiState(), extreme), true,
        )
        val allowed = runAiTuneup(consented)
        assertTrue(allowed.isAiRunning)
        assertNull(allowed.aiStatusMessage)
    }
}

/** In-memory repository double reusing the real Phase 10 parser/validator. */
private class FakeHardeningRepository {
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

    suspend fun getVersions(projectId: String): List<VectorTuneupVersion> =
        versions.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }
}
