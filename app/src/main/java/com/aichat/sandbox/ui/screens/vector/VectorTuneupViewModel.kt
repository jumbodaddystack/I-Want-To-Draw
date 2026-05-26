package com.aichat.sandbox.ui.screens.vector

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorTuneupExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val reducer = VectorTuneupReducer()

    private val _uiState = MutableStateFlow(VectorTuneupUiState())
    val uiState: StateFlow<VectorTuneupUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VectorTuneupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VectorTuneupEvent> = _events.asSharedFlow()

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
        _uiState.value = reducer.reset()
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
