package com.aichat.sandbox.ui.screens.notes

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
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
    onNavigateToChat: (chatId: String) -> Unit = { },
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
    val availableModels by viewModel.availableModels.collectAsState()
    val chats by viewModel.chats.collectAsState()
    val sendToChatMode by viewModel.sendToChatMode.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var pdfDialogVisible by remember { mutableStateOf(false) }
    var viewportController by remember { mutableStateOf<ViewportController?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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
                        EditorOverflowMenu(
                            expanded = menuExpanded,
                            current = note.backgroundStyle,
                            onDismiss = { menuExpanded = false },
                            onBackgroundSelect = { style ->
                                viewModel.setBackgroundStyle(style)
                                menuExpanded = false
                            },
                            onSharePng = {
                                menuExpanded = false
                                scope.launch {
                                    val uri = viewModel.sharePng()
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share note as PNG")
                                    )
                                }
                            },
                            onSharePdf = {
                                menuExpanded = false
                                pdfDialogVisible = true
                            },
                            onSendToChat = {
                                menuExpanded = false
                                viewModel.openSendNoteToChatPicker()
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
                    .fillMaxWidth()
                    // Track the canvas size so "Insert as text box"
                    // (sub-phase 2.8) can anchor the new item at the
                    // current viewport centre when no selection is in
                    // scope. World coords are recovered via
                    // `ViewportController.screenToWorld(size/2)` at insert
                    // time, so panning between the reply landing and the
                    // tap is reflected correctly.
                    .onSizeChanged { canvasSize = it },
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
                    onAsk = viewModel::openAiSheetForSelection,
                    onConvertToText = viewModel::launchConvertSelectionToText,
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
        if (pdfDialogVisible) {
            ExportPdfDialog(
                boundsForPreview = remember(pdfDialogVisible) { viewModel.computeBoundsForExport() },
                onCancel = { pdfDialogVisible = false },
                onExport = { mode, pageSize ->
                    pdfDialogVisible = false
                    scope.launch {
                        val uri = viewModel.sharePdf(mode, pageSize)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Share note as PDF")
                        )
                    }
                },
            )
        }
        if (sendToChatMode != null) {
            SendToChatSheet(
                chats = chats,
                onPickExisting = { chatId ->
                    scope.launch {
                        val resolved = viewModel.finalizeSendToChat(chatId) ?: return@launch
                        onNavigateToChat(resolved)
                    }
                },
                onPickNewChat = {
                    scope.launch {
                        val resolved = viewModel.finalizeSendToChat(null) ?: return@launch
                        onNavigateToChat(resolved)
                    }
                },
                onDismiss = viewModel::dismissSendToChatPicker,
            )
        }
        AiSideSheet(
            state = aiSheetState,
            onInputChanged = viewModel::onAiInputChanged,
            onSubmit = viewModel::submitAiPrompt,
            onCancel = viewModel::cancelAiStreaming,
            onClose = viewModel::closeAiSheet,
            onCannedPrompt = viewModel::submitCannedPrompt,
            onClearScope = viewModel::clearAiSheetScope,
            onInsertConvertResult = viewModel::insertConvertResultAsTextBox,
            onInsertReply = { turnId ->
                // Compute the viewport-centre fallback in world coords at
                // tap time so panning between reply landing and the tap is
                // honoured. When the sheet has a selection scope the VM
                // ignores this and anchors at the selection bounds.
                val vp = viewportController
                val cx: Float
                val cy: Float
                if (vp != null && canvasSize != IntSize.Zero) {
                    cx = vp.screenToWorldX(canvasSize.width / 2f)
                    cy = vp.screenToWorldY(canvasSize.height / 2f)
                } else {
                    cx = 0f
                    cy = 0f
                }
                viewModel.insertReplyAsTextBox(turnId, cx, cy)
            },
            onSendReplyToChat = { turnId ->
                // Open the sub-phase 4.3 picker; navigation happens once the
                // user picks an existing chat or "+ New chat". The VM
                // already gates on Done / non-empty turns.
                viewModel.openSendReplyToChatPicker(turnId)
            },
            onModelSelected = viewModel::setAiModelId,
            availableModels = availableModels,
            customModels = emptyList(),
        )
      }
    }
}

/**
 * Editor TopAppBar overflow menu (sub-phase 4.1, PDF wired in 4.2).
 *
 * Combines the sub-phase 1.6 background-style picker with the Share section.
 * Both PNG and PDF entries fire callbacks; PDF first opens [ExportPdfDialog]
 * (handled by the parent) so the user can pick layout + page size before the
 * exporter runs.
 */
@Composable
private fun EditorOverflowMenu(
    expanded: Boolean,
    current: String,
    onDismiss: () -> Unit,
    onBackgroundSelect: (String) -> Unit,
    onSharePng: () -> Unit,
    onSharePdf: () -> Unit,
    onSendToChat: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = "Share",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DropdownMenuItem(
            text = { Text("Share as PNG") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                )
            },
            onClick = onSharePng,
        )
        DropdownMenuItem(
            text = { Text("Share as PDF") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                )
            },
            onClick = onSharePdf,
        )
        // Sub-phase 4.3: in-app picker over existing chats. PNG render +
        // OCR snippet handover is done via [PendingDraftStore]; the user
        // lands in the chosen chat with the attachment already in the
        // composer strip.
        DropdownMenuItem(
            text = { Text("Send to chat") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                )
            },
            onClick = onSendToChat,
        )
        HorizontalDivider()
        Text(
            text = "Background",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        backgroundChoices.forEach { (style, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onBackgroundSelect(style) },
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
