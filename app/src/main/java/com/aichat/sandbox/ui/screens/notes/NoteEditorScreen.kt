package com.aichat.sandbox.ui.screens.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.DrawingSurfaceView
import com.aichat.sandbox.ui.components.notes.TextItemEditor
import com.aichat.sandbox.ui.components.notes.Tool
import com.aichat.sandbox.ui.components.notes.ToolPalette
import com.aichat.sandbox.ui.components.notes.ViewportController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: NoteEditorViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val ocrIndicator by viewModel.ocrIndicator.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionWorldBounds by viewModel.selectionWorldBounds.collectAsState()
    val selectionMatrix by viewModel.selectionMatrix.collectAsState()
    val textEditTarget by viewModel.textEditTarget.collectAsState()
    val aiSheetState by viewModel.aiSheetState.collectAsState()
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var viewportController by remember { mutableStateOf<ViewportController?>(null) }

    fun saveAndExit() {
        scope.launch {
            viewModel.commitTextEdit()
            viewModel.save()
            onNavigateBack()
        }
    }

    BackHandler(onBack = ::saveAndExit)

    // Switching away from the TEXT tool commits any in-flight edit so the
    // user doesn't see a stale editor floating over their ink stroke. The
    // snapshotFlow translates `palette.selected` (Compose state) into a flow
    // we can observe without redrawing the whole screen on every keystroke.
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.palette.selected }.collectLatest { tool ->
            if (tool != Tool.TEXT) viewModel.commitTextEdit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::saveAndExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    OutlinedTextField(
                        value = note.title,
                        onValueChange = viewModel::setTitle,
                        placeholder = { Text("Untitled") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    OcrIndicatorBadge(state = ocrIndicator)
                    IconButton(onClick = { viewModel.openAiSheet(selection = null) }) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "Ask about this note",
                        )
                    }
                    IconButton(onClick = viewModel::undo, enabled = canUndo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                        )
                    }
                    IconButton(onClick = viewModel::redo, enabled = canRedo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More",
                            )
                        }
                        BackgroundStyleMenu(
                            expanded = menuExpanded,
                            current = note.backgroundStyle,
                            onDismiss = { menuExpanded = false },
                            onSelect = { style ->
                                viewModel.setBackgroundStyle(style)
                                menuExpanded = false
                            },
                        )
                    }
                },
            )
        }
    ) { padding ->
      Box(
          modifier = Modifier
              .fillMaxSize()
              .padding(padding),
      ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                DrawingSurfaceView(
                    items = viewModel.items,
                    backgroundStyle = note.backgroundStyle,
                    paletteState = viewModel.palette,
                    selectedIds = selection,
                    selectionMatrix = selectionMatrix,
                    editingTextId = (textEditTarget as? TextEditTarget.Existing)?.itemId,
                    onStrokeCommitted = viewModel::addItem,
                    onItemsErased = viewModel::removeItems,
                    onLassoCompleted = viewModel::onLassoCompleted,
                    onSelectionShouldClear = viewModel::clearSelection,
                    onTextTap = viewModel::onTextToolTap,
                    modifier = Modifier.fillMaxSize(),
                    onViewportReady = { viewportController = it },
                )
                SelectionOverlay(
                    selection = selection,
                    worldBounds = selectionWorldBounds,
                    viewport = viewportController,
                    liveMatrix = selectionMatrix,
                    onTransformChanged = viewModel::updateSelectionTransform,
                    onTransformCommitted = viewModel::bakeSelectionTransform,
                    onDuplicate = viewModel::duplicateSelection,
                    onDelete = viewModel::deleteSelection,
                    onCut = viewModel::cutSelection,
                    onCopy = viewModel::copySelection,
                    onPaste = viewModel::pasteFromClipboard,
                    canPaste = viewModel.hasClipboardContent(),
                )
                val target = textEditTarget
                val vp = viewportController
                if (target != null && vp != null) {
                    TextItemEditor(
                        initialBody = target.initialBody,
                        screenOriginX = vp.worldToScreenX(target.worldX),
                        screenOriginY = vp.worldToScreenY(target.worldY),
                        fontSizePx = target.fontSize * vp.scale,
                        alignment = target.alignment,
                        // Match the StaticLayout wrap width used by
                        // [TextItemRenderer] so the editor and the final
                        // rendered text break at the same column.
                        maxWidthPx = com.aichat.sandbox.ui.components.notes.TextItemCodec
                            .DEFAULT_MAX_WIDTH_WORLD * vp.scale,
                        onBodyChanged = viewModel::onTextEditBodyChanged,
                        onCommit = viewModel::commitTextEdit,
                    )
                }
            }
            ToolPalette(state = viewModel.palette)
        }
        AiSideSheet(
            state = aiSheetState,
            onInputChanged = viewModel::onAiInputChanged,
            onSubmit = viewModel::submitAiPrompt,
            onCancel = viewModel::cancelAiStreaming,
            onClose = viewModel::closeAiSheet,
        )
      }
    }
}

@Composable
private fun BackgroundStyleMenu(
    expanded: Boolean,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = "Background",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        backgroundChoices.forEach { (style, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onSelect(style) },
                trailingIcon = {
                    if (style == current) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                        )
                    }
                },
            )
        }
    }
}

private val backgroundChoices = listOf(
    BackgroundLayer.STYLE_PLAIN to "Plain",
    BackgroundLayer.STYLE_DOT to "Dot grid",
    BackgroundLayer.STYLE_LINE to "Lines",
    BackgroundLayer.STYLE_GRAPH to "Graph",
)

/**
 * TopAppBar indicator for the sub-phase 2.4 OCR pipeline. Renders an
 * indeterminate spinner while the ML Kit model is downloading or recognition
 * is running for the active note; otherwise occupies no slot at all so the
 * undo/redo group sits flush against the title.
 */
@Composable
private fun OcrIndicatorBadge(state: OcrIndicator) {
    if (state == OcrIndicator.Idle) return
    val description = when (state) {
        OcrIndicator.Downloading -> "Downloading handwriting model"
        OcrIndicator.Running -> "Transcribing handwriting"
        OcrIndicator.Idle -> return
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .semantics { contentDescription = description },
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
