package com.aichat.sandbox.ui.screens.vector

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupRepository
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorBatchRestyle
import com.aichat.sandbox.data.vector.VectorBatchRestyleApplier
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorDrawableOptimizer
import com.aichat.sandbox.data.vector.VectorExportFormat
import com.aichat.sandbox.data.vector.VectorInputLimits
import com.aichat.sandbox.data.vector.VectorManualEdit
import com.aichat.sandbox.data.vector.VectorManualEditApplier
import com.aichat.sandbox.data.vector.VectorManualEditResult
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorPathCatalog
import com.aichat.sandbox.data.vector.VectorPortableBundle
import com.aichat.sandbox.data.vector.VectorQualityScorer
import com.aichat.sandbox.data.vector.VectorVersionDiffAnalyzer
import com.aichat.sandbox.data.vector.VectorVersionQualityInput
import com.aichat.sandbox.data.vector.VectorRedrawAiChunk
import com.aichat.sandbox.data.vector.VectorRedrawAiRequest
import com.aichat.sandbox.data.vector.VectorRedrawAiService
import com.aichat.sandbox.data.vector.VectorTuneupAiChunk
import com.aichat.sandbox.data.vector.VectorTuneupAiRequest
import com.aichat.sandbox.data.vector.VectorTuneupAiService
import com.aichat.sandbox.data.vector.VectorTuneupExporter
import com.aichat.sandbox.data.vector.VectorTuneupFileReader
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Vector Art Tune-Up workspace.
 *
 * Pure workflow logic lives in [VectorTuneupReducer]; this class owns the
 * [StateFlow], runs optimize/AI/export off the main thread, and (Phase 6)
 * persists every generated version through [VectorTuneupRepository] so projects
 * survive restart, preserve parent/child lineage, and can be reopened and
 * branched from. The unsaved paste/parse flow still works — a project is
 * auto-created lazily the first time the user generates a version.
 */
