package com.aichat.sandbox.ui.screens.vector

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Drain one-shot events: export-ready share intents and transient messages.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VectorTuneupEvent.ExportReady -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/xml"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share optimized VectorDrawable")
                    )
                }
                is VectorTuneupEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Vector Tune-Up") },
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
            val tabs = VectorTuneupTab.entries
            TabRow(selectedTabIndex = tabs.indexOf(state.selectedTab)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tabLabel(tab)) },
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
                    VectorTuneupTab.DIAGNOSTICS -> DiagnosticsTab(state)
                    VectorTuneupTab.COMPARE -> CompareTab(state, viewModel)
                    VectorTuneupTab.HISTORY -> HistoryTab(state, viewModel)
                    VectorTuneupTab.EXPORT -> ExportTab(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun InputTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    VectorXmlInputPanel(
        inputXml = state.inputXml,
        onXmlChanged = viewModel::onXmlChanged,
        onParse = viewModel::parseInput,
        onPasteSample = viewModel::pasteSample,
        onClear = { viewModel.onXmlChanged("") },
    )
}

@Composable
private fun DiagnosticsTab(state: VectorTuneupUiState) {
    val original = state.original
    if (original == null) {
        Text(
            text = "Parse a vector on the Input tab to see diagnostics.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    SectionTitle("Metrics")
    VectorMetricsPanel(original.metrics)
    SectionTitle("Warnings")
    VectorWarningList(original.warnings)
    PreviewPlaceholder()
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
    SectionTitle("Compare")
    VectorVersionComparePanel(
        original = state.original,
        candidate = state.candidate,
    )
}

@Composable
private fun HistoryTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    val projects by viewModel.projects.collectAsState()

    SectionTitle("Saved projects")
    if (projects.isEmpty()) {
        Text(
            text = "No saved projects yet. Run an operation or tap \"Save project\" to create one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun ExportTab(state: VectorTuneupUiState, viewModel: VectorTuneupViewModel) {
    val exportVersion = state.selectedVersion ?: state.activeVersion ?: state.candidate ?: state.original
    if (exportVersion == null) {
        Text(
            text = "Parse or save a vector first. You can then export any version from " +
                "here or from the History tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    } else {
        Text(
            text = "Will export: ${exportVersion.label} · ${exportVersion.metrics.xmlBytes} bytes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    Button(
        onClick = { viewModel.exportCandidate() },
        enabled = exportVersion != null,
    ) {
        Text("Export selected version")
    }
}

@Composable
private fun PreviewPlaceholder() {
    SectionTitle("Preview")
    Text(
        text = "Preview rendering coming in a later phase.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

private fun tabLabel(tab: VectorTuneupTab): String = when (tab) {
    VectorTuneupTab.INPUT -> "Input"
    VectorTuneupTab.DIAGNOSTICS -> "Diagnostics"
    VectorTuneupTab.COMPARE -> "Compare"
    VectorTuneupTab.HISTORY -> "History"
    VectorTuneupTab.EXPORT -> "Export"
}
