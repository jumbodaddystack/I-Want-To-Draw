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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.Role
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
    val stickyEditTarget by viewModel.stickyEditTarget.collectAsState()
    // Sub-phase 12.3 — non-null while a single path is in node-edit mode.
    val nodeEditTarget by viewModel.nodeEditTarget.collectAsState()
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
    // Sub-phase 11.5 — non-null while presenting; indexes the ordinal-sorted
    // frame deck. All editor chrome hides and the stylus becomes a laser.
    val presentationIndex by viewModel.presentationIndex.collectAsState()
    val presenting = presentationIndex != null
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
    val fingerDrawing by viewModel.fingerDrawing.collectAsState()
    val iconPixelGrid by viewModel.iconPixelGrid.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var panelsMenuExpanded by remember { mutableStateOf(false) }
    var pdfDialogVisible by remember { mutableStateOf(false) }
    var vectorXmlDialogVisible by remember { mutableStateOf(false) }
    var svgDialogVisible by remember { mutableStateOf(false) }
    var svgDialogForFrame by remember { mutableStateOf(false) }
    var iconSetDialogVisible by remember { mutableStateOf(false) }
    // Live preview + skipped-item count for the vector-XML export dialog,
    // recomputed off the main thread each time the dialog opens.
    var vectorPreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var vectorSkippedCount by remember { mutableStateOf(0) }
    var brushSheetOpen by remember { mutableStateOf(false) }
    var canvasSizeDialogVisible by remember { mutableStateOf(false) }
    var saveStampDialogVisible by remember { mutableStateOf(false) }
    var viewportController by remember { mutableStateOf<ViewportController?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var paletteHeightPx by remember { mutableStateOf(0) }
    // Collapsible bottom-palette state. When false, the favorites + brush
    // sheet + tool palette rows hide behind a thin handle; the canvas above
    // expands to reclaim ~150dp of vertical space for drawing/writing.
    var palettesExpanded by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val paletteHeightDp = with(density) { paletteHeightPx.toDp() }
    val recordingBottomOffset = if (palettesExpanded) {
        // Keep the recording control above the expanded palette while still
        // allowing palette background to bleed behind system navigation.
        paletteHeightDp + 12.dp
    } else {
        // Collapsed state only needs handle clearance + touch breathing room.
        56.dp
    }

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
            // Don't persist a never-touched new note — backing out of a fresh,
            // empty note shouldn't leave a junk "Untitled" row in the list.
            // (commitTextEdit ran first, so typed-but-uncommitted text counts.)
            // The autosave may already have written a row for content the user
            // has since undone — discard it so it doesn't resurrect.
            if (viewModel.isBlankNewNote()) {
                viewModel.discardAutosavedIfBlank()
                onNavigateBack()
                return@launch
            }
            // Persist the current viewport for icons so reopening restores the
            // exact view (pan + zoom) rather than getting "lost" on the canvas.
            val vp = viewportController
            if (note.isIcon && vp != null) {
                viewModel.setIconViewport(vp.offsetX, vp.offsetY, vp.scale)
            }
            viewModel.save()
            onNavigateBack()
        }
    }

    BackHandler {
        // Back exits the presentation first; only a second back leaves the note.
        if (presenting) viewModel.exitPresentation() else saveAndExit()
    }

    // Fly the viewport to the active presentation frame whenever the stepper
    // moves (and on entry). Ordinal order is the deck order.
    LaunchedEffect(presentationIndex, canvasSize) {
        val index = presentationIndex ?: return@LaunchedEffect
        val vp = viewportController ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        val frame = viewModel.presentationFrames().getOrNull(index) ?: return@LaunchedEffect
        vp.flyTo(
            bounds = frame.bounds(),
            canvasSize = floatArrayOf(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
        )
    }

    // Backgrounding the app (home button, app switch) flushes any pending
    // debounced autosave — otherwise a process kill inside the 3-second
    // debounce window silently drops the last edits.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushPendingSave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Switching away from the TEXT tool commits any in-flight edit so the
    // user doesn't see a stale editor floating over their ink stroke. The
    // snapshotFlow translates `palette.selected` (Compose state) into a flow
    // we can observe without redrawing the whole screen on every keystroke.
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.palette.selected }.collectLatest { tool ->
            if (tool != Tool.TEXT) viewModel.commitTextEdit()
            if (tool != Tool.STICKY) viewModel.commitStickyEdit()
            // 12.3 — picking any tool backs out of node-edit mode (each
            // gesture already committed, so this is non-destructive).
            viewModel.exitNodeEdit()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.stampSaved.collect { name ->
            snackbarHostState.showSnackbar("Saved \"$name\" to stamps")
        }
    }
    LaunchedEffect(Unit) {
        viewModel.templateSaved.collect { name ->
            snackbarHostState.showSnackbar("Saved \"$name\" as a template")
        }
    }

    // Icons are a *bounded* canvas: keep the artboard rubber-banded into the
    // viewport so it can never be flung off-screen (notes stay infinite). This
    // effect re-installs the clamp whenever the artboard or canvas size changes
    // (rotation, artboard resize), and clears it for non-icon notes. setPanBounds
    // re-clamps immediately, so a now-illegal offset snaps back into range.
    LaunchedEffect(note.isIcon, frames, currentFrameId, viewportController, canvasSize) {
        val vp = viewportController ?: return@LaunchedEffect
        if (note.isIcon && canvasSize != IntSize.Zero) {
            viewModel.currentFrameBounds()?.let { artboard ->
                vp.setPanBounds(
                    bounds = artboard,
                    canvasSize = floatArrayOf(
                        canvasSize.width.toFloat(),
                        canvasSize.height.toFloat(),
                    ),
                )
            }
        } else if (!note.isIcon) {
            vp.clearPanBounds()
        }
    }

    // Restore the view when an icon first becomes visible. Without this the
    // viewport stays at the origin / 100%, so a reopened icon's box and strokes
    // sit off-screen and the saved work looks lost. Prefers the persisted
    // viewport (exact view the user left); falls back to fitting the artboard
    // for first opens / pre-v16 icons. Runs once per note (the artboard frame
    // loads asynchronously, so we wait for it).
    var didInitialIconFit by remember(note.id) { mutableStateOf(false) }
    LaunchedEffect(note.id, note.isIcon, frames, currentFrameId, viewportController, canvasSize) {
        if (didInitialIconFit || !note.isIcon) return@LaunchedEffect
        val vp = viewportController ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        val artboard = viewModel.currentFrameBounds() ?: return@LaunchedEffect
        val canvasFloats = floatArrayOf(
            canvasSize.width.toFloat(),
            canvasSize.height.toFloat(),
        )
        // Ensure the clamp is installed before the restore/fit so the result
        // lands legal even if this effect wins the race with the one above.
        vp.setPanBounds(artboard, canvasFloats)
        val savedX = note.viewportOffsetX
        val savedY = note.viewportOffsetY
        val savedScale = note.viewportScale
        if (savedX != null && savedY != null && savedScale != null) {
            vp.setForAnimation(savedX, savedY, savedScale)
        } else {
            vp.flyTo(
                bounds = viewModel.iconOpenBounds() ?: artboard,
                canvasSize = canvasFloats,
            )
        }
        didInitialIconFit = true
    }

    Scaffold(
        // We handle the navigation-bar inset ourselves on the bottom palette
        // Surface so its tonal background bleeds to the screen edge instead
        // of stopping above the system nav bar (which used to leave a visible
        // empty strip below the width slider).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 11.5 — presenting hides the whole header; the presentation
            // control strip (bottom-centre overlay) is the only chrome.
            if (!presenting) {
            // Compact custom header instead of TopAppBar: M3 1.2 pins the
            // bar at 64 dp and the OutlinedTextField title needs 56 dp more
            // vertical room than its text — together that read as a band of
            // wasted space above the canvas. A 48 dp row keeps every icon at
            // full touch size while giving the difference back to the page.
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::saveAndExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    val titleStyle = MaterialTheme.typography.titleMedium
                    BasicTextField(
                        value = note.title,
                        onValueChange = viewModel::setTitle,
                        singleLine = true,
                        textStyle = titleStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (note.title.isEmpty()) {
                                    Text(
                                        text = "Untitled",
                                        style = titleStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                    OcrIndicatorBadge(state = ocrIndicator)
                    // Hero AI action. Accented (filled-tonal) so it stands out
                    // from the monochrome toolbar icons — the user said AI
                    // should be easy to reach. Icons open the sheet edit-first;
                    // notes open it ask-first (the VM picks the default).
                    FilledTonalIconButton(
                        onClick = { viewModel.openAiSheet(selection = null) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = if (note.isIcon) "Edit icon with AI" else "Ask AI about this note",
                        )
                    }
                    // Panels group — frames/pages, stamps and layers fold into
                    // one menu so the bar keeps room for the title on phones
                    // (eight standalone actions used to squeeze it out
                    // entirely). The zoom chip moved onto the canvas itself.
                    Box {
                        IconButton(onClick = { panelsMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.Dashboard,
                                contentDescription = "Panels",
                            )
                        }
                        DropdownMenu(
                            expanded = panelsMenuExpanded,
                            onDismissRequest = { panelsMenuExpanded = false },
                        ) {
                            // Sub-phase 8.2 — frame navigator. In notebook
                            // mode we surface a Pages rail instead so the
                            // canvas only carries one strip at a time.
                            if (notebook != null) {
                                DropdownMenuItem(
                                    text = { Text("Pages") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Dashboard, contentDescription = null)
                                    },
                                    onClick = {
                                        panelsMenuExpanded = false
                                        viewModel.togglePageRail()
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Frames") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Dashboard, contentDescription = null)
                                    },
                                    onClick = {
                                        panelsMenuExpanded = false
                                        viewModel.toggleFrameNavigator()
                                    },
                                )
                            }
                            // Sub-phase 8.3 — stamp drawer.
                            DropdownMenuItem(
                                text = { Text("Stamps") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Bookmark, contentDescription = null)
                                },
                                onClick = {
                                    panelsMenuExpanded = false
                                    viewModel.openStampDrawer()
                                },
                            )
                            // Sub-phase 6.4 — layers panel.
                            DropdownMenuItem(
                                text = { Text("Layers") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Layers, contentDescription = null)
                                },
                                onClick = {
                                    panelsMenuExpanded = false
                                    viewModel.toggleLayersPanel()
                                },
                            )
                        }
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
                            hasActiveFrame = currentFrameId != null,
                            isIcon = note.isIcon,
                            fingerDrawing = fingerDrawing,
                            onToggleFingerDrawing = {
                                viewModel.setFingerDrawing(!fingerDrawing)
                            },
                            iconPixelGrid = iconPixelGrid,
                            onToggleIconPixelGrid = {
                                viewModel.setIconPixelGrid(!iconPixelGrid)
                            },
                            onExportIconSet = {
                                menuExpanded = false
                                iconSetDialogVisible = true
                            },
                            onResizeArtboard = {
                                menuExpanded = false
                                canvasSizeDialogVisible = true
                            },
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
                                svgDialogForFrame = false
                                svgDialogVisible = true
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
                                svgDialogForFrame = true
                                svgDialogVisible = true
                            },
                            canPresent = frames.isNotEmpty(),
                            onPresent = {
                                menuExpanded = false
                                viewModel.startPresentation()
                            },
                            onSaveAsTemplate = {
                                menuExpanded = false
                                viewModel.saveNoteAsTemplate()
                            },
                        )
                    }
                }
            }
            }
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
                    editingStickyId = stickyEditTarget?.itemId,
                    onStrokeCommitted = viewModel::addItem,
                    onItemsErased = viewModel::removeItems,
                    onLassoCompleted = viewModel::onLassoCompleted,
                    onSelectionShouldClear = viewModel::clearSelection,
                    onTextTap = viewModel::onTextToolTap,
                    onStickyTap = viewModel::onStickyToolTap,
                    onStrokeHoldRecognized = viewModel::onStrokeHoldRecognized,
                    modifier = Modifier.fillMaxSize(),
                    snapMask = snapMask,
                    layers = layers,
                    activeTextureId = activeBrushPreset?.textureId,
                    onViewportReady = { viewportController = it },
                    onFrameDrawn = viewModel::onFrameDrawn,
                    onFrameTap = viewModel::onFrameTap,
                    recordingStartedAt = recordingStartedAt,
                    fingerInkEnabled = fingerDrawing,
                    // Icons are a bounded canvas: clip ink to the artboard.
                    artboardClipBounds = if (note.isIcon) {
                        viewModel.currentFrameBounds()
                    } else {
                        null
                    },
                    // Phase 15.3 — pixel snap + keylines. One graph-background
                    // cell per icon pixel for every icon canvas size.
                    pixelGridSpacingWorld = if (note.isIcon && iconPixelGrid) {
                        BackgroundLayer.SPACING_WORLD
                    } else {
                        0f
                    },
                    // 11.5 — stylus = laser, barrel button = next frame.
                    presentationMode = presenting,
                    onPresentationAdvance = { viewModel.stepPresentation(1) },
                )
                // Phase 5.4 — zoom chip + Fit / 100% / Center popover. Lives
                // on the canvas (not the TopAppBar) so the bar keeps room for
                // the title. Bounds are recomputed lazily on each menu open
                // so the user doesn't pay for a scan per recomposition.
                if (!presenting) ZoomChrome(
                    viewport = viewportController,
                    canvasSize = canvasSize,
                    contentBoundsProvider = {
                        val bounds = viewModel.computeBoundsForExport()
                        // The exporter returns a default A4-ish rect for
                        // empty notes; fit-on-empty would be visually
                        // confusing, so collapse those to null.
                        if (viewModel.items.isEmpty()) null else bounds
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                // Phase 15.3 — live small-size preview while editing icons.
                if (!presenting && note.isIcon) IconLivePreview(
                    items = viewModel.items,
                    frameBounds = frames.firstOrNull()?.bounds(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                // Sub-phase 8.1 — frame rectangles + name labels rendered
                // above the canvas. Hidden while presenting so the audience
                // sees content, not scaffolding.
                if (!presenting) FrameOverlay(
                    frames = frames,
                    currentFrameId = currentFrameId,
                    viewport = viewportController,
                )
                if (!presenting) SelectionOverlay(
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
                    // Phase 10 — restyle / group / arrange.
                    selectionHasShapes = viewModel.selectionHasShapes(),
                    canGroup = selection.size >= 2,
                    canUngroup = viewModel.selectionHasGroup(),
                    canDistribute = selection.size >= 3,
                    onSetFill = viewModel::setSelectionFill,
                    onSetStrokeStyle = viewModel::setSelectionStrokeStyle,
                    onGroup = viewModel::groupSelection,
                    onUngroup = viewModel::ungroupSelection,
                    onAlign = viewModel::alignSelection,
                    onDistribute = viewModel::distributeSelection,
                    onReorder = viewModel::reorderSelection,
                    // Phase 12.3/12.4 — node editing + convert to path.
                    canEditNodes = viewModel.selectionIsSinglePath(),
                    onEditNodes = viewModel::enterNodeEdit,
                    canConvertToPath = viewModel.selectionHasConvertibles(),
                    onConvertToPath = viewModel::convertSelectionToPaths,
                    // Phase 15.2 — stroke → filled outline path.
                    canOutlineStroke = viewModel.selectionHasOutlinableStrokes(),
                    onOutlineStroke = viewModel::outlineSelectionStrokes,
                    // Phase 13 — booleans, gradients, style tools.
                    canCombine = viewModel.selectionCanCombine(),
                    onCombine = viewModel::combineSelection,
                    canMergePaths = viewModel.selectionCanMergePaths(),
                    canTidy = viewModel.selectionCanTidy(),
                    onTidy = { viewModel.tidySelection() },
                    onMergePaths = viewModel::mergeSelectionPaths,
                    onSetGradient = viewModel::setSelectionGradient,
                    canCopyStyle = viewModel.selectionIsSingleStyleSource(),
                    onCopyStyle = viewModel::copySelectionStyle,
                    canPasteStyle = viewModel.hasStyleClipboard(),
                    onPasteStyle = viewModel::pasteStyleToSelection,
                    canPickColor = selection.size == 1,
                    onPickColor = viewModel::pickColorFromSelection,
                )
                // Sub-phase 12.3 — node-edit overlay replaces the selection
                // overlay (the selection cleared when node editing began).
                val nodeEditItem = nodeEditTarget?.let { id ->
                    viewModel.items.firstOrNull { it.id == id }
                }
                if (!presenting && nodeEditItem != null) {
                    PathNodeEditor(
                        item = nodeEditItem,
                        viewport = viewportController,
                        onPreview = { payload ->
                            viewModel.previewPathNodeEdit(nodeEditItem.id, payload)
                        },
                        onGestureEnd = { before, description ->
                            viewModel.commitPathNodeGesture(nodeEditItem.id, before, description)
                        },
                        onImmediateEdit = { payload, description ->
                            viewModel.applyPathNodeEdit(nodeEditItem.id, payload, description)
                        },
                        onDeleteItem = { viewModel.deleteNodeEditItem(nodeEditItem.id) },
                        onDone = viewModel::exitNodeEdit,
                    )
                }
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
                // Sub-phase 11.1 — inline sticky body editor. Reuses the
                // text-item editor overlay anchored at the sticky's text
                // inset; the surface keeps drawing the rect underneath and
                // suppresses the rasterized body while this is open.
                val stickyTarget = stickyEditTarget
                if (stickyTarget != null && vp != null) {
                    TextItemEditor(
                        initialBody = stickyTarget.initialBody,
                        screenOriginX = vp.worldToScreenX(stickyTarget.worldX),
                        screenOriginY = vp.worldToScreenY(stickyTarget.worldY),
                        fontSizePx = stickyTarget.fontSize * vp.scale,
                        alignment = com.aichat.sandbox.ui.components.notes.TextItemCodec.ALIGN_LEFT,
                        maxWidthPx = stickyTarget.maxWidthWorld * vp.scale,
                        onBodyChanged = viewModel::onStickyEditBodyChanged,
                        onCommit = viewModel::commitStickyEdit,
                    )
                }
            }
            // Sub-phase 9.4 — audio recordings strip (playback + scrubber).
            // Hidden until at least one clip exists; the record button below
            // surfaces independent of clip count.
            if (!presenting) AudioPlaybackBar(
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
            if (!presenting) Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { paletteHeightPx = it.height },
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
                                onPickShapeFillColor = viewModel::openShapeFillColorPicker,
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
        // Sub-phase 11.5 — presentation control strip: prev / counter / next
        // / exit, floating bottom-centre over the otherwise chrome-free canvas.
        if (presenting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.BottomCenter,
            ) {
                PresentationControls(
                    index = presentationIndex ?: 0,
                    count = frames.size,
                    onPrev = { viewModel.stepPresentation(-1) },
                    onNext = { viewModel.stepPresentation(1) },
                    onExit = viewModel::exitPresentation,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
        // Sub-phase 9.4 — record button anchored above the AI side sheet.
        if (!presenting) Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.BottomEnd,
        ) {
            AudioRecordingBar(
                isRecording = isRecordingAudio,
                onStart = viewModel::startAudioRecording,
                onStop = viewModel::stopAudioRecording,
                modifier = Modifier.padding(end = 12.dp, bottom = recordingBottomOffset),
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
        if (iconSetDialogVisible) {
            ExportSvgDialog(
                title = "Export icon set",
                onCancel = { iconSetDialogVisible = false },
                onExport = { preservePressure ->
                    iconSetDialogVisible = false
                    scope.launch {
                        val result = viewModel.shareIconSet(preservePressure)
                        if (result.skippedCount > 0) {
                            Toast.makeText(
                                context,
                                "${result.skippedCount} text/image item(s) skipped in the " +
                                    "XML sizes — not supported by vector drawables",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, result.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Share icon set")
                        )
                    }
                },
            )
        }
        if (svgDialogVisible) {
            ExportSvgDialog(
                title = if (svgDialogForFrame) "Share frame as SVG" else "Share note as SVG",
                onCancel = { svgDialogVisible = false },
                onExport = { preservePressure ->
                    svgDialogVisible = false
                    val forFrame = svgDialogForFrame
                    scope.launch {
                        val uri = if (forFrame) {
                            viewModel.shareSvgForCurrentFrame(preservePressure) ?: return@launch
                        } else {
                            viewModel.shareSvg(preservePressure)
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/svg+xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                send,
                                if (forFrame) "Share frame as SVG" else "Share note as SVG",
                            )
                        )
                    }
                },
            )
        }
        if (vectorXmlDialogVisible) {
            LaunchedEffect(Unit) {
                vectorSkippedCount = viewModel.vectorExportSkippedCount()
                vectorPreview = viewModel.renderVectorPreview()?.asImageBitmap()
            }
            ExportVectorXmlDialog(
                skippedCount = vectorSkippedCount,
                preview = vectorPreview,
                onCancel = {
                    vectorXmlDialogVisible = false
                    vectorPreview = null
                },
                onExport = { sizeDp, preservePressure ->
                    vectorXmlDialogVisible = false
                    scope.launch {
                        val result = viewModel.shareVectorXml(sizeDp, preservePressure)
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
        if (canvasSizeDialogVisible) {
            IconCanvasSizeDialog(
                current = viewModel.iconArtboardWorld()
                    ?.let { IconCanvasSize.nearest(it) },
                onSelect = { choice ->
                    viewModel.resizeIconArtboard(choice.world)
                    canvasSizeDialogVisible = false
                },
                onDismiss = { canvasSizeDialogVisible = false },
            )
        }
        // Sub-phase 8.3 — bottom-aligned stamp drawer.
        if (stampDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
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
            onSubmit = viewModel::submitAiFooter,
            onCancel = viewModel::cancelAiStreaming,
            onClose = viewModel::closeAiSheet,
            onCannedPrompt = viewModel::submitCannedPrompt,
            onIconQuickAction = viewModel::submitIconQuickAction,
            onFooterModeChanged = viewModel::setAiFooterMode,
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
private fun IconCanvasSizeDialog(
    current: IconCanvasSize?,
    onSelect: (IconCanvasSize) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canvas size") },
        text = {
            Column {
                Text(
                    text = "Pick the design grid for this icon. Larger grids give finer " +
                        "detail; the export scales the artwork to the chosen icon size.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                IconCanvasSize.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun EditorOverflowMenu(
    expanded: Boolean,
    current: String,
    hasActiveFrame: Boolean,
    isIcon: Boolean,
    fingerDrawing: Boolean,
    onToggleFingerDrawing: () -> Unit,
    iconPixelGrid: Boolean,
    onToggleIconPixelGrid: () -> Unit,
    onExportIconSet: () -> Unit,
    onResizeArtboard: () -> Unit,
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
    canPresent: Boolean = false,
    onPresent: () -> Unit = {},
    onSaveAsTemplate: () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Sub-phase 11.5 — full-screen frame stepper. Gated on having at
        // least one frame: a frameless note has nothing to step through.
        if (canPresent) {
            DropdownMenuItem(
                text = { Text("Present") },
                leadingIcon = {
                    Icon(Icons.Filled.Slideshow, contentDescription = null)
                },
                onClick = onPresent,
            )
            HorizontalDivider()
        }
        if (isIcon) {
            Text(
                text = "Icon",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text("Canvas size…") },
                leadingIcon = {
                    Icon(Icons.Filled.AspectRatio, contentDescription = null)
                },
                onClick = onResizeArtboard,
            )
            // Phase 15.3 — snap-to-pixel + keyline overlay on the artboard.
            DropdownMenuItem(
                text = { Text("Pixel grid & keylines") },
                leadingIcon = {
                    Icon(Icons.Filled.Grid4x4, contentDescription = null)
                },
                trailingIcon = {
                    if (iconPixelGrid) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Enabled",
                        )
                    }
                },
                onClick = onToggleIconPixelGrid,
            )
            // Phase 15.4 — XML at 24/48/108 dp + SVG + PNG in one zip.
            DropdownMenuItem(
                text = { Text("Export icon set…") },
                leadingIcon = {
                    Icon(Icons.Filled.FolderZip, contentDescription = null)
                },
                onClick = onExportIconSet,
            )
            HorizontalDivider()
        }
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
        // 14.3 — snapshot this note (items + frames) as a reusable template;
        // it appears under "Your templates" in the new-note menu.
        DropdownMenuItem(
            text = { Text("Save as template") },
            leadingIcon = {
                Icon(Icons.Filled.Dashboard, contentDescription = null)
            },
            onClick = onSaveAsTemplate,
        )
        // "Draw with finger" — lets phones without a stylus ink at all.
        // Off: classic routing (finger pans, stylus inks). On: one finger
        // inks, two fingers pan/zoom; a stylus still takes priority.
        DropdownMenuItem(
            text = { Text("Draw with finger") },
            leadingIcon = {
                Icon(Icons.Filled.TouchApp, contentDescription = null)
            },
            trailingIcon = {
                if (fingerDrawing) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Enabled",
                    )
                }
            },
            onClick = onToggleFingerDrawing,
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
 * Sub-phase 11.5 — floating presentation control strip. Deliberately the
 * only chrome on screen while presenting: previous / "n of N" counter /
 * next / exit. The stylus barrel button and laser ink live on the surface.
 */
@Composable
private fun PresentationControls(
    index: Int,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPrev, enabled = index > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous frame")
            }
            Text(
                text = "${index + 1} / $count",
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onNext, enabled = index < count - 1) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next frame")
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = "Exit presentation")
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