@HiltViewModel
class VectorTuneupViewModel @Inject constructor(
    private val exporter: VectorTuneupExporter,
    private val aiService: VectorTuneupAiService,
    private val redrawService: VectorRedrawAiService,
    private val preferencesManager: PreferencesManager,
    private val repository: VectorTuneupRepository,
    private val fileReader: VectorTuneupFileReader,
) : ViewModel() {

    private val reducer = VectorTuneupReducer()
    private val gson = Gson()

    private val _uiState = MutableStateFlow(VectorTuneupUiState())
    val uiState: StateFlow<VectorTuneupUiState> = _uiState.asStateFlow()

    /** Recent saved projects, for the in-workspace project picker. */
    val projects: StateFlow<List<VectorTuneupProject>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<VectorTuneupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VectorTuneupEvent> = _events.asSharedFlow()

    private var aiJob: Job? = null
    private var redrawJob: Job? = null

    fun onXmlChanged(xml: String) {
        _uiState.update { reducer.onXmlChanged(it, xml) }
    }

    fun parseInput() {
        _uiState.update { reducer.parseInput(it) }
    }

    fun updateOptions(options: VectorOptimizeOptions) {
        _uiState.update { reducer.updateOptions(it, options) }
    }

    fun selectTab(tab: VectorTuneupTab) {
        _uiState.update { reducer.selectTab(it, tab) }
    }

    /** Loads the built-in sample so users can try the workflow immediately. */
    fun pasteSample() {
        _uiState.update { reducer.onXmlChanged(it, SAMPLE_XML) }
    }

    /**
     * Runs a deterministic optimize pass. Auto-creates a project if the
     * workspace is still unsaved, branches the new version from the resolved
     * source version, and persists it as a [VectorTuneupMode.OPTIMIZE] version.
     */
    fun optimize() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isOptimizing = true, errorMessage = null) }
        viewModelScope.launch {
            val projectId = ensureProject()
            if (projectId == null) {
                _uiState.update { it.copy(isOptimizing = false) }
                return@launch
            }
            val state = _uiState.value
            val source = state.sourceVersion
            if (source == null) {
                _uiState.update { it.copy(isOptimizing = false, errorMessage = VectorTuneupReducer.ERROR_OPTIMIZE) }
                return@launch
            }
            val result = runCatching {
                val document = AndroidVectorDrawableParser.parse(source.xml)
                VectorDrawableOptimizer.optimize(document, source.xml, state.options)
            }.getOrElse {
                Log.w(TAG, "Optimize failed", it)
                _uiState.update { it.copy(isOptimizing = false, errorMessage = VectorTuneupReducer.ERROR_OPTIMIZE) }
                return@launch
            }
            val version = runCatching {
                repository.addVersion(
                    projectId = projectId,
                    parentId = source.persistedId,
                    label = "Optimized",
                    instruction = "Local optimize (tolerance ${state.options.tolerance})",
                    mode = VectorTuneupMode.OPTIMIZE,
                    xml = result.xml,
                    metrics = result.report.after,
                    warnings = result.report.warnings,
                    reportSummary = VectorTuneupReducer.summarize(result.report),
                )
            }.getOrElse {
                Log.w(TAG, "Persist optimized version failed", it)
                _uiState.update { it.copy(isOptimizing = false, errorMessage = VectorTuneupReducer.ERROR_SAVE_VERSION) }
                return@launch
            }
            val all = repository.getVersions(projectId)
            _uiState.update { reducer.stagePersistedVersion(it, version, all).copy(isOptimizing = false) }
        }
    }

    fun clearCandidate() {
        _uiState.update { reducer.clearCandidate(it) }
    }

    fun reset() {
        aiJob?.cancel(); aiJob = null
        redrawJob?.cancel(); redrawJob = null
        _uiState.value = reducer.reset()
    }

    fun onAiPromptChanged(prompt: String) {
        _uiState.update { reducer.onAiPromptChanged(it, prompt) }
    }

    /**
     * Runs a model-guided tune-up. Auto-creates a project if needed, branches
     * from the resolved source version, applies the validated plan, and persists
     * the result as a [VectorTuneupMode.AI_TUNE_UP] version (storing the edit
     * plan JSON). The original XML is never mutated.
     */
    fun runAiTuneup() {
        if (_uiState.value.isBusy) return
        val prompt = _uiState.value.aiPrompt
        if (prompt.isBlank()) return
        if (_uiState.value.expensiveAiBlocked) {
            _uiState.update { reducer.aiFailed(it, expensiveBlockMessage(it)) }
            return
        }

        _uiState.update { reducer.startAi(it) }
        aiJob = viewModelScope.launch {
            val projectId = ensureProject()
            if (projectId == null) {
                _uiState.update { reducer.aiFailed(it, VectorTuneupReducer.ERROR_CREATE_PROJECT) }
                return@launch
            }
            val source = _uiState.value.sourceVersion
            if (source == null) {
                _uiState.update { reducer.aiFailed(it, VectorTuneupReducer.ERROR_NEED_VECTOR) }
                return@launch
            }
            val credentials = resolveCredentials()
            if (credentials == null) {
                _uiState.update { reducer.aiFailed(it, VectorTuneupReducer.AI_NEED_KEY) }
                return@launch
            }
            val modelId = preferencesManager.defaultModel.first()
            val document = AndroidVectorDrawableParser.parse(source.xml)
            val metrics = VectorMetricsAnalyzer.analyze(document, source.xml)
            val request = VectorTuneupAiRequest(
                xml = source.xml,
                document = document,
                metrics = metrics,
                userPrompt = prompt,
                modelId = modelId,
                baseUrl = credentials.baseUrl,
                apiKey = credentials.apiKey,
            )

            aiService.tuneUp(request).collect { chunk ->
                when (chunk) {
                    is VectorTuneupAiChunk.Delta -> Unit
                    is VectorTuneupAiChunk.Complete -> {
                        val result = chunk.result
                        val version = runCatching {
                            repository.addVersion(
                                projectId = projectId,
                                parentId = source.persistedId,
                                label = "AI Tune-Up",
                                instruction = prompt,
                                mode = VectorTuneupMode.AI_TUNE_UP,
                                xml = result.applyResult.xml,
                                metrics = result.applyResult.metrics,
                                warnings = VectorTuneupReducer.aiCandidateWarnings(result),
                                reportSummary = result.applyResult.summary,
                                editPlanJson = runCatching { gson.toJson(result.plan) }.getOrNull(),
                            )
                        }.getOrElse {
                            Log.w(TAG, "Persist AI tune-up version failed", it)
                            _uiState.update { s -> reducer.aiFailed(s, VectorTuneupReducer.ERROR_SAVE_VERSION) }
                            return@collect
                        }
                        val all = repository.getVersions(projectId)
                        _uiState.update { s ->
                            reducer.stagePersistedVersion(s, version, all).copy(
                                isAiRunning = false,
                                aiStatusMessage = VectorTuneupReducer.aiStatus(result),
                                lastAiSummary = result.plan.summary.ifBlank { null },
                            )
                        }
                    }
                    is VectorTuneupAiChunk.Error ->
                        _uiState.update { s -> reducer.aiFailed(s, chunk.message) }
                }
            }
        }
    }

    fun cancelAiTuneup() {
        aiJob?.cancel(); aiJob = null
        _uiState.update { it.copy(isAiRunning = false, aiStatusMessage = "AI Tune-Up cancelled.") }
    }

    fun onRedrawPromptChanged(prompt: String) {
        _uiState.update { reducer.onRedrawPromptChanged(it, prompt) }
    }

    /**
     * Runs a semantic redraw. Auto-creates a project if needed, branches from
     * the resolved source version, compiles the scene, and persists the result
     * as a [VectorTuneupMode.AI_REDRAW] version (storing the scene JSON).
     */
    fun runSemanticRedraw() {
        if (_uiState.value.isBusy) return
        val prompt = _uiState.value.redrawPrompt
        if (prompt.isBlank()) return
        if (_uiState.value.expensiveAiBlocked) {
            _uiState.update { reducer.redrawFailed(it, expensiveBlockMessage(it)) }
            return
        }

        _uiState.update { reducer.startRedraw(it) }
        redrawJob = viewModelScope.launch {
            val projectId = ensureProject()
            if (projectId == null) {
                _uiState.update { reducer.redrawFailed(it, VectorTuneupReducer.ERROR_CREATE_PROJECT) }
                return@launch
            }
            val source = _uiState.value.sourceVersion
            if (source == null) {
                _uiState.update { reducer.redrawFailed(it, VectorTuneupReducer.ERROR_NEED_VECTOR) }
                return@launch
            }
            val credentials = resolveCredentials()
            if (credentials == null) {
                _uiState.update { reducer.redrawFailed(it, VectorTuneupReducer.REDRAW_NEED_KEY) }
                return@launch
            }
            val modelId = preferencesManager.defaultModel.first()
            val document = AndroidVectorDrawableParser.parse(source.xml)
            val metrics = VectorMetricsAnalyzer.analyze(document, source.xml)
            val request = VectorRedrawAiRequest(
                xml = source.xml,
                document = document,
                metrics = metrics,
                userPrompt = prompt,
                modelId = modelId,
                baseUrl = credentials.baseUrl,
                apiKey = credentials.apiKey,
            )

            redrawService.redraw(request).collect { chunk ->
                when (chunk) {
                    is VectorRedrawAiChunk.Delta -> Unit
                    is VectorRedrawAiChunk.Complete -> {
                        val result = chunk.result
                        val warnings = VectorTuneupReducer.redrawCandidateWarnings(result)
                        val version = runCatching {
                            repository.addVersion(
                                projectId = projectId,
                                parentId = source.persistedId,
                                label = "AI Redraw",
                                instruction = prompt,
                                mode = VectorTuneupMode.AI_REDRAW,
                                xml = result.compileResult.xml,
                                metrics = result.compileResult.metrics,
                                warnings = warnings,
                                reportSummary = VectorTuneupReducer.redrawSummary(
                                    result.scene, result.compileResult, warnings.size,
                                ),
                                sceneJson = runCatching { gson.toJson(result.scene) }.getOrNull(),
                            )
                        }.getOrElse {
                            Log.w(TAG, "Persist AI redraw version failed", it)
                            _uiState.update { s -> reducer.redrawFailed(s, VectorTuneupReducer.ERROR_SAVE_VERSION) }
                            return@collect
                        }
                        val all = repository.getVersions(projectId)
                        _uiState.update { s ->
                            reducer.stagePersistedVersion(s, version, all).copy(
                                isRedrawRunning = false,
                                redrawStatusMessage = VectorTuneupReducer.redrawStatus(result.scene),
                                lastRedrawSummary = result.scene.styleIntent.ifBlank { null },
                            )
                        }
                    }
                    is VectorRedrawAiChunk.Error ->
                        _uiState.update { s -> reducer.redrawFailed(s, chunk.message) }
                }
            }
        }
    }

    fun cancelSemanticRedraw() {
        redrawJob?.cancel(); redrawJob = null
        _uiState.update { it.copy(isRedrawRunning = false, redrawStatusMessage = "AI Redraw cancelled.") }
    }

    // ---- project management (Phase 6) ----

    /** Explicitly saves the current parsed input as a durable project. */
    fun createProjectFromCurrentInput(title: String = VectorTuneupRepository.DEFAULT_TITLE) {
        if (_uiState.value.isSaved) return
        viewModelScope.launch { ensureProject(title) }
    }

    /** Reopens a saved project, restoring its versions and active selection. */
    fun openProject(projectId: String) {
        viewModelScope.launch {
            val project = runCatching { repository.getProject(projectId) }.getOrNull()
            if (project == null) {
                _uiState.update { it.copy(errorMessage = VectorTuneupReducer.ERROR_LOAD_PROJECT) }
                return@launch
            }
            val versions = repository.getVersions(projectId)
            _uiState.update { reducer.loadProject(it, project, versions) }
        }
    }

    fun selectVersion(versionId: String) {
        _uiState.update { reducer.selectVersion(it, versionId) }
    }

    fun setActiveVersion(versionId: String) {
        val projectId = _uiState.value.projectId ?: return
        _uiState.update { reducer.setActiveVersion(it, versionId) }
        viewModelScope.launch {
            runCatching { repository.setActiveVersion(projectId, versionId) }
                .onFailure { Log.w(TAG, "Set active version failed", it) }
        }
    }

    fun renameProject(title: String) {
        val projectId = _uiState.value.projectId ?: return
        if (title.isBlank()) return
        _uiState.update { reducer.renameProject(it, title) }
        viewModelScope.launch {
            runCatching { repository.renameProject(projectId, title) }
                .onFailure {
                    Log.w(TAG, "Rename project failed", it)
                    emit(VectorTuneupEvent.ShowMessage(VectorTuneupReducer.ERROR_RENAME))
                }
        }
    }

    fun deleteCurrentProject() {
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.deleteProject(projectId) }
                .onFailure { Log.w(TAG, "Delete project failed", it) }
                .isSuccess
            if (ok) {
                _uiState.value = reducer.reset()
            } else {
                emit(VectorTuneupEvent.ShowMessage(VectorTuneupReducer.ERROR_DELETE))
            }
        }
    }

    // ---- preview + visual diff (Phase 8) ----

    fun setVisualDiffMode(mode: VectorVisualDiffMode) {
        _uiState.update { reducer.setVisualDiffMode(it, mode) }
    }

    // ---- advanced editing + quality scoring (Phase 7) ----

    fun togglePathSelection(pathId: String) {
        _uiState.update { reducer.togglePathSelection(it, pathId) }
    }

    fun clearPathSelection() {
        _uiState.update { reducer.clearPathSelection(it) }
    }

    /**
     * Recomputes the per-path catalog, quality scores, and original-vs-source
     * diff for the current source version and folds them into state. Pure
     * deterministic analysis — no AI, no network.
     */
    fun refreshSelectedVersionAnalysis() {
        viewModelScope.launch {
            val state = _uiState.value
            val source = state.sourceVersion
            if (source == null) {
                _uiState.update { reducer.manualEditFailed(it, VectorTuneupReducer.MANUAL_ANALYZE_FAILED) }
                return@launch
            }
            val analysis = runCatching {
                val document = AndroidVectorDrawableParser.parse(source.xml)
                val candidate = VectorVersionQualityInput(
                    source.xml, document, VectorMetricsAnalyzer.analyze(document, source.xml),
                )
                val original = state.original
                    ?.takeIf { it.id != source.id }
                    ?.let {
                        val od = AndroidVectorDrawableParser.parse(it.xml)
                        VectorVersionQualityInput(it.xml, od, VectorMetricsAnalyzer.analyze(od, it.xml))
                    }
                val catalog = VectorPathCatalog.catalog(document)
                val scores = VectorQualityScorer.score(original, candidate)
                val diff = original?.let { VectorVersionDiffAnalyzer.diff(it, candidate) }
                Triple(catalog, scores, diff)
            }.getOrElse {
                Log.w(TAG, "Analyze selected version failed", it)
                _uiState.update { s -> reducer.manualEditFailed(s, VectorTuneupReducer.MANUAL_ANALYZE_FAILED) }
                return@launch
            }
            _uiState.update {
                reducer.loadAnalysisForSelectedVersion(it, analysis.first, analysis.second, analysis.third)
            }
        }
    }

    fun deleteSelectedPaths() =
        applySelectedEdit("Delete Paths") { ids -> listOf(VectorManualEdit.DeletePaths(ids)) }

    fun recolorSelectedPaths(strokeColor: String?, fillColor: String?) =
        applySelectedEdit("Recolor") { ids ->
            listOf(VectorManualEdit.RecolorPaths(ids, strokeColor = strokeColor, fillColor = fillColor))
        }

    fun restyleSelectedPaths(strokeWidth: Float?, lineCap: String?, lineJoin: String?) =
        applySelectedEdit("Restyle") { ids ->
            listOf(VectorManualEdit.RestylePaths(ids, strokeWidth = strokeWidth, lineCap = lineCap, lineJoin = lineJoin))
        }

    fun simplifySelectedPaths(tolerance: Float, simplifyFills: Boolean = false) =
        applySelectedEdit("Simplify") { ids ->
            listOf(VectorManualEdit.SimplifyPaths(ids, tolerance = tolerance, simplifyFills = simplifyFills))
        }

    /** Applies a batch restyle (target by color/path group) and persists it. */
    fun applyBatchRestyle(restyle: VectorBatchRestyle) {
        if (_uiState.value.isBusy) return
        persistManualEdit("Batch Restyle") { document, xml ->
            VectorBatchRestyleApplier.apply(document, xml, restyle)
        }
    }

    /** Guards on a non-empty selection, then persists the built edits. */
    private fun applySelectedEdit(label: String, build: (List<String>) -> List<VectorManualEdit>) {
        if (_uiState.value.isBusy) return
        val ids = _uiState.value.selectedPathIds.toList()
        if (ids.isEmpty()) {
            _uiState.update { reducer.manualEditFailed(it, VectorTuneupReducer.MANUAL_NEED_SELECTION) }
            return
        }
        persistManualEdit(label) { document, xml ->
            VectorManualEditApplier.apply(document, xml, build(ids))
        }
    }

    /**
     * Shared manual-edit pipeline: ensure a project, resolve the source version,
     * run the deterministic [apply], persist a [VectorTuneupMode.MANUAL_EDIT]
     * child branched from the source, then re-analyze the new version.
     */
    private fun persistManualEdit(
        label: String,
        apply: (VectorDocument, String) -> VectorManualEditResult,
    ) {
        viewModelScope.launch {
            val projectId = ensureProject()
            if (projectId == null) {
                _uiState.update { reducer.manualEditFailed(it, VectorTuneupReducer.ERROR_CREATE_PROJECT) }
                return@launch
            }
            val source = _uiState.value.sourceVersion
            if (source == null) {
                _uiState.update { reducer.manualEditFailed(it, VectorTuneupReducer.ERROR_NEED_VECTOR) }
                return@launch
            }
            val result = runCatching {
                apply(AndroidVectorDrawableParser.parse(source.xml), source.xml)
            }.getOrElse {
                Log.w(TAG, "Manual edit failed", it)
                _uiState.update { s -> reducer.manualEditFailed(s, VectorTuneupReducer.MANUAL_APPLY_FAILED) }
                return@launch
            }
            val version = runCatching {
                repository.addVersion(
                    projectId = projectId,
                    parentId = source.persistedId,
                    label = label,
                    instruction = result.summary,
                    mode = VectorTuneupMode.MANUAL_EDIT,
                    xml = result.xml,
                    metrics = result.metrics,
                    warnings = result.warnings,
                    reportSummary = result.summary,
                )
            }.getOrElse {
                Log.w(TAG, "Persist manual edit failed", it)
                _uiState.update { s -> reducer.manualEditFailed(s, VectorTuneupReducer.MANUAL_SAVE_FAILED) }
                return@launch
            }
            val all = repository.getVersions(projectId)
            _uiState.update { reducer.stageManualEditVersion(it, version, all) }
            refreshSelectedVersionAnalysis()
        }
    }

    // ---- portable bundle import + version graph (Phase 10) ----

    fun onBundleImportTextChanged(text: String) {
        _uiState.update { reducer.onBundleImportTextChanged(it, text) }
    }

    /**
     * Imports a pasted portable project bundle as a brand-new local project,
     * then opens it. A blank field or an unimportable bundle leaves the current
     * project untouched and shows a friendly status.
     */
    fun importBundleFromText() {
        if (_uiState.value.isBusy) return
        val text = _uiState.value.bundleImportText
        if (text.isBlank()) {
            _uiState.update { reducer.bundleImportFailed(it, VectorTuneupReducer.BUNDLE_IMPORT_BLANK) }
            return
        }
        _uiState.update { reducer.startBundleImport(it) }
        viewModelScope.launch {
            val result = runCatching { repository.importBundle(text) }.getOrElse {
                Log.w(TAG, "Bundle import failed", it)
                null
            }
            if (result?.project == null) {
                _uiState.update { reducer.bundleImportFailed(it, VectorTuneupReducer.BUNDLE_IMPORT_FAILED) }
                return@launch
            }
            val project = result.project
            val versions = repository.getVersions(project.id)
            _uiState.update { reducer.bundleImportSucceeded(it, project, versions, result.warnings) }
        }
    }

    /**
     * Duplicates the selected/source version as a new manual-edit child and
     * stages it as active. Requires a saved project and a persisted source.
     */
    fun duplicateSelectedVersion() {
        if (_uiState.value.isBusy) return
        val state = _uiState.value
        val projectId = state.projectId
        val sourceId = state.sourceVersion?.persistedId
        if (projectId == null || sourceId == null) {
            _uiState.update { reducer.versionGraphActionFailed(it, VectorTuneupReducer.VERSION_DUPLICATE_FAILED) }
            return
        }
        viewModelScope.launch {
            val version = runCatching {
                repository.duplicateVersion(projectId, sourceId)
            }.getOrElse {
                Log.w(TAG, "Duplicate version failed", it)
                _uiState.update { reducer.versionGraphActionFailed(it, VectorTuneupReducer.VERSION_DUPLICATE_FAILED) }
                return@launch
            }
            val all = repository.getVersions(projectId)
            _uiState.update {
                reducer.stagePersistedVersion(it, version, all).copy(selectedTab = VectorTuneupTab.HISTORY)
            }
        }
    }

    /**
     * Deletes the selected version when it is a safe leaf. The original and
     * versions with children are blocked with a friendly message; a successful
     * delete reloads the project and re-selects the active version.
     */
    fun deleteSelectedVersion() {
        if (_uiState.value.isBusy) return
        val state = _uiState.value
        val projectId = state.projectId
        val selected = state.selectedVersion
        val versionId = selected?.persistedId
        if (projectId == null || selected == null || versionId == null) {
            _uiState.update { reducer.versionGraphActionFailed(it, VectorTuneupReducer.VERSION_DELETE_FAILED) }
            return
        }
        if (selected.mode == VectorTuneupMode.ORIGINAL) {
            _uiState.update { reducer.versionGraphActionFailed(it, VectorTuneupReducer.VERSION_DELETE_ORIGINAL) }
            return
        }
        viewModelScope.launch {
            val deleted = runCatching {
                repository.deleteLeafVersion(projectId, versionId)
            }.getOrElse {
                Log.w(TAG, "Delete version failed", it)
                _uiState.update { reducer.versionGraphActionFailed(it, VectorTuneupReducer.VERSION_DELETE_FAILED) }
                return@launch
            }
            if (!deleted) {
                _uiState.update { reducer.versionGraphActionFailed(it, VectorTuneupReducer.VERSION_DELETE_HAS_CHILDREN) }
                return@launch
            }
            val project = runCatching { repository.getProject(projectId) }.getOrNull()
            val all = repository.getVersions(projectId)
            if (project != null) {
                _uiState.update {
                    reducer.loadProject(it, project, all).copy(selectedTab = VectorTuneupTab.HISTORY)
                }
            }
        }
    }

    // ---- file import + large-input safety (Phase 11) ----

    /** Opt the user in/out of running expensive AI on large (EXTREME) input. */
    fun setAllowExpensiveOnLargeInput(allow: Boolean) {
        _uiState.update { reducer.setAllowExpensiveOnLargeInput(it, allow) }
    }

    /**
     * Reads a picked vector file (XML/SVG/bundle JSON) and routes it: a bundle is
     * imported as a new project, anything else is placed in the input and parsed.
     * Oversized/unreadable files surface a friendly status and change nothing.
     */
    fun importVectorFileFromUri(uri: Uri, displayName: String? = null) {
        readThen(uri) { text -> importVectorTextFromFile(displayName, text) }
    }

    /** Reads a picked file and imports it as a project bundle (History tab). */
    fun importBundleFileFromUri(uri: Uri, displayName: String? = null) {
        readThen(uri) { text -> importBundleTextFromFile(displayName, text) }
    }

    /**
     * Routes already-read import text. Oversized text is rejected; a project bundle
     * is sent to bundle import; everything else is placed in the input and parsed.
     */
    fun importVectorTextFromFile(displayName: String?, text: String) {
        when (reducer.classifyImportText(text)) {
            VectorTuneupReducer.ImportRoute.TOO_LARGE ->
                _uiState.update { reducer.fileImportFailed(it, VectorTuneupReducer.FILE_TOO_LARGE) }
            VectorTuneupReducer.ImportRoute.BUNDLE -> importBundleTextFromFile(displayName, text)
            VectorTuneupReducer.ImportRoute.VECTOR ->
                _uiState.update { reducer.importVectorText(it, displayName, text) }
        }
    }

    /** Stages already-read bundle JSON and imports it as a new project. */
    fun importBundleTextFromFile(displayName: String?, text: String) {
        if (text.length > VectorInputLimits.MAX_PASTE_CHARS) {
            _uiState.update { reducer.fileImportFailed(it, VectorTuneupReducer.FILE_TOO_LARGE) }
            return
        }
        _uiState.update { reducer.onBundleImportTextChanged(it, text) }
        importBundleFromText()
    }

    /** Reads [uri] via the file-reader seam, then hands the text to [onText]. */
    private fun readThen(uri: Uri, onText: (String) -> Unit) {
        viewModelScope.launch {
            val result = fileReader.readText(uri, VectorInputLimits.MAX_IMPORT_BYTES)
            val text = result.getOrElse { t ->
                val message = when ((t as? VectorTuneupFileReader.FileReadException)?.error) {
                    VectorTuneupFileReader.ReadError.TOO_LARGE -> VectorTuneupReducer.FILE_TOO_LARGE
                    else -> VectorTuneupReducer.FILE_UNREADABLE
                }
                _uiState.update { reducer.fileImportFailed(it, message) }
                return@launch
            }
            onText(text)
        }
    }

    private fun expensiveBlockMessage(state: VectorTuneupUiState): String =
        if (state.isInputUnsafe) VectorTuneupReducer.AI_BLOCKED_UNSAFE
        else VectorTuneupReducer.AI_BLOCKED_EXTREME

    // ---- export ----

    /** Updates the portable export format used by the Export tab. */
    fun setExportFormat(format: VectorExportFormat) {
        _uiState.update { reducer.setExportFormat(it, format) }
    }

    /**
     * Exports the selected/active version (top-bar + Export tab default). The
     * top bar always exports Android XML to avoid surprising users; the Export
     * tab calls [exportSelectedVersion] with the chosen format.
     */
    fun exportCandidate() =
        export(reducer.resolveExportVersion(_uiState.value, null), VectorExportFormat.ANDROID_VECTOR_XML)

    /** Exports the selected/active version in the requested [format]. */
    fun exportSelectedVersion(format: VectorExportFormat = _uiState.value.exportFormat) =
        export(reducer.resolveExportVersion(_uiState.value, null), format)

    /** Exports a specific version from the history panel as Android XML. */
    fun exportVersion(versionId: String) =
        export(reducer.resolveExportVersion(_uiState.value, versionId), VectorExportFormat.ANDROID_VECTOR_XML)

    private fun export(version: VectorVersionUi?, format: VectorExportFormat) {
        if (version == null) {
            emit(VectorTuneupEvent.ShowMessage(VectorTuneupReducer.ERROR_NEED_VECTOR))
            return
        }
        viewModelScope.launch {
            runCatching {
                if (format == VectorExportFormat.PROJECT_BUNDLE) {
                    exporter.exportBundle("vector-tuneup", buildBundleJson())
                } else {
                    exporter.exportVersion("vector-tuneup", version.xml, format)
                }
            }
                .onSuccess { uri -> emit(VectorTuneupEvent.ExportReady(uri, format.mimeType)) }
                .onFailure { t ->
                    Log.w(TAG, "Vector version export failed", t)
                    emit(VectorTuneupEvent.ShowMessage("Vector could not be exported."))
                }
        }
    }

    /** Serializes the current project's versions into a portable JSON bundle. */
    private fun buildBundleJson(): String {
        val state = _uiState.value
        val versions = state.versions.ifEmpty { listOfNotNull(state.original, state.candidate) }
        return VectorPortableBundle.build(
            project = VectorPortableBundle.ProjectInfo(
                title = state.projectTitle,
                createdAt = 0L,
                updatedAt = System.currentTimeMillis(),
            ),
            versions = versions.map { v ->
                VectorPortableBundle.VersionInfo(
                    id = v.id,
                    parentId = v.parentId,
                    label = v.label,
                    mode = v.mode.name,
                    instruction = v.instruction,
                    xml = v.xml,
                    metrics = v.metrics,
                    warnings = v.warnings,
                    reportSummary = v.reportSummary,
                    createdAt = v.createdAt,
                )
            },
        )
    }

    // ---- internals ----

    /**
     * Ensures a durable project exists, parsing the input first if needed and
     * creating the project (with its original version) on demand. Returns the
     * project id, or null if the input could not be parsed/saved (a friendly
     * error is left in state for the caller to surface).
     */
    private suspend fun ensureProject(title: String? = null): String? {
        var state = _uiState.value
        state.projectId?.let { return it }

        if (!state.hasOriginal) {
            state = reducer.parseInput(state)
            _uiState.value = state
            if (!state.hasOriginal) return null
        }
        // Pass the exact pasted input so the project's sourceXml preserves it
        // (SVG included); the repository canonicalizes the original version.
        val input = state.inputXml.ifBlank { state.original?.xml.orEmpty() }
        val project = runCatching {
            repository.createProjectFromInput(title ?: state.projectTitle, input)
        }.getOrElse {
            Log.w(TAG, "Create project failed", it)
            _uiState.update { it.copy(errorMessage = VectorTuneupReducer.ERROR_CREATE_PROJECT) }
            return null
        }
        val versions = repository.getVersions(project.id)
        _uiState.update { reducer.loadProject(it, project, versions) }
        return project.id
    }

    private suspend fun resolveCredentials(): com.aichat.sandbox.data.local.ProviderCredentials? {
        val modelId = preferencesManager.defaultModel.first()
        val credentials = runCatching { preferencesManager.credentialsFor(modelId) }
            .getOrElse {
                Log.w(TAG, "Failed to resolve AI credentials", it)
                return null
            }
        return credentials.takeIf { it.apiKey.isNotBlank() }
    }

    private fun emit(event: VectorTuneupEvent) {
        _events.tryEmit(event)
    }

    companion object {
        private const val TAG = "VectorTuneupVM"

        /** Small built-in VectorDrawable for the "Paste sample" convenience action. */
        val SAMPLE_XML: String = """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path
                    android:name="frame"
                    android:pathData="M16,16 L92,16 L92,92 L16,92 Z"
                    android:fillColor="#00000000"
                    android:strokeColor="#2D2D2D"
                    android:strokeWidth="2"
                    android:strokeLineCap="round"
                    android:strokeLineJoin="round" />
                <path
                    android:name="diagonal"
                    android:pathData="M16,16 L24,20 L32,23 L41,27 L50,31 L60,36 L72,42 L84,49 L92,54"
                    android:strokeColor="#109F5C"
                    android:strokeWidth="2"
                    android:strokeLineCap="round" />
                <path
                    android:name="dot"
                    android:pathData="M54,54 m-4,0 a4,4 0 1,0 8,0 a4,4 0 1,0 -8,0 Z"
                    android:fillColor="#D62828" />
            </vector>
        """.trimIndent()
    }
}
