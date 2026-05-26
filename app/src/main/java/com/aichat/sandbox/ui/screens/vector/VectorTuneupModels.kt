package com.aichat.sandbox.ui.screens.vector

import android.net.Uri
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * UI-facing models for the Vector Art Tune-Up workspace (Phase 3).
 *
 * These wrap the deterministic Phase 1/2 foundation (parser, metrics, optimizer)
 * in shapes the Compose screen and its [VectorTuneupViewModel] can render
 * directly. Nothing here performs AI calls, semantic redraw, or persistent
 * storage — all state is in-memory and rebuilt from the input XML on demand.
 */

/** The four sections of the tune-up workspace, surfaced as tabs. */
enum class VectorTuneupTab {
    INPUT,
    DIAGNOSTICS,
    COMPARE,
    EXPORT,
}

/**
 * One concrete version of a vector drawing in the workspace: either the parsed
 * [original] input or an optimized [candidate].
 *
 * @property id Stable identifier for the version (e.g. `original`, `candidate`).
 * @property label Human label shown in compare/export UI.
 * @property xml The serialized VectorDrawable XML for this version.
 * @property metrics Diagnostics computed by `VectorMetricsAnalyzer`.
 * @property warnings Parser + validator warnings relevant to this version.
 * @property reportSummary Optional before/after summary (candidate only).
 */
data class VectorVersionUi(
    val id: String,
    val label: String,
    val xml: String,
    val metrics: VectorMetrics,
    val warnings: List<VectorWarning>,
    val reportSummary: String? = null,
) {
    companion object {
        const val ID_ORIGINAL = "original"
        const val ID_CANDIDATE = "candidate"
    }
}

/**
 * One-shot events the [VectorTuneupViewModel] emits for the screen to consume
 * (fire a share intent, show a snackbar). Modeled on the rest of the app, which
 * keeps transient side effects out of the persistent UI state.
 */
sealed interface VectorTuneupEvent {
    data class ExportReady(val uri: Uri) : VectorTuneupEvent
    data class ShowMessage(val message: String) : VectorTuneupEvent
}
