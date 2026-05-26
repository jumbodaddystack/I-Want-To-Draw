package com.aichat.sandbox.ui.screens.vector

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupProject
import com.aichat.sandbox.data.repository.VectorTuneupRepository
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorDrawableOptimizer
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorRedrawAiChunk
import com.aichat.sandbox.data.vector.VectorRedrawAiRequest
import com.aichat.sandbox.data.vector.VectorRedrawAiService
import com.aichat.sandbox.data.vector.VectorTuneupAiChunk
import com.aichat.sandbox.data.vector.VectorTuneupAiRequest
import com.aichat.sandbox.data.vector.VectorTuneupAiService
import com.aichat.sandbox.data.vector.VectorTuneupExporter
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

    // ---- export ----

    /** Exports the selected/active version (top-bar + Export tab default). */
    fun exportCandidate() = export(reducer.resolveExportVersion(_uiState.value, null))

    /** Exports a specific version from the history panel. */
    fun exportVersion(versionId: String) =
        export(reducer.resolveExportVersion(_uiState.value, versionId))

    private fun export(version: VectorVersionUi?) {
        if (version == null) {
            emit(VectorTuneupEvent.ShowMessage(VectorTuneupReducer.ERROR_NEED_VECTOR))
            return
        }
        viewModelScope.launch {
            runCatching { exporter.exportXml("vector-tuneup", version.xml) }
                .onSuccess { uri -> emit(VectorTuneupEvent.ExportReady(uri)) }
                .onFailure { t ->
                    Log.w(TAG, "Vector version export failed", t)
                    emit(VectorTuneupEvent.ShowMessage("Vector XML could not be exported."))
                }
        }
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
        val xml = state.original?.xml ?: state.inputXml
        val project = runCatching {
            repository.createProjectFromXml(title ?: state.projectTitle, xml)
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
