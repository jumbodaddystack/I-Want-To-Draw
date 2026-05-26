package com.aichat.sandbox.ui.screens.vector

import android.net.Uri
import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.repository.VectorTuneupVersion
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * UI-facing models for the Vector Art Tune-Up workspace.
 *
 * These wrap the deterministic Phase 1/2 foundation (parser, metrics, optimizer)
 * and the Phase 6 persistence layer in shapes the Compose screen and its
 * [VectorTuneupViewModel] can render directly.
 */

/** The sections of the tune-up workspace, surfaced as tabs. */
enum class VectorTuneupTab {
    INPUT,
    DIAGNOSTICS,
    COMPARE,
    EDIT,
    HISTORY,
    EXPORT,
}

/**
 * One concrete version of a vector drawing in the workspace.
 *
 * Before a project is saved, the in-memory parse/optimize flow produces a single
 * [original] and [candidate] keyed by the stable [ID_ORIGINAL]/[ID_CANDIDATE]
 * constants. Once persisted (Phase 6), each version carries its real database
 * [id], its [parentId] lineage, [mode], originating [instruction], and
 * [createdAt] so the history panel can show the version tree.
 *
 * @property id Stable identifier (`original`/`candidate` in-memory, a UUID once persisted).
 * @property label Human label shown in compare/history/export UI.
 * @property xml The serialized VectorDrawable XML for this version.
 * @property metrics Diagnostics computed by `VectorMetricsAnalyzer`.
 * @property warnings Parser + validator warnings relevant to this version.
 * @property reportSummary Optional before/after or operation summary.
 * @property projectId Owning project id once persisted, else null.
 * @property parentId The version this one branched from, null for the original.
 * @property mode How this version was produced.
 * @property instruction The optimize/AI instruction that produced this version.
 * @property createdAt Persisted creation timestamp (0 for unsaved in-memory versions).
 */
data class VectorVersionUi(
    val id: String,
    val label: String,
    val xml: String,
    val metrics: VectorMetrics,
    val warnings: List<VectorWarning>,
    val reportSummary: String? = null,
    val projectId: String? = null,
    val parentId: String? = null,
    val mode: VectorTuneupMode = VectorTuneupMode.ORIGINAL,
    val instruction: String = "",
    val createdAt: Long = 0L,
) {
    companion object {
        const val ID_ORIGINAL = "original"
        const val ID_CANDIDATE = "candidate"
    }
}

/**
 * The persisted database id of this version, or null for the transient
 * in-memory [VectorVersionUi.ID_ORIGINAL]/[VectorVersionUi.ID_CANDIDATE]
 * placeholders. Safe to use as a child version's `parentId`.
 */
val VectorVersionUi.persistedId: String?
    get() = id.takeUnless { it == VectorVersionUi.ID_ORIGINAL || it == VectorVersionUi.ID_CANDIDATE }

/** Maps a persisted domain [VectorTuneupVersion] into its UI representation. */
fun VectorTuneupVersion.toUi(): VectorVersionUi = VectorVersionUi(
    id = id,
    label = label,
    xml = xml,
    metrics = metrics,
    warnings = warnings,
    reportSummary = reportSummary,
    projectId = projectId,
    parentId = parentId,
    mode = mode,
    instruction = instruction,
    createdAt = createdAt,
)

/**
 * One-shot events the [VectorTuneupViewModel] emits for the screen to consume
 * (fire a share intent, show a snackbar).
 */
sealed interface VectorTuneupEvent {
    data class ExportReady(val uri: Uri) : VectorTuneupEvent
    data class ShowMessage(val message: String) : VectorTuneupEvent
}
