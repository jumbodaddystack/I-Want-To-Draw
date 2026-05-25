package com.aichat.sandbox.ui.screens.notes

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
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
import com.aichat.sandbox.ui.components.notes.AudioPlaybackBar
import com.aichat.sandbox.ui.components.notes.AudioRecordingBar
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.BrushSheet
import com.aichat.sandbox.ui.components.notes.ColorPickerSheet
import com.aichat.sandbox.ui.components.notes.DrawingSurfaceView
import com.aichat.sandbox.ui.components.notes.FavoritesBar
import com.aichat.sandbox.ui.components.notes.FrameOverlay
import com.aichat.sandbox.ui.components.notes.SaveStampDialog
import com.aichat.sandbox.ui.components.notes.StampDrawer
import com.aichat.sandbox.ui.components.notes.TextItemEditor
import com.aichat.sandbox.ui.components.notes.Tool
import com.aichat.sandbox.ui.components.notes.ToolPalette
import com.aichat.sandbox.ui.components.notes.ViewportController
import com.aichat.sandbox.ui.components.notes.ZoomChrome
import com.aichat.sandbox.ui.screens.notebooks.PageThumbnailRail
import com.aichat.sandbox.data.notes.FrameThumbnailRenderer
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
    val colorPickerOpen by viewModel.colorPickerOpen.collectAsState()
    val recentColors by viewModel.recentColors.collectAsState()
    val snapMask by viewModel.snapMask.collectAsState()
    val layers by viewModel.layers.collectAsState()
    val activeLayerId by viewModel.activeLayerId.collectAsState()
    val layersPanelOpen by viewModel.layersPanelOpen.collectAsState()
    val brushPresets by viewModel.brushPresetList.collectAsState()
    val activeBrushPreset by viewModel.activeBrushPreset.collectAsState()
    val pendingEdit by viewModel.pendingEdit.collectAsState()
    val frames by viewModel.frames.collectAsState()
    val currentFrameId by viewModel.currentFrameId.collectAsState()
    val frameNavigatorOpen by viewModel.frameNavigatorOpen.collectAsState()
    val stamps by viewModel.stamps.collectAsState()
    val stampDrawerOpen by viewModel.stampDrawerOpen.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val notebook by viewModel.notebook.collectAsState()
    val pageRailOpen by viewModel.pageRailOpen.collectAsState()
    val audioClips by viewModel.audioClips.collectAsState()
    val isRecordingAudio by viewModel.isRecordingAudio.collectAsState()
    val recordingStartedAt by viewModel.recordingStartedAt.collectAsState()
    val audioPosition by viewModel.audioPlayer.positionMs.collectAsState()
    val audioPlaybackState by viewModel.audioPlayer.state.collectAsState()
    val audioActiveClip by viewModel.audioPlayer.activeClip.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var pdfDialogVisible by remember { mutableStateOf(false) }
    var vectorXmlDialogVisible by remember { mutableStateOf(false) }
    var brushSheetOpen by remember { mutableStateOf(false) }
    var saveStampDialogVisible by remember { mutableStateOf(false) }
    var viewportController by remember { mutableStateOf<ViewportController?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Collapsible bottom-palette state. When false, the favorites + brush
    // sheet + tool palette rows hide behind a thin handle; the canvas above
    // expands to reclaim ~150dp of vertical space for drawing/writing.
    var palettesExpanded by remember { mutableStateOf(true) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val vp = viewportController
        val cx: Float
        val cy: Float
        if (vp != null && canvasSize != IntSize.Zero) {
            cx = vp.screenToWorldX(canvasSize.width / 2f)
            cy = vp.screenToWorldY(canvasSize.height / 2f)
        } else {
            cx = 0f; cy = 0f
        }
        viewModel.insertImageFromUri(uri, cx, cy)
    }

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
        // We handle the navigation-bar inset ourselves on the bottom palette
        // Surface so its tonal background bleeds to the screen edge instead
        // of stopping above the system nav bar (which used to leave a visible
        // empty strip below the width slider).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                    // Sub-phase 8.2 — frame navigator toggle.
                    // In notebook mode we surface a Pages rail instead so
                    // the canvas only carries one strip at a time.
                    if (notebook != null) {
                        IconButton(onClick = viewModel::togglePageRail) {
                            Icon(
                                imageVector = Icons.Filled.Dashboard,
                                contentDescription = "Pages",
                            )
                        }
                    } else {
                        IconButton(onClick = viewModel::toggleFrameNavigator) {
                            Icon(
                                imageVector = Icons.Filled.Dashboard,
                                contentDescription = "Frames",
                            )
                        }
                    }
                    // Sub-phase 8.3 — stamp drawer.
                    IconButton(onClick = viewModel::openStampDrawer) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = "Stamps",
                        )
                    }
                    // Sub-phase 6.4 — layers panel toggle.
                    IconButton(onClick = viewModel::toggleLayersPanel) {
                        Icon(
                            imageVector = Icons.Filled.Layers,
                            contentDescription = "Layers",
                        )
                    }
                    // Phase 5.4 — zoom chip + Fit / 100% / Center popover.
                    // Bounds are recomputed lazily on each menu open so the
                    // user doesn't pay for a scan on every TopAppBar
                    // recomposition.
                    ZoomChrome(
                        viewport = viewportController,
                        canvasSize = canvasSize,
                        contentBoundsProvider = {
                            val bounds = viewModel.computeBoundsForExport()
                            // The exporter returns a default A4-ish rect for
                            // empty notes; fit-on-empty would be visually
                            // confusing, so collapse those to null.
                            if (viewModel.items.isEmpty()) null else bounds
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
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
                            hasActiveFrame = currentFrameId != null,
                            onDismiss = { menuExpanded = false },
                            onBackgroundSelect = { style ->
                                viewModel.setBackgroundStyle(style)
                                menuExpanded = false
                            },
                            onInsertImage = {
                                menuExpanded = false
                                imagePicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            onToggleBrushSheet = {
                                menuExpanded = false
                                brushSheetOpen = !brushSheetOpen
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
                            onShareSvg = {
                                menuExpanded = false
                                scope.launch {
                                    val uri = viewModel.shareSvg()
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/svg+xml"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share note as SVG")
                                    )
                                }
                            },
                            onExportVectorXml = {
                                menuExpanded = false
                                vectorXmlDialogVisible = true
                            },
                            onSendToChat = {
                                menuExpanded = false
                                viewModel.openSendNoteToChatPicker()
                            },
                            onExportFramePng = {
                                menuExpanded = false
                                scope.launch {
                                    val uri = viewModel.sharePngForCurrentFrame() ?: return@launch
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share frame as PNG")
                                    )
                                }
                            },
                            onExportFrameSvg = {
                                menuExpanded = false
                                scope.launch {
                                    val uri = viewModel.shareSvgForCurrentFrame() ?: return@launch
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/svg+xml"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share frame as SVG")
                                    )
                                }
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
              // Only consume the TopAppBar inset here; the navigation-bar
              // inset is applied inside the bottom palette so its Surface
              // background extends to the screen edge.
              .padding(top = padding.calculateTopPadding()),
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
                    snapMask = snapMask,
                    layers = layers,
                    activeTextureId = activeBrushPreset?.textureId,
                    onViewportReady = { viewportController = it },
                    onFrameDrawn = viewModel::onFrameDrawn,
                    onFrameTap = viewModel::onFrameTap,
                    recordingStartedAt = recordingStartedAt,
                )
                // Sub-phase 8.1 — frame rectangles + name labels rendered
                // above the canvas.
                FrameOverlay(
                    frames = frames,
                    currentFrameId = currentFrameId,
                    viewport = viewportController,
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
                    onCannedEdit = { action ->
                        viewModel.applyCannedEditAction(action)
                    },
                    onSaveAsStamp = { saveStampDialogVisible = true },
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
            // Sub-phase 9.4 — audio recordings strip (playback + scrubber).
            // Hidden until at least one clip exists; the record button below
            // surfaces independent of clip count.
            AudioPlaybackBar(
                clips = audioClips,
                activeClipPath = audioActiveClip,
                playbackState = audioPlaybackState,
                positionMs = audioPosition,
                onPlay = viewModel::playAudioClip,
                onPause = viewModel::pauseAudio,
                onResume = viewModel::resumeAudio,
                onSeek = viewModel::seekAudio,
                onDelete = viewModel::deleteAudioClip,
            )
            // Bottom palette wrapper: handle + collapsible tool rows. The
            // outer Surface bleeds its background through the navigation-bar
            // inset; the inner Column applies that inset to its content so
            // controls stay above the system nav bar.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    PaletteCollapseHandle(
                        expanded = palettesExpanded,
                        onToggle = { palettesExpanded = !palettesExpanded },
                    )
                    AnimatedVisibility(visible = palettesExpanded) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Sub-phase 8.4 — favorites bar above the tool palette.
                            FavoritesBar(
                                slots = favorites,
                                presets = brushPresets,
                                activePresetId = activeBrushPreset?.id,
                                onApply = viewModel::applyFavorite,
                                onAssignActive = viewModel::assignFavoriteFromActiveBrush,
                                onClear = viewModel::clearFavoriteSlot,
                            )
                            if (brushSheetOpen) {
                                BrushSheet(
                                    presets = brushPresets,
                                    activePreset = activeBrushPreset,
                                    onApplyPreset = viewModel::applyBrushPreset,
                                    onLiveEdit = viewModel::setLiveBrushEdit,
                                    onSaveAsPreset = { _, name -> viewModel.saveActiveAsUserPreset(name) },
                                    onTextureChange = viewModel::setActiveTextureId,
                                )
                            }
                            ToolPalette(
                                state = viewModel.palette,
                                onPickCustomColor = viewModel::openColorPicker,
                                snapMask = snapMask,
                                onToggleSnap = viewModel::toggleSnap,
                            )
                        }
                    }
                }
            }
        }
        // Sub-phase 9.2 — left-edge page rail when in notebook mode.
        if (pageRailOpen && notebook != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.CenterStart,
            ) {
                PageThumbnailRail(
                    frames = frames,
                    currentFrameId = currentFrameId,
                    items = viewModel.items,
                    thumbnailRenderer = viewModel.frameThumbnailRenderer,
                    onSelect = { frame ->
                        viewModel.selectFrame(frame.id)
                        val vp = viewportController
                        if (vp != null && canvasSize != IntSize.Zero) {
                            vp.flyTo(
                                bounds = frame.bounds(),
                                canvasSize = floatArrayOf(
                                    canvasSize.width.toFloat(),
                                    canvasSize.height.toFloat(),
                                ),
                            )
                        }
                    },
                    onAddPage = viewModel::addNotebookPage,
                    onClose = viewModel::closePageRail,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        // Sub-phase 9.4 — record button anchored above the AI side sheet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.BottomEnd,
        ) {
            AudioRecordingBar(
                isRecording = isRecordingAudio,
                onStart = viewModel::startAudioRecording,
                onStop = viewModel::stopAudioRecording,
                modifier = Modifier.padding(end = 12.dp, bottom = 160.dp),
            )
        }
        // Sub-phase 8.2 — left-edge frame navigator.
        if (frameNavigatorOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.CenterStart,
            ) {
                FrameNavigator(
                    frames = frames,
                    currentFrameId = currentFrameId,
                    items = viewModel.items,
                    thumbnailRenderer = viewModel.frameThumbnailRenderer,
                    onSelect = { frame ->
                        viewModel.selectFrame(frame.id)
                        val vp = viewportController
                        if (vp != null && canvasSize != IntSize.Zero) {
                            vp.flyTo(
                                bounds = frame.bounds(),
                                canvasSize = floatArrayOf(
                                    canvasSize.width.toFloat(),
                                    canvasSize.height.toFloat(),
                                ),
                            )
                        }
                    },
                    onRename = viewModel::renameFrame,
                    onDelete = viewModel::deleteFrame,
                    onClose = viewModel::closeFrameNavigator,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (layersPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.CenterEnd,
            ) {
                LayersPanel(
                    layers = layers,
                    activeLayerId = activeLayerId,
                    onAddLayer = viewModel::addLayer,
                    onSelectLayer = viewModel::selectLayer,
                    onToggleVisible = viewModel::toggleLayerVisible,
                    onToggleLocked = viewModel::toggleLayerLocked,
                    onOpacityChange = viewModel::setLayerOpacity,
                    onMoveUp = viewModel::moveLayerUp,
                    onMoveDown = viewModel::moveLayerDown,
                    onDelete = viewModel::deleteLayer,
                    onClose = viewModel::closeLayersPanel,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
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
        if (vectorXmlDialogVisible) {
            ExportVectorXmlDialog(
                onCancel = { vectorXmlDialogVisible = false },
                onExport = { sizeDp ->
                    vectorXmlDialogVisible = false
                    scope.launch {
                        val result = viewModel.shareVectorXml(sizeDp)
                        if (result.skippedCount > 0) {
                            Toast.makeText(
                                context,
                                "${result.skippedCount} text/image item(s) skipped — " +
                                    "not supported by vector drawables",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/xml"
                            putExtra(Intent.EXTRA_STREAM, result.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Share icon as XML")
                        )
                    }
                },
            )
        }
        // Sub-phase 8.3 — bottom-aligned stamp drawer.
        if (stampDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.BottomCenter,
            ) {
                StampDrawer(
                    stamps = stamps,
                    onInsert = { stampId ->
                        val vp = viewportController
                        val cx: Float
                        val cy: Float
                        if (vp != null && canvasSize != IntSize.Zero) {
                            cx = vp.screenToWorldX(canvasSize.width / 2f)
                            cy = vp.screenToWorldY(canvasSize.height / 2f)
                        } else {
                            cx = 0f; cy = 0f
                        }
                        viewModel.insertStamp(stampId, cx, cy)
                    },
                    onRename = viewModel::renameStamp,
                    onDelete = viewModel::deleteStamp,
                    onDismiss = viewModel::closeStampDrawer,
                )
            }
        }
        if (saveStampDialogVisible) {
            SaveStampDialog(
                onConfirm = { name ->
                    viewModel.saveSelectionAsStamp(name)
                    saveStampDialogVisible = false
                },
                onDismiss = { saveStampDialogVisible = false },
            )
        }
        if (colorPickerOpen) {
            ColorPickerSheet(
                initialColorArgb = viewModel.palette.activeInkColor(),
                recents = recentColors,
                onConfirm = viewModel::confirmColorPick,
                onDismiss = viewModel::dismissColorPicker,
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
        // Sub-phase 7.4 — staged AI edit preview banner. Rendered above the
        // AI side sheet so the user sees Accept / Reject as a row at the top
        // of the canvas. Visual diff overlay (alpha+outline) is a follow-up;
        // for v1 we show the summary + counts and let the side-sheet turn
        // carry the model's narrative.
        pendingEdit?.let { pending ->
            AiEditPreviewBanner(
                pending = pending,
                onAccept = viewModel::acceptPendingEdit,
                onReject = viewModel::rejectPendingEdit,
            )
        }
        AiSideSheet(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
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
    hasActiveFrame: Boolean,
    onDismiss: () -> Unit,
    onBackgroundSelect: (String) -> Unit,
    onInsertImage: () -> Unit,
    onToggleBrushSheet: () -> Unit,
    onSharePng: () -> Unit,
    onSharePdf: () -> Unit,
    onShareSvg: () -> Unit,
    onExportVectorXml: () -> Unit,
    onSendToChat: () -> Unit,
    onExportFramePng: () -> Unit,
    onExportFrameSvg: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = "Insert",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DropdownMenuItem(
            text = { Text("Insert image…") },
            leadingIcon = {
                Icon(Icons.Filled.Image, contentDescription = null)
            },
            onClick = onInsertImage,
        )
        DropdownMenuItem(
            text = { Text("Brush settings") },
            leadingIcon = {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            },
            onClick = onToggleBrushSheet,
        )
        HorizontalDivider()
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
        // Phase 6.8 — vector-fidelity export. Opens in Inkscape / Figma /
        // browsers as a scalable document with paths and shapes preserved.
        DropdownMenuItem(
            text = { Text("Share as SVG") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Polyline,
                    contentDescription = null,
                )
            },
            onClick = onShareSvg,
        )
        // Android VectorDrawable XML — imports straight into res/drawable/.
        DropdownMenuItem(
            text = { Text("Export as Android XML…") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = null,
                )
            },
            onClick = onExportVectorXml,
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
        if (hasActiveFrame) {
            HorizontalDivider()
            Text(
                text = "Current frame",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text("Export frame as PNG") },
                leadingIcon = {
                    Icon(Icons.Filled.CropFree, contentDescription = null)
                },
                onClick = onExportFramePng,
            )
            DropdownMenuItem(
                text = { Text("Export frame as SVG") },
                leadingIcon = {
                    Icon(Icons.Filled.CropFree, contentDescription = null)
                },
                onClick = onExportFrameSvg,
            )
        }
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

/**
 * Sub-phase 7.4 — staged AI edit banner.
 *
 * Renders a compact summary + Accept / Reject row pinned above the AI side
 * sheet. The proper translucent-overlay preview (alpha + magenta outline)
 * is a near-term follow-up; for v1 this surfaces "what would change" via a
 * count line so the user can already commit / discard the AI edit as one
 * undo entry.
 */
@Composable
private fun AiEditPreviewBanner(
    pending: PendingEdit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val sim = pending.simulation
    val countLine = buildString {
        append(pending.description)
        append(" · ")
        append(sim.added.size).append(" added, ")
        append(sim.removed.size).append(" removed, ")
        append(sim.modified.size).append(" modified")
        if (sim.skipped.isNotEmpty()) {
            append(" · ").append(sim.skipped.size).append(" skipped")
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = pending.doc.summary.ifBlank { "AI edit preview" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = countLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReject) { Text("Reject") }
                    androidx.compose.material3.Button(onClick = onAccept) { Text("Accept") }
                }
            }
        }
    }
}

/**
 * Thin tappable bar that toggles the bottom palette between expanded
 * (favorites + brush sheet + tool palette visible) and collapsed
 * (only this handle visible, freeing the rest of the screen for ink).
 *
 * The chevron flips between Down (palette expanded; tap collapses) and Up
 * (palette collapsed; tap expands). Tap target spans the full width so the
 * user doesn't have to aim at the icon.
 */
@Composable
private fun PaletteCollapseHandle(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp)
            .semantics {
                contentDescription = if (expanded) "Collapse tools" else "Expand tools"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
