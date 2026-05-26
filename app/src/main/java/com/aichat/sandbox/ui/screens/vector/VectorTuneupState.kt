package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.VectorOptimizeOptions

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
 * @property options Current optimizer settings.
 * @property isOptimizing True while an optimize pass is running (drives spinners).
 * @property errorMessage Friendly, user-facing error; never a stack trace.
 * @property selectedTab Currently selected workspace section.
 */
data class VectorTuneupUiState(
    val inputXml: String = "",
    val original: VectorVersionUi? = null,
    val candidate: VectorVersionUi? = null,
    val options: VectorOptimizeOptions = VectorOptimizeOptions(),
    val isOptimizing: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: VectorTuneupTab = VectorTuneupTab.INPUT,
) {
    /** True once the input has been parsed into an [original] version. */
    val hasOriginal: Boolean get() = original != null

    /** True once an optimized [candidate] has been produced. */
    val hasCandidate: Boolean get() = candidate != null
}
