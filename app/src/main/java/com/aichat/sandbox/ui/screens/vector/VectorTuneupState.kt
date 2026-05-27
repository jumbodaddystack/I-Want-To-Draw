package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorPathCatalogEntry
import com.aichat.sandbox.data.vector.VectorQualityScores
import com.aichat.sandbox.data.vector.VectorVersionDiff
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * Immutable UI state for the Vector Art Tune-Up workspace.
 *
 * Held by [VectorTuneupViewModel] and produced exclusively by
 * [VectorTuneupReducer], so the whole workflow (parse → diagnose → optimize →
 * compare → export) is a pure function of [inputXml] + [options] and can be
 * unit-tested without Compose, Hilt, or the Android framework.
 *
 * @property inputXml Raw text in the import field.
 * @property original Parsed source version, or null before a successful parse.
 * @property candidate Optimized version, or null before optimizing / after clear.
 * @property projectId Owning persisted project id, or null while unsaved.
 * @property projectTitle Display title for the project header.
 * @property versions Full persisted version history (empty while unsaved).
 * @property activeVersionId The version marked active in the project.
 * @property selectedVersionId The version selected in the history panel (drives branching/compare).
 * @property options Current optimizer settings.
 * @property isOptimizing True while an optimize pass is running (drives spinners).
 * @property errorMessage Friendly, user-facing error; never a stack trace.
 * @property selectedTab Currently selected workspace section.
 * @property aiPrompt The user's free-text instruction for the AI tune-up.
 * @property isAiRunning True while an AI tune-up request is streaming.
 * @property aiStatusMessage Friendly status/result line for the AI panel (never raw JSON).
 * @property lastAiSummary The model's plan summary from the most recent run, if any.
 * @property redrawPrompt The user's free-text instruction for the semantic redraw.
 * @property isRedrawRunning True while a semantic redraw request is streaming.
 * @property redrawStatusMessage Friendly status/result line for the redraw panel.
 * @property lastRedrawSummary The scene styleIntent from the most recent redraw, if any.
 * @property selectedPathIds Path ids the user has selected for manual editing.
 * @property pathCatalog Per-path catalog for the analyzed source version (Edit tab).
 * @property qualityScores Deterministic quality scores for the analyzed source version.
 * @property selectedDiff Structural diff of original vs the analyzed source version.
 * @property manualEditStatusMessage Friendly status line for the manual-edit controls.
 * @property visualDiffMode How the Compare tab's visual diff is presented.
 * @property previewWarnings Preview-build notes for the current source version (informational only).
 */
data class VectorTuneupUiState(
    val inputXml: String = "",
    val original: VectorVersionUi? = null,
    val candidate: VectorVersionUi? = null,
    val projectId: String? = null,
    val projectTitle: String = "Vector Tune-Up",
    val versions: List<VectorVersionUi> = emptyList(),
    val activeVersionId: String? = null,
    val selectedVersionId: String? = null,
    val options: VectorOptimizeOptions = VectorOptimizeOptions(),
    val isOptimizing: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: VectorTuneupTab = VectorTuneupTab.INPUT,
    val aiPrompt: String = "Tune this up while preserving the original visual intent.",
    val isAiRunning: Boolean = false,
    val aiStatusMessage: String? = null,
    val lastAiSummary: String? = null,
    val redrawPrompt: String =
        "Redraw this as a clean, polished vector icon while preserving the subject and color palette.",
    val isRedrawRunning: Boolean = false,
    val redrawStatusMessage: String? = null,
    val lastRedrawSummary: String? = null,
    val selectedPathIds: Set<String> = emptySet(),
    val pathCatalog: List<VectorPathCatalogEntry> = emptyList(),
    val qualityScores: VectorQualityScores? = null,
    val selectedDiff: VectorVersionDiff? = null,
    val manualEditStatusMessage: String? = null,
    val visualDiffMode: VectorVisualDiffMode = VectorVisualDiffMode.SIDE_BY_SIDE,
    val previewWarnings: List<VectorWarning> = emptyList(),
) {
    /** True once the input has been parsed into an [original] version. */
    val hasOriginal: Boolean get() = original != null

    /** True once an optimized [candidate] has been produced. */
    val hasCandidate: Boolean get() = candidate != null

    /** True when an optimize, AI tune-up, or redraw pass is in flight (gates actions). */
    val isBusy: Boolean get() = isOptimizing || isAiRunning || isRedrawRunning

    /** True once the workspace has been persisted as a durable project. */
    val isSaved: Boolean get() = projectId != null

    /** The persisted version selected in the history panel, if any. */
    val selectedVersion: VectorVersionUi? get() = versions.firstOrNull { it.id == selectedVersionId }

    /** The persisted version currently marked active, if any. */
    val activeVersion: VectorVersionUi? get() = versions.firstOrNull { it.id == activeVersionId }

    /**
     * The version a new operation should branch from: an explicitly selected
     * version wins, then the current candidate, then the original. Returns null
     * only before anything has been parsed.
     */
    val sourceVersion: VectorVersionUi? get() = selectedVersion ?: candidate ?: original
}
