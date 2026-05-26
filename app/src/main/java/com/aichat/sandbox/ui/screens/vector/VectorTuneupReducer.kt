package com.aichat.sandbox.ui.screens.vector

import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.AndroidVectorDrawableWriter
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorDocumentValidator
import com.aichat.sandbox.data.vector.VectorDrawableOptimizer
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorMetricsAnalyzer
import com.aichat.sandbox.data.vector.VectorEditPlanApplyResult
import com.aichat.sandbox.data.vector.VectorOptimizationReport
import com.aichat.sandbox.data.vector.VectorOptimizationResult
import com.aichat.sandbox.data.vector.VectorOptimizeOptions
import com.aichat.sandbox.data.vector.VectorRedrawAiResult
import com.aichat.sandbox.data.vector.VectorScene
import com.aichat.sandbox.data.vector.VectorSceneCompileResult
import com.aichat.sandbox.data.vector.VectorTuneupAiResult
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

    // ---- AI tune-up (Phase 4) ----

    /** Updates the AI instruction text. Never starts a request. */
    fun onAiPromptChanged(state: VectorTuneupUiState, prompt: String): VectorTuneupUiState =
        state.copy(aiPrompt = prompt)

    /** Marks an AI request as started and clears any stale status/error. */
    fun startAi(state: VectorTuneupUiState): VectorTuneupUiState =
        state.copy(isAiRunning = true, aiStatusMessage = null, errorMessage = null)

    /**
     * Stages the AI-applied result as the single candidate, replacing any
     * existing one. The candidate carries the apply warnings plus the plan's
     * rejected operations (surfaced as warnings) and an operation report.
     */
    fun stageAiCandidate(
        state: VectorTuneupUiState,
        result: VectorTuneupAiResult,
    ): VectorTuneupUiState {
        val apply = result.applyResult
        val rejectedWarnings = result.plan.rejected.map { rejected ->
            VectorWarning(
                VectorWarning.Codes.AI_PLAN_SKIPPED_INVALID_OPERATION,
                "Rejected operation (${rejected.reason})",
            )
        }
        val candidate = VectorVersionUi(
            id = VectorVersionUi.ID_CANDIDATE,
            label = "AI Tune-Up",
            xml = apply.xml,
            metrics = apply.metrics,
            warnings = (apply.warnings + rejectedWarnings).distinct(),
            reportSummary = apply.summary,
        )
        val status = if (result.plan.isEmpty) AI_NO_CHANGES
        else "AI proposed ${result.plan.operations.size} operation(s)."
        return state.copy(
            candidate = candidate,
            isAiRunning = false,
            errorMessage = null,
            aiStatusMessage = status,
            lastAiSummary = result.plan.summary.ifBlank { null },
            selectedTab = VectorTuneupTab.COMPARE,
        )
    }

    /**
     * Records an AI failure with a friendly [message], preserving the original
     * and any existing candidate so nothing is lost.
     */
    fun aiFailed(state: VectorTuneupUiState, message: String): VectorTuneupUiState =
        state.copy(isAiRunning = false, aiStatusMessage = message)

    // ---- semantic redraw (Phase 5) ----

    /** Updates the redraw instruction text. Never starts a request. */
    fun onRedrawPromptChanged(state: VectorTuneupUiState, prompt: String): VectorTuneupUiState =
        state.copy(redrawPrompt = prompt)

    /** Marks a redraw request as started and clears any stale status/error. */
    fun startRedraw(state: VectorTuneupUiState): VectorTuneupUiState =
        state.copy(isRedrawRunning = true, redrawStatusMessage = null, errorMessage = null)

    /**
     * Stages the compiled redraw result as the single candidate, replacing any
     * existing one. The candidate carries the compiler warnings plus the scene's
     * rejected objects (surfaced as warnings) and a style-intent/object report.
     */
    fun stageRedrawCandidate(
        state: VectorTuneupUiState,
        result: VectorRedrawAiResult,
    ): VectorTuneupUiState {
        val compile = result.compileResult
        val scene = result.scene
        val rejectedWarnings = scene.rejected.map { rejected ->
            VectorWarning(
                VectorWarning.Codes.SCENE_OBJECT_REJECTED,
                "Rejected object (${rejected.reason})",
            )
        }
        val warnings = (compile.warnings + rejectedWarnings).distinct()
        val candidate = VectorVersionUi(
            id = VectorVersionUi.ID_CANDIDATE,
            label = "AI Redraw",
            xml = compile.xml,
            metrics = compile.metrics,
            warnings = warnings,
            reportSummary = redrawSummary(scene, compile, warnings.size),
        )
        val status = if (scene.objects.isEmpty()) {
            REDRAW_NO_OBJECTS
        } else {
            "AI proposed ${scene.objects.size} object(s)."
        }
        return state.copy(
            candidate = candidate,
            isRedrawRunning = false,
            errorMessage = null,
            redrawStatusMessage = status,
            lastRedrawSummary = scene.styleIntent.ifBlank { null },
            selectedTab = VectorTuneupTab.COMPARE,
        )
    }

    /**
     * Records a redraw failure with a friendly [message], preserving the
     * original and any existing candidate so nothing is lost.
     */
    fun redrawFailed(state: VectorTuneupUiState, message: String): VectorTuneupUiState =
        state.copy(isRedrawRunning = false, redrawStatusMessage = message)

    private fun redrawSummary(
        scene: VectorScene,
        compile: VectorSceneCompileResult,
        warningCount: Int,
    ): String = buildString {
        append(scene.styleIntent.ifBlank { "AI redraw" })
        append('\n')
        append("Objects: ${scene.objects.size} accepted, ${scene.rejected.size} rejected\n")
        append("Compiled paths: ${compile.metrics.pathCount}\n")
        append("Warnings: $warningCount")
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

        const val AI_NEED_KEY =
            "Add an API key in Settings before using AI Tune-Up."
        const val AI_NO_CHANGES = "No safe changes were proposed."

        const val REDRAW_NEED_KEY =
            "Add an API key in Settings before using AI Redraw."
        const val REDRAW_NO_OBJECTS = "No drawable objects were proposed."

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
