package com.aichat.sandbox.ui.screens.vector

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Navigation route for the Vector Art Tune-Up workspace. */
const val ROUTE_VECTOR_TUNEUP = "vector-tuneup"

/**
 * The Vector Art Tune-Up workspace (Phase 3).
 *
 * End-to-end local workflow over the deterministic Phase 1/2 vector foundation:
 * paste/parse VectorDrawable XML → inspect metrics and warnings → tune optimizer
 * options → run optimization → compare original vs candidate → export the
 * optimized XML. No AI, no model calls, no persistent storage.
 *
 * Raster preview is intentionally deferred (inflating arbitrary VectorDrawable
 * XML at runtime is risky for untrusted input), so the compare panel surfaces
 * read-only XML inspection and before/after metrics instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VectorTuneupScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: VectorTuneupViewModel = hiltViewModel(),
) {
    com.aichat.sandbox.ui.theme.studio.StudioTheme(
        dark = androidx.compose.foundation.isSystemInDarkTheme(),
    ) {
        VectorTuneupScreenContent(onNavigateBack = onNavigateBack, viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VectorTuneupScreenContent(
    onNavigateBack: () -> Unit,
    viewModel: VectorTuneupViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Drain one-shot events: export-ready share intents and transient messages.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VectorTuneupEvent.ExportReady -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share vector export")
                    )
                }
                is VectorTuneupEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Keep the Edit/Compare analysis (catalog, scores, diff) in sync with the
    // currently selected source version.
    LaunchedEffect(state.selectedTab, state.sourceVersion?.id, state.versions.size) {
        if (state.selectedTab == VectorTuneupTab.EDIT || state.selectedTab == VectorTuneupTab.COMPARE) {
            if (state.sourceVersion != null) viewModel.refreshSelectedVersionAnalysis()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        ),
        containerColor = com.aichat.sandbox.ui.theme.studio.LocalStudioColors.current.canvasBase,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = com.aichat.sandbox.ui.theme.studio.LocalStudioColors.current.canvasBase,
                    titleContentColor = com.aichat.sandbox.ui.theme.studio.LocalStudioColors.current.inkStrong,
                ),
                title = {
                    Text(
                        "Vector Tune-Up",
                        style = com.aichat.sandbox.ui.theme.studio.LocalStudioTypography.current.title,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.reset() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                    }
                    IconButton(
                        onClick = { viewModel.exportCandidate() },
                        enabled = state.hasCandidate || state.hasOriginal,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Export")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            VectorProjectHeader(
                title = state.projectTitle,
                isSaved = state.isSaved,
                activeVersionLabel = state.activeVersion?.label,
                onSave = { viewModel.createProjectFromCurrentInput() },
                onRename = viewModel::renameProject,
                onDelete = { viewModel.deleteCurrentProject() },
            )
            // Studio Bench: the six tabs read as a numbered pipeline / stage
            // track (Input → Diagnostics → … → Export) rather than a flat tab
            // strip, reinforcing that Tune-Up is a left-to-right workflow.
            val tabs = VectorTuneupTab.entries
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(state.selectedTab),
                edgePadding = 8.dp,
                containerColor = com.aichat.sandbox.ui.theme.studio.LocalStudioColors.current.canvasBase,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = "${index + 1} · ${tabLabel(tab)}",
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (state.selectedTab) {
                    VectorTuneupTab.INPUT -> InputTab(state, viewModel)
                    VectorTuneupTab.DIAGNOSTICS -> DiagnosticsTab(state, viewModel)
                    VectorTuneupTab.COMPARE -> CompareTab(state, viewModel)
                    VectorTuneupTab.EDIT -> EditTab(state, viewModel)
                    VectorTuneupTab.HISTORY -> HistoryTab(state, viewModel)
                    VectorTuneupTab.EXPORT -> ExportTab(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun InputTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    VectorTuneupHelpPanel()
    SectionTitle("Import from a file")
    VectorFileImportPanel(
        buttonLabel = "Import file",
        helpText = "Import an Android VectorDrawable .xml, an .svg, or a project bundle .json. " +
            "Bundles open as a new project; XML/SVG land in the field below.",
        mimeTypes = VECTOR_IMPORT_MIME_TYPES,
        onFilePicked = { uri -> viewModel.importVectorFileFromUri(uri) },
    )
    state.fileImportStatusMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
    SectionTitle("Paste")
    VectorXmlInputPanel(
        inputXml = state.inputXml,
        detectedFormat = state.detectedImportFormat,
        onXmlChanged = viewModel::onXmlChanged,
        onParse = viewModel::parseInput,
        onPasteSample = viewModel::pasteSample,
        onClear = { viewModel.onXmlChanged("") },
    )
}

@Composable
private fun DiagnosticsTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    val original = state.original
    if (original == null) {
        StudioEmptyHint(
            marker = "Awaiting input",
            body = "Parse a vector on the Input stage to read its diagnostics.",
        )
        return
    }
    Text(
        text = "Import format: ${importFormatLabel(state.detectedImportFormat)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    SectionTitle("Health")
    VectorProjectHealthPanel(
        health = com.aichat.sandbox.data.vector.VectorTuneupAudit.assessMetrics(original.metrics),
        allowExpensive = state.allowExpensiveOnLargeInput,
        onAllowExpensiveChange = viewModel::setAllowExpensiveOnLargeInput,
    )
    SectionTitle("Preview")
    VectorPreviewPanel(title = "Original (parsed)", version = original)
    SectionTitle("Metrics")
    VectorMetricsPanel(original.metrics)
    SectionTitle("Warnings")
    VectorWarningList(original.warnings)
}

private fun importFormatLabel(format: com.aichat.sandbox.data.vector.VectorImportFormat): String =
    when (format) {
        com.aichat.sandbox.data.vector.VectorImportFormat.ANDROID_VECTOR -> "Android VectorDrawable"
        com.aichat.sandbox.data.vector.VectorImportFormat.SVG -> "SVG (converted to Android XML)"
        com.aichat.sandbox.data.vector.VectorImportFormat.PROJECT_BUNDLE -> "Project bundle JSON"
        com.aichat.sandbox.data.vector.VectorImportFormat.UNKNOWN -> "Unknown"
    }

@Composable
private fun CompareTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    SectionTitle("Local Optimize")
    VectorOptimizeControls(
        options = state.options,
        onOptionsChange = viewModel::updateOptions,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { viewModel.optimize() },
            enabled = !state.isBusy && (state.hasOriginal || state.inputXml.isNotBlank()),
        ) {
            Text("Optimize")
        }
        OutlinedButton(
            onClick = { viewModel.clearCandidate() },
            enabled = state.hasCandidate,
        ) {
            Text("Clear candidate")
        }
        TextButton(
            onClick = { viewModel.exportCandidate() },
            enabled = state.hasCandidate,
        ) {
            Text("Export candidate")
        }
        if (state.isOptimizing) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
        }
    }
    if (state.expensiveAiBlocked) {
        Text(
            text = if (state.isInputUnsafe) {
                "AI is disabled for this large vector. Optimize it locally first or import a smaller file."
            } else {
                "This vector is large. Enable \"Run AI on large input\" in Diagnostics before using AI."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
    SectionTitle("AI Tune-Up")
    VectorAiTuneupPanel(
        state = state,
        onPromptChange = viewModel::onAiPromptChanged,
        onRun = viewModel::runAiTuneup,
        onCancel = viewModel::cancelAiTuneup,
    )
    SectionTitle("AI Redraw")
    VectorSemanticRedrawPanel(
        state = state,
        onPromptChange = viewModel::onRedrawPromptChanged,
        onRun = viewModel::runSemanticRedraw,
        onCancel = viewModel::cancelSemanticRedraw,
    )
    SectionTitle("Visual diff")
    VectorVisualDiffPanel(
        original = state.original,
        candidate = state.candidate,
        mode = state.visualDiffMode,
        onModeChange = viewModel::setVisualDiffMode,
        diff = state.selectedDiff,
    )

    SectionTitle("Compare")
    VectorVersionComparePanel(
        original = state.original,
        candidate = state.candidate,
    )

    SectionTitle("Quality")
    VectorQualityPanel(scores = state.qualityScores)
    SectionTitle("Diff (original → selected)")
    VectorDiffPanel(diff = state.selectedDiff)
}

@Composable
private fun EditTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    if (!state.hasOriginal) {
        StudioEmptyHint(
            marker = "Nothing on the bench",
            body = "Parse or open a vector first, then select any version to edit it.",
        )
        return
    }

    state.manualEditStatusMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
    }

    SectionTitle("Preview")
    VectorPreviewPanel(
        title = state.sourceVersion?.label ?: "Selected version",
        version = state.sourceVersion,
        highlightPathIds = state.selectedPathIds,
    )

    SectionTitle("Quality")
    VectorQualityPanel(scores = state.qualityScores)

    SectionTitle("Paths")
    VectorPathInspectorPanel(
        entries = state.pathCatalog,
        selectedPathIds = state.selectedPathIds,
        onToggle = viewModel::togglePathSelection,
        onClearSelection = viewModel::clearPathSelection,
    )

    SectionTitle("Edit selected")
    VectorPathEditPanel(
        selectedCount = state.selectedPathIds.size,
        enabled = !state.isBusy && state.selectedPathIds.isNotEmpty(),
        onDelete = viewModel::deleteSelectedPaths,
        onSimplify = { tolerance, simplifyFills -> viewModel.simplifySelectedPaths(tolerance, simplifyFills) },
        onRecolor = { stroke, fill -> viewModel.recolorSelectedPaths(stroke, fill) },
        onRestyle = { width, cap, join -> viewModel.restyleSelectedPaths(width, cap, join) },
    )

    SectionTitle("Batch restyle")
    VectorBatchRestylePanel(
        availableColors = state.pathCatalog
            .flatMap { listOfNotNull(it.fillColor, it.strokeColor) }
            .distinct(),
        enabled = !state.isBusy,
        onApply = viewModel::applyBatchRestyle,
    )
}

@Composable
private fun HistoryTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    val projects by viewModel.projects.collectAsState()

    Text(
        text = "Select any version, then use Compare or Edit to branch from it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    val previewVersion = state.selectedVersion ?: state.activeVersion ?: state.candidate ?: state.original
    if (previewVersion != null) {
        SectionTitle("Preview selected")
        VectorPreviewPanel(title = previewVersion.label, version = previewVersion)
    }

    SectionTitle("Saved projects")
    if (projects.isEmpty()) {
        StudioEmptyHint(
            marker = "No saved projects",
            body = "Run an operation or tap \"Save project\" to keep a versioned project here.",
        )
    } else {
        projects.forEach { project ->
            val isOpen = project.id == state.projectId
            Surface(
                color = if (isOpen) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clickable { viewModel.openProject(project.id) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(project.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (isOpen) "Open" else "Tap to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    SectionTitle("Version history")
    VectorVersionHistoryPanel(
        versions = state.versions,
        activeVersionId = state.activeVersionId,
        selectedVersionId = state.selectedVersionId,
        onSelect = viewModel::selectVersion,
        onMakeActive = viewModel::setActiveVersion,
        onExport = viewModel::exportVersion,
    )

    SectionTitle("Selected version actions")
    VectorVersionGraphActionsPanel(
        selected = state.selectedVersion,
        enabled = !state.isBusy && state.isSaved,
        onDuplicate = viewModel::duplicateSelectedVersion,
        onDelete = viewModel::deleteSelectedVersion,
    )

    SectionTitle("Import project bundle")
    VectorFileImportPanel(
        buttonLabel = "Import bundle file",
        helpText = "Import a Vector Tune-Up project bundle .json file as a new project.",
        mimeTypes = BUNDLE_IMPORT_MIME_TYPES,
        onFilePicked = { uri -> viewModel.importBundleFileFromUri(uri) },
        modifier = Modifier.padding(bottom = 8.dp),
    )
    VectorBundleImportPanel(
        bundleText = state.bundleImportText,
        statusMessage = state.bundleImportStatusMessage,
        isImporting = state.isImportingBundle,
        onTextChanged = viewModel::onBundleImportTextChanged,
        onImport = viewModel::importBundleFromText,
    )
}

@Composable
private fun ExportTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    val exportVersion = state.selectedVersion ?: state.activeVersion ?: state.candidate ?: state.original
    if (exportVersion == null) {
        StudioEmptyHint(
            marker = "Nothing to export",
            body = "Parse or save a vector first. You can then export any version from " +
                "here or from the History stage.",
        )
        Spacer(modifier = Modifier.height(12.dp))
    } else {
        Text(
            text = "Will export: ${exportVersion.label} · ${exportVersion.metrics.xmlBytes} bytes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    SectionTitle("Format")
    VectorExportOptionsPanel(
        selected = state.exportFormat,
        onSelect = viewModel::setExportFormat,
    )
    Text(
        text = "Project bundle JSON can be imported back into Vector Tune-Up from the History tab.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Button(
        onClick = { viewModel.exportSelectedVersion() },
        enabled = exportVersion != null,
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text(exportButtonLabel(state.exportFormat))
    }
}

private fun exportButtonLabel(format: com.aichat.sandbox.data.vector.VectorExportFormat): String =
    when (format) {
        com.aichat.sandbox.data.vector.VectorExportFormat.ANDROID_VECTOR_XML ->
            "Export selected version as Android XML"
        com.aichat.sandbox.data.vector.VectorExportFormat.SVG ->
            "Export selected version as SVG"
        com.aichat.sandbox.data.vector.VectorExportFormat.PROJECT_BUNDLE ->
            "Export project bundle JSON"
    }

@Composable
private fun SectionTitle(title: String) {
    // Studio Bench: all-caps, wide-tracked stage marker instead of a generic
    // titleMedium header — the instrument voice of the identity.
    com.aichat.sandbox.ui.components.studio.StudioSectionMarker(label = title)
}

/**
 * Studio Bench empty/placeholder hint. A small-caps instrument marker over a
 * muted instruction line — replaces the ad-hoc `Text(bodyMedium)` placeholders
 * so the workspace's empty states speak the same voice as the rest of the
 * identity.
 */
@Composable
private fun StudioEmptyHint(marker: String, body: String) {
    val colors = com.aichat.sandbox.ui.theme.studio.LocalStudioColors.current
    val type = com.aichat.sandbox.ui.theme.studio.LocalStudioTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = marker.uppercase(), style = type.section, color = colors.inkMuted)
        Text(text = body, style = type.body, color = colors.inkDefault)
    }
}

private fun tabLabel(tab: VectorTuneupTab): String = when (tab) {
    VectorTuneupTab.INPUT -> "Input"
    VectorTuneupTab.DIAGNOSTICS -> "Diagnostics"
    VectorTuneupTab.COMPARE -> "Compare"
    VectorTuneupTab.EDIT -> "Edit"
    VectorTuneupTab.HISTORY -> "History"
    VectorTuneupTab.EXPORT -> "Export"
}
