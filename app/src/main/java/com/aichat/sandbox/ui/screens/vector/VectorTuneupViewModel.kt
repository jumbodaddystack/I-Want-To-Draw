package com.aichat.sandbox.ui.screens.vector

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorTuneupAiChunk
import com.aichat.sandbox.data.vector.VectorTuneupAiRequest
import com.aichat.sandbox.data.vector.VectorTuneupAiService
import com.aichat.sandbox.data.vector.VectorTuneupExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Vector Art Tune-Up workspace.
 *
 * All workflow logic lives in the pure [VectorTuneupReducer]; this class only
 * owns the [StateFlow], runs optimize/export off the main thread, and emits
 * one-shot [VectorTuneupEvent]s for the screen. No AI, no model calls, no
 * persistent storage — everything is in-memory and rebuilt from the input XML.
 */
@HiltViewModel
class VectorTuneupViewModel @Inject constructor(
    private val exporter: VectorTuneupExporter,
    private val aiService: VectorTuneupAiService,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val reducer = VectorTuneupReducer()

    private val _uiState = MutableStateFlow(VectorTuneupUiState())
    val uiState: StateFlow<VectorTuneupUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VectorTuneupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VectorTuneupEvent> = _events.asSharedFlow()

    /** Tracks the in-flight AI request so [cancelAiTuneup] can stop it. */
    private var aiJob: Job? = null

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
     * Optimizes the current input. Optimization is CPU-only and fast, but it is
     * run on [viewModelScope] with an [isOptimizing] flag so the UI can show
     * progress and the call never blocks input dispatch.
     */
    fun optimize() {
        if (_uiState.value.isOptimizing) return
        _uiState.update { it.copy(isOptimizing = true, errorMessage = null) }
        viewModelScope.launch {
            _uiState.update { reducer.optimize(it) }
        }
    }

    fun clearCandidate() {
        _uiState.update { reducer.clearCandidate(it) }
    }

    fun reset() {
        aiJob?.cancel()
        aiJob = null
        _uiState.value = reducer.reset()
    }

    fun onAiPromptChanged(prompt: String) {
        _uiState.update { reducer.onAiPromptChanged(it, prompt) }
    }

    /**
     * Runs a model-guided tune-up. Parses the current input first if needed,
     * uses the existing candidate (or the original) as the source, resolves the
     * default model's credentials, and applies the validated plan as a new
     * candidate. The original XML is never mutated; failures surface as a
     * friendly status message, not a crash.
     */
    fun runAiTuneup() {
        if (_uiState.value.isBusy) return

        var state = _uiState.value
        if (!state.hasOriginal) {
            state = reducer.parseInput(state)
            _uiState.value = state
            if (!state.hasOriginal) return // parse error already surfaced
        }
        val source = state.candidate ?: state.original ?: return
        val prompt = state.aiPrompt
        if (prompt.isBlank()) return

        _uiState.update { reducer.startAi(it) }
        aiJob = viewModelScope.launch {
            val modelId = preferencesManager.defaultModel.first()
            val credentials = runCatching { preferencesManager.credentialsFor(modelId) }
                .getOrElse {
                    Log.w(TAG, "Failed to resolve AI credentials", it)
                    _uiState.update { s -> reducer.aiFailed(s, VectorTuneupReducer.AI_NEED_KEY) }
                    return@launch
                }
            if (credentials.apiKey.isBlank()) {
                _uiState.update { s -> reducer.aiFailed(s, VectorTuneupReducer.AI_NEED_KEY) }
                return@launch
            }

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
                    is VectorTuneupAiChunk.Delta -> Unit // raw JSON: spinner only, no display
                    is VectorTuneupAiChunk.Complete ->
                        _uiState.update { s -> reducer.stageAiCandidate(s, chunk.result) }
                    is VectorTuneupAiChunk.Error ->
                        _uiState.update { s -> reducer.aiFailed(s, chunk.message) }
                }
            }
        }
    }

    fun cancelAiTuneup() {
        aiJob?.cancel()
        aiJob = null
        _uiState.update { it.copy(isAiRunning = false, aiStatusMessage = "AI Tune-Up cancelled.") }
    }

    /**
     * Exports the candidate XML (falling back to the original if no candidate
     * exists) to a shareable URI and emits [VectorTuneupEvent.ExportReady].
     * Failures surface as a friendly [VectorTuneupEvent.ShowMessage]; the
     * technical detail is logged, never shown.
     */
    fun exportCandidate() {
        val state = _uiState.value
        val version = state.candidate ?: state.original
        if (version == null) {
            emit(VectorTuneupEvent.ShowMessage("Parse or optimize a vector first."))
            return
        }
        viewModelScope.launch {
            runCatching { exporter.exportXml("vector-tuneup", version.xml) }
                .onSuccess { uri -> emit(VectorTuneupEvent.ExportReady(uri)) }
                .onFailure { t ->
                    Log.w(TAG, "Vector candidate export failed", t)
                    emit(VectorTuneupEvent.ShowMessage("Candidate XML could not be exported."))
                }
        }
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
