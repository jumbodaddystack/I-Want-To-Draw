package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.AndroidVectorDrawableWriter
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorDocumentValidator
import com.aichat.sandbox.data.vector.VectorDrawableOptimizer
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorOptimizationReport
import com.aichat.sandbox.data.vector.VectorOptimizationResult
import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * Pure state machine for the Vector Art Tune-Up workspace.
 *
 * Every transition is a `(VectorTuneupUiState) -> VectorTuneupUiState` function
 * built only from the deterministic Phase 1/2 vector foundation, so the entire
 * workflow can be exercised by plain JVM unit tests with no Compose, Hilt, or
 * Android framework on the classpath. [VectorTuneupViewModel] is a thin shell
 * that owns a `StateFlow` and delegates here.
 *
 * The vector operations are injected as function parameters (defaulting to the
 * real implementations) purely so tests can simulate a failing optimizer
 * without crafting input that the robust optimizer would never reject.
 */
class VectorTuneupReducer(
    private val parse: (String) -> VectorDocument = AndroidVectorDrawableParser::parse,
    private val analyze: (VectorDocument, String) -> VectorMetrics =
        { doc, xml -> VectorMetricsAnalyzer.analyze(doc, xml) },
    private val validate: (VectorDocument) -> List<VectorWarning> =
        VectorDocumentValidator::validate,
    private val write: (VectorDocument) -> String = AndroidVectorDrawableWriter::write,
    private val optimize: (VectorDocument, String, VectorOptimizeOptions) -> VectorOptimizationResult =
        { doc, xml, opts -> VectorDrawableOptimizer.optimize(doc, xml, opts) },
) {

    /** Updates the input text and clears any stale error. Never auto-parses. */
    fun onXmlChanged(state: VectorTuneupUiState, xml: String): VectorTuneupUiState =
        state.copy(inputXml = xml, errorMessage = null)

    /** Replaces the optimizer settings. Never auto-optimizes. */
    fun updateOptions(
        state: VectorTuneupUiState,
        options: VectorOptimizeOptions,
    ): VectorTuneupUiState = state.copy(options = options)

    /** Drops the candidate, returning focus to diagnostics if it was showing it. */
    fun clearCandidate(state: VectorTuneupUiState): VectorTuneupUiState {
        val tab = when (state.selectedTab) {
            VectorTuneupTab.COMPARE, VectorTuneupTab.EXPORT ->
                if (state.hasOriginal) VectorTuneupTab.DIAGNOSTICS else VectorTuneupTab.INPUT
            else -> state.selectedTab
        }
        return state.copy(candidate = null, selectedTab = tab)
    }

    /** Clears everything back to a blank workspace. */
    fun reset(): VectorTuneupUiState = VectorTuneupUiState()

    fun selectTab(state: VectorTuneupUiState, tab: VectorTuneupTab): VectorTuneupUiState =
        state.copy(selectedTab = tab)

    /**
     * Parses [VectorTuneupUiState.inputXml] into a [VectorVersionUi] original.
     * Blank input and unparseable XML produce a friendly error and clear any
     * previous original/candidate, so diagnostics never show stale data for XML
     * that no longer parses.
     */
    fun parseInput(state: VectorTuneupUiState): VectorTuneupUiState {
        val xml = state.inputXml
        if (xml.isBlank()) {
            return state.copy(
                errorMessage = ERROR_BLANK,
                original = null,
                candidate = null,
            )
        }
        val parsed = runCatching { buildVersion(xml, VectorVersionUi.ID_ORIGINAL, "Original") }
            .getOrNull()

        if (parsed == null || isUnparseable(parsed)) {
            return state.copy(
                errorMessage = ERROR_PARSE,
                original = null,
                candidate = null,
            )
        }
        return state.copy(
            original = parsed,
            candidate = null,
            errorMessage = null,
            selectedTab = VectorTuneupTab.DIAGNOSTICS,
        )
    }

    /**
     * Produces an optimized [candidate] from the current input/original.
     *
     * Parses first if needed; surfaces a friendly error (and keeps the original
     * untouched) on blank input, unparseable XML, or any optimizer exception.
     * Callers are responsible for toggling [VectorTuneupUiState.isOptimizing]
     * around this call.
     */
    fun optimize(state: VectorTuneupUiState): VectorTuneupUiState {
        val base = if (state.hasOriginal) state else parseInput(state)
        val original = base.original
            ?: return base.copy(isOptimizing = false) // parse failed; error already set

        val result = runCatching {
            val document = parse(original.xml)
            optimize(document, original.xml, base.options)
        }.getOrElse {
            return base.copy(
                isOptimizing = false,
                errorMessage = ERROR_OPTIMIZE,
            )
        }

        val candidate = VectorVersionUi(
            id = VectorVersionUi.ID_CANDIDATE,
            label = "Optimized",
            xml = result.xml,
            metrics = result.report.after,
            warnings = result.report.warnings,
            reportSummary = summarize(result.report),
        )
        return base.copy(
            candidate = candidate,
            isOptimizing = false,
            errorMessage = null,
            selectedTab = VectorTuneupTab.COMPARE,
        )
    }

    // ---- helpers ----

    private fun buildVersion(xml: String, id: String, label: String): VectorVersionUi {
        val document = parse(xml)
        val metrics = analyze(document, xml)
        val warnings = (document.warnings + validate(document)).distinct()
        return VectorVersionUi(
            id = id,
            label = label,
            xml = xml,
            metrics = metrics,
            warnings = warnings,
        )
    }

    /**
     * The parser never throws; it reports a structural failure (root not
     * `<vector>`, malformed XML) as a [VectorWarning.Codes.MALFORMED_XML]
     * warning on an empty document. Treat that as "couldn't parse" for the UI.
     */
    private fun isUnparseable(version: VectorVersionUi): Boolean =
        version.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML }

    companion object {
        const val ERROR_BLANK = "Paste a VectorDrawable XML first."
        const val ERROR_PARSE =
            "Could not parse this XML. Check that the root element is <vector>."
        const val ERROR_OPTIMIZE =
            "Optimization failed, but your original XML was not changed."

        /** Human-readable before/after summary for the compare/export panels. */
        fun summarize(report: VectorOptimizationReport): String {
            val before = report.before
            val after = report.after
            val bytesDelta = before.xmlBytes - after.xmlBytes
            val pct = if (before.xmlBytes > 0) {
                (bytesDelta.toFloat() / before.xmlBytes * 100f)
            } else 0f
            return buildString {
                append("Size: ${before.xmlBytes} → ${after.xmlBytes} bytes")
                if (bytesDelta != 0) append(" (${"%+.0f".format(-pct)}%)")
                append('\n')
                append("Commands: ${before.commandCount} → ${after.commandCount}\n")
                append("Paths: ${before.pathCount} → ${after.pathCount}\n")
                append("Simplified ${report.simplifiedPathCount}, removed ${report.removedPathCount}")
                if (report.unsupportedPathCount > 0) {
                    append(", left ${report.unsupportedPathCount} unparsed path(s) untouched")
                }
            }
        }
    }
}
