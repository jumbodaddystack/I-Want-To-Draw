package com.aichat.sandbox.ui.screens.notes

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.model.Stamp
import com.aichat.sandbox.data.notes.AiChunk
import com.aichat.sandbox.data.notes.AskMode
import com.aichat.sandbox.data.notes.AskRequest
import com.aichat.sandbox.data.notes.CannedEditAction
import com.aichat.sandbox.data.notes.EditOpsDoc
import com.aichat.sandbox.data.notes.HandwritingOcr
import com.aichat.sandbox.data.notes.aiRecolorPrompt
import com.aichat.sandbox.data.notes.HandwritingOcr.OcrModelState
import com.aichat.sandbox.data.notes.NoteAiService
import com.aichat.sandbox.data.notes.NoteExporter
import com.aichat.sandbox.data.notes.NoteImageStore
import com.aichat.sandbox.data.notes.NoteSvgExporter
import com.aichat.sandbox.data.notes.PdfLayout
import com.aichat.sandbox.data.notes.PendingDraftStore
import com.aichat.sandbox.data.notes.RecentColorsStore
import com.aichat.sandbox.data.repository.BrushPresetRepository
import com.aichat.sandbox.data.repository.ChatRepository
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.repository.StampRepository
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.Snap
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.aichat.sandbox.ui.components.notes.TextItemRenderer
import com.aichat.sandbox.ui.components.notes.Tool
import com.aichat.sandbox.ui.components.notes.ToolPaletteState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

const val NOTE_ID_NEW = "new"
private const val DEFAULT_TITLE = "Untitled"
private const val DEFAULT_BACKGROUND_STYLE = "plain"
private const val CURRENT_SCHEMA_VERSION = 1
private const val UNDO_STACK_CAP = 200

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository,
    private val aiService: NoteAiService,
    private val handwritingOcr: HandwritingOcr,
    private val preferencesManager: PreferencesManager,
    private val chatRepository: ChatRepository,
    private val noteExporter: NoteExporter,
    private val noteSvgExporter: NoteSvgExporter,
    private val recentColorsStore: RecentColorsStore,
    private val brushPresets: BrushPresetRepository,
    private val noteImageStore: NoteImageStore,
    private val stampRepository: StampRepository,
    private val favoritesStore: com.aichat.sandbox.data.notes.FavoritesStore,
    val frameThumbnailRenderer: com.aichat.sandbox.data.notes.FrameThumbnailRenderer,
    // Sub-phase 9.4 — audio-synced ink. Recorder + player + persistence.
    private val audioRecorder: com.aichat.sandbox.data.notes.AudioRecorder,
    val audioPlayer: com.aichat.sandbox.data.notes.AudioPlayer,
    private val audioRepository: com.aichat.sandbox.data.repository.NoteAudioRepository,
    // Sub-phase 9.1 — notebook header lookup (page size / style for "Add page").
    private val notebookRepository: com.aichat.sandbox.data.repository.NotebookRepository,
) : ViewModel() {

    private val routeArg: String = savedStateHandle["noteId"] ?: NOTE_ID_NEW

    // Stable id for the "new" path: generated once, reused across saves so rapid
    // back-presses don't create duplicate notes.
    private val resolvedNoteId: String =
        if (routeArg == NOTE_ID_NEW) UUID.randomUUID().toString() else routeArg

    // Phase 3.1 deep-link args. Only populated on the `note/new?source=…&stylus=…`
    // route; bare `note/{noteId}` opens leave them null/false. `stylus=true`
    // forces the PEN tool — today this is already the default, but pinning it
    // explicitly insulates future palette-default changes from breaking the
    // quick-capture flows that rely on landing inked-up and ready.
    private val entrySource: String? = savedStateHandle["source"]
    private val entryStylus: Boolean = savedStateHandle["stylus"] ?: false

    private val _note = MutableStateFlow(emptyNote(resolvedNoteId))
    val note: StateFlow<Note> = _note.asStateFlow()

    val items: SnapshotStateList<NoteItem> = mutableStateListOf()

    /** Tool palette state — selection, per-tool color / width, area-eraser radius. */
    val palette: ToolPaletteState = ToolPaletteState()

    private var nextInkZIndex: Int = 0
    private var nextHighlighterZIndex: Int = HIGHLIGHTER_Z_BASE

    // Undo / redo log. Capped to keep a long session bounded; the oldest
    // action is dropped silently when [UNDO_STACK_CAP] is exceeded. Persisted
    // to `Note.undoLogJson` on each save and re-hydrated in [init]
    // (sub-phase 5.2).
    private val past = ArrayDeque<EditorAction>()
    private val future = ArrayDeque<EditorAction>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // ── Selection state (sub-phase 1.8) ──────────────────────────────────
    //
    // The selection is held as a Set<String> of NoteItem ids. The DrawingSurface
    // skips these from the scene bitmap and draws them through the live path
    // with [selectionMatrix] applied; the Compose-side overlay reads
    // [selectionWorldBounds] for the dashed rectangle and handles.
    //
    // [selectionMatrix] is the in-flight transform — identity when no drag is
    // active. On gesture release, [bakeSelectionTransform] flushes it through
    // an `EditorAction.TransformItems` so undo/redo round-trip correctly.

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _selectionWorldBounds = MutableStateFlow<FloatArray?>(null)
    val selectionWorldBounds: StateFlow<FloatArray?> = _selectionWorldBounds.asStateFlow()

    private val _selectionMatrix = MutableStateFlow(StrokeTransform.IDENTITY)
    val selectionMatrix: StateFlow<FloatArray> = _selectionMatrix.asStateFlow()

    // ── Text editor target (sub-phase 1.9) ───────────────────────────────
    //
    // Two modes:
    //  - NewAt: the user tapped empty canvas with the TEXT tool. No item
    //    exists in [items] yet — it lands on commit, only if the body is
    //    non-empty. This keeps "tap, then cancel" from polluting undo.
    //  - Existing: the user tapped on an existing text item. The item is
    //    already in [items]; on commit we diff the body and record an
    //    [EditorAction.UpdateText] (or `RemoveItems` when the body emptied).

    private val _textEditTarget = MutableStateFlow<TextEditTarget?>(null)
    val textEditTarget: StateFlow<TextEditTarget?> = _textEditTarget.asStateFlow()

    // ── OCR indicator (sub-phase 2.4) ────────────────────────────────────
    //
    // Combines the ML Kit model-download state (shared across the app) with
    // the in-flight job tracker filtered to *this* note's id. The TopAppBar
    // renders a small icon while non-Idle; everything else is invisible.

    val ocrIndicator: StateFlow<OcrIndicator> = combine(
        repository.ocrModelState,
        repository.ocrJobsInFlight,
    ) { modelState, inFlight ->
        when {
            resolvedNoteId in inFlight -> OcrIndicator.Running
            modelState is OcrModelState.Downloading -> OcrIndicator.Downloading
            else -> OcrIndicator.Idle
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = OcrIndicator.Idle,
    )

    /** Live draft body — Compose `BasicTextField` writes this on every keystroke. */
    private var textEditDraftBody: String = ""

    // ── AI side sheet (sub-phase 2.6) ────────────────────────────────────
    //
    // The sheet hosts a list of one-shot ask/reply turns. State is held here
    // (rather than in a dedicated VM) because submitting a prompt needs the
    // live `note` + `items`, the user's selected model from preferences, and
    // shares lifetime with the editor. Streaming jobs are tracked separately
    // so cancellation can stop the upstream flow within a frame or two.

    private val _aiSheetState = MutableStateFlow(AiSideSheetState())
    val aiSheetState: StateFlow<AiSideSheetState> = _aiSheetState.asStateFlow()

    private val streamingJobs: MutableMap<String, Job> = mutableMapOf()

    // ── AI edit preview (sub-phase 7.4) ──────────────────────────────────
    //
    // When `runStream` runs in EDIT mode the terminal `AiChunk.EditPreview`
    // event is staged here as a `PendingEdit` rather than committed to the
    // canvas. The editor renders a translucent overlay on top of the live
    // scene from this state; `acceptPendingEdit` / `rejectPendingEdit`
    // commit-or-discard. Only one preview can be active at a time; firing
    // another EDIT request while a preview is staged silently replaces it.

    private val _pendingEdit = MutableStateFlow<PendingEdit?>(null)
    val pendingEdit: StateFlow<PendingEdit?> = _pendingEdit.asStateFlow()

    /**
     * Models offered by the in-sheet model picker (sub-phase 2.8). Mirrors
     * the chat-side `ModelSelector` source: built-in provider catalogue
     * plus user-added customs from preferences. De-duplicated so a custom
     * id colliding with a built-in only appears once.
     */
    val availableModels: StateFlow<List<String>> = preferencesManager.customModels
        .map { byProvider ->
            val builtIns = ApiProvider.defaults.flatMap { it.models }
            val customs = byProvider.values.flatten()
            (builtIns + customs.filter { it !in builtIns })
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ApiProvider.defaults.flatMap { it.models },
        )

    /**
     * Sub-phase 5.3 — twelve most-recent custom colours, app-wide. Surfaced
     * to the [ColorPickerSheet] so the user can re-apply yesterday's pick
     * with one tap.
     */
    val recentColors: StateFlow<List<Int>> = recentColorsStore.recentColors
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    /**
     * Sub-phase 5.3 — whether the colour picker sheet is currently open. The
     * sheet edits the active ink tool's colour; the VM owns the open/close
     * state so a configuration change (rotation) doesn't lose the user's
     * in-flight pick.
     */
    private val _colorPickerOpen = MutableStateFlow(false)
    val colorPickerOpen: StateFlow<Boolean> = _colorPickerOpen.asStateFlow()

    /**
     * Phase 6.3 — snap toggle bitmask. Bit 0 = angle (15°), bit 1 = grid,
     * bit 2 = endpoint. Editor surfaces this through a chip; default is
     * angle + endpoint on, grid off (matches Concepts).
     */
    private val _snapMask = MutableStateFlow(Snap.MASK_ANGLE or Snap.MASK_ENDPOINT)
    val snapMask: StateFlow<Int> = _snapMask.asStateFlow()

    fun toggleSnap(mask: Int) {
        _snapMask.value = _snapMask.value xor mask
    }

    // ── Layers (sub-phase 6.4) ───────────────────────────────────────────

    private val _layers = MutableStateFlow<List<NoteLayer>>(emptyList())
    val layers: StateFlow<List<NoteLayer>> = _layers.asStateFlow()

    private val _activeLayerId = MutableStateFlow<String?>(null)
    val activeLayerId: StateFlow<String?> = _activeLayerId.asStateFlow()

    private val _layersPanelOpen = MutableStateFlow(false)
    val layersPanelOpen: StateFlow<Boolean> = _layersPanelOpen.asStateFlow()

    fun toggleLayersPanel() { _layersPanelOpen.value = !_layersPanelOpen.value }
    fun closeLayersPanel() { _layersPanelOpen.value = false }

    fun selectLayer(layerId: String) {
        if (_layers.value.none { it.id == layerId }) return
        _activeLayerId.value = layerId
    }

    fun addLayer(name: String = "Layer ${_layers.value.size + 1}") {
        val current = _layers.value
        val nextOrdinal = (current.maxOfOrNull { it.ordinal } ?: -1) + 1
        val layer = NoteLayer(
            noteId = resolvedNoteId,
            name = name,
            opacityPercent = 100,
            visible = true,
            locked = false,
            ordinal = nextOrdinal,
        )
        _layers.value = current + layer
        _activeLayerId.value = layer.id
    }

    fun toggleLayerVisible(layer: NoteLayer) =
        updateLayer(layer.id) { it.copy(visible = !it.visible) }

    fun toggleLayerLocked(layer: NoteLayer) =
        updateLayer(layer.id) { it.copy(locked = !it.locked) }

    fun setLayerOpacity(layer: NoteLayer, percent: Int) =
        updateLayer(layer.id) { it.copy(opacityPercent = percent.coerceIn(0, 100)) }

    fun moveLayerUp(layer: NoteLayer) {
        val sorted = _layers.value.sortedBy { it.ordinal }
        val idx = sorted.indexOfFirst { it.id == layer.id }
        if (idx < 0 || idx >= sorted.size - 1) return
        val above = sorted[idx + 1]
        _layers.value = _layers.value.map {
            when (it.id) {
                layer.id -> it.copy(ordinal = above.ordinal)
                above.id -> it.copy(ordinal = layer.ordinal)
                else -> it
            }
        }
    }

    fun moveLayerDown(layer: NoteLayer) {
        val sorted = _layers.value.sortedBy { it.ordinal }
        val idx = sorted.indexOfFirst { it.id == layer.id }
        if (idx <= 0) return
        val below = sorted[idx - 1]
        _layers.value = _layers.value.map {
            when (it.id) {
                layer.id -> it.copy(ordinal = below.ordinal)
                below.id -> it.copy(ordinal = layer.ordinal)
                else -> it
            }
        }
    }

    /**
     * Delete [layer] from the list. Items previously on it are reparented to
     * the active layer (or the topmost remaining layer if the active layer
     * is the one being deleted) so they don't vanish from the canvas. The
     * reparent uses a single [EditorAction.MoveItemsBetweenLayers] so undo
     * walks both the layer removal and the reparent together.
     *
     * Refuses to delete the only remaining layer — the panel button is
     * gated on `layers.size > 1`, but the guard keeps the VM honest.
     */
    fun deleteLayer(layer: NoteLayer) {
        val current = _layers.value
        if (current.size <= 1) return
        val survivors = current.filterNot { it.id == layer.id }
        val replacementId = (_activeLayerId.value
            ?.takeIf { id -> id != layer.id && survivors.any { it.id == id } }
            ?: survivors.maxByOrNull { it.ordinal }?.id)
        val affectedIds = items.filter { it.layerId == layer.id }.map { it.id }
        if (affectedIds.isNotEmpty()) {
            apply(EditorAction.MoveItemsBetweenLayers(affectedIds, layer.id, replacementId))
        }
        _layers.value = survivors
        if (_activeLayerId.value == layer.id) _activeLayerId.value = replacementId
    }

    private fun updateLayer(id: String, mutate: (NoteLayer) -> NoteLayer) {
        _layers.value = _layers.value.map { if (it.id == id) mutate(it) else it }
    }

    /** Layer id new strokes / shapes should inherit. Falls back to "no layer". */
    private fun layerIdForNewItem(): String? = _activeLayerId.value

    // ── Frames (sub-phase 8.1) ───────────────────────────────────────────

    private val _frames = MutableStateFlow<List<NoteFrame>>(emptyList())
    val frames: StateFlow<List<NoteFrame>> = _frames.asStateFlow()

    /** Sub-phase 8.1 — id of the frame the user most recently created or tapped. */
    private val _currentFrameId = MutableStateFlow<String?>(null)
    val currentFrameId: StateFlow<String?> = _currentFrameId.asStateFlow()

    /** Sub-phase 8.2 — navigator open / closed. */
    private val _frameNavigatorOpen = MutableStateFlow(false)
    val frameNavigatorOpen: StateFlow<Boolean> = _frameNavigatorOpen.asStateFlow()

    fun toggleFrameNavigator() { _frameNavigatorOpen.value = !_frameNavigatorOpen.value }
    fun closeFrameNavigator() { _frameNavigatorOpen.value = false }

    fun onFrameDrawn(bounds: FloatArray) {
        if (bounds.size < 4) return
        val current = _frames.value
        val nextOrdinal = (current.maxOfOrNull { it.ordinal } ?: -1) + 1
        val frame = NoteFrame(
            noteId = resolvedNoteId,
            name = "Frame ${nextOrdinal + 1}",
            minX = bounds[0],
            minY = bounds[1],
            maxX = bounds[2],
            maxY = bounds[3],
            ordinal = nextOrdinal,
        )
        applyFrameMutation("Add frame", before = current, after = current + frame)
        _currentFrameId.value = frame.id
    }

    fun onFrameTap(worldX: Float, worldY: Float) {
        // Most recently added (highest ordinal) wins for overlapping frames.
        val hit = _frames.value
            .filter { worldX in it.minX..it.maxX && worldY in it.minY..it.maxY }
            .maxByOrNull { it.ordinal }
        _currentFrameId.value = hit?.id
    }

    fun renameFrame(frameId: String, newName: String) {
        val before = _frames.value
        val sanitized = newName.trim().ifBlank { "Frame" }
        val after = before.map { if (it.id == frameId) it.copy(name = sanitized) else it }
        if (after == before) return
        applyFrameMutation("Rename frame", before, after)
    }

    fun deleteFrame(frameId: String) {
        val before = _frames.value
        val after = before.filterNot { it.id == frameId }
        if (after.size == before.size) return
        applyFrameMutation("Delete frame", before, after)
        if (_currentFrameId.value == frameId) _currentFrameId.value = null
    }

    fun updateFrameBounds(frameId: String, bounds: FloatArray) {
        if (bounds.size < 4) return
        val before = _frames.value
        val after = before.map {
            if (it.id == frameId) it.copy(
                minX = bounds[0], minY = bounds[1], maxX = bounds[2], maxY = bounds[3],
            ) else it
        }
        if (after == before) return
        applyFrameMutation("Resize frame", before, after)
    }

    fun reorderFrames(orderedIds: List<String>) {
        val before = _frames.value
        val byId = before.associateBy { it.id }
        val after = orderedIds.mapIndexedNotNull { i, id ->
            byId[id]?.copy(ordinal = i)
        }
        // Preserve any frames missing from the order list (defensive — caller
        // should always supply the full set).
        val seen = orderedIds.toHashSet()
        val survivors = after + before.filterNot { it.id in seen }
        if (survivors == before) return
        applyFrameMutation("Reorder frames", before, survivors)
    }

    fun selectFrame(frameId: String?) {
        _currentFrameId.value = frameId
    }

    /**
     * Common path for every frame mutation: record the before / after into a
     * `FrameMutation` undo entry and update the live state. Frame ops don't
     * touch the item list so we bypass [apply] and update the state directly,
     * then push the action onto the past stack.
     */
    private fun applyFrameMutation(
        description: String,
        before: List<NoteFrame>,
        after: List<NoteFrame>,
    ) {
        _frames.value = after
        apply(EditorAction.FrameMutation(description, before, after))
    }

    /** Returns the world-bounds of the currently-selected frame, or null. */
    fun currentFrameBounds(): FloatArray? {
        val id = _currentFrameId.value ?: return null
        val frame = _frames.value.firstOrNull { it.id == id } ?: return null
        return frame.bounds()
    }

    // ── Notebook mode (sub-phase 9.1 / 9.2) ──────────────────────────────
    //
    // A notebook owns this note (one note per notebook in 9.1). When
    // `note.notebookId` is non-null, the editor adds a page rail and the
    // "Add page" affordance, and pins the active frame to the page the
    // user most recently interacted with.

    private val _notebook = MutableStateFlow<com.aichat.sandbox.data.model.Notebook?>(null)
    val notebook: StateFlow<com.aichat.sandbox.data.model.Notebook?> = _notebook.asStateFlow()

    private val _pageRailOpen = MutableStateFlow(false)
    val pageRailOpen: StateFlow<Boolean> = _pageRailOpen.asStateFlow()

    fun togglePageRail() { _pageRailOpen.value = !_pageRailOpen.value }
    fun closePageRail() { _pageRailOpen.value = false }

    /**
     * Phase 9.2 — append a new page below the current last frame, using
     * the notebook's pinned page size. Sets the new frame as current so
     * the editor flies to it on the next viewport sync.
     */
    fun addNotebookPage() {
        val book = _notebook.value ?: return
        val current = _frames.value
        val lastMaxY = current.maxOfOrNull { it.maxY } ?: 0f
        val gutter = com.aichat.sandbox.data.model.Notebook.PAGE_GUTTER
        val nextOrdinal = (current.maxOfOrNull { it.ordinal } ?: -1) + 1
        val minY = if (current.isEmpty()) 0f else lastMaxY + gutter
        val frame = NoteFrame(
            noteId = resolvedNoteId,
            name = "Page ${nextOrdinal + 1}",
            minX = 0f,
            minY = minY,
            maxX = book.pageWidth,
            maxY = minY + book.pageHeight,
            ordinal = nextOrdinal,
        )
        applyFrameMutation("Add page", before = current, after = current + frame)
        _currentFrameId.value = frame.id
        viewModelScope.launch { notebookRepository.touchUpdatedAt(book.id) }
    }

    // ── Audio-synced ink (sub-phase 9.4) ─────────────────────────────────
    //
    // Recording state, the per-note audio list, and the player are all
    // surfaced as flows so the bottom playback bar can drive UI without
    // additional plumbing. The recorder's monotonic anchor
    // (`recordingStartedAt`) is exposed via [DrawingSurface] (set on the
    // surface when recording starts) so each stroke sample lands with a
    // `t = sampleTime - recordingStartedAt`.

    val audioClips: StateFlow<List<com.aichat.sandbox.data.model.NoteAudio>> =
        audioRepository.observeAudio(resolvedNoteId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    private val _isRecordingAudio = MutableStateFlow(false)
    val isRecordingAudio: StateFlow<Boolean> = _isRecordingAudio.asStateFlow()

    /** Monotonic anchor for the active recording (or 0 when not recording). */
    private val _recordingStartedAt = MutableStateFlow(0L)
    val recordingStartedAt: StateFlow<Long> = _recordingStartedAt.asStateFlow()

    fun startAudioRecording() {
        if (_isRecordingAudio.value) return
        try {
            val handle = audioRecorder.start()
            _isRecordingAudio.value = true
            _recordingStartedAt.value = handle.startedAt
        } catch (t: Throwable) {
            Log.w("AudioRecording", "start failed", t)
        }
    }

    fun stopAudioRecording() {
        if (!_isRecordingAudio.value) return
        val completed = audioRecorder.stop()
        _isRecordingAudio.value = false
        _recordingStartedAt.value = 0L
        if (completed == null) return
        viewModelScope.launch {
            audioRepository.insertAudio(
                com.aichat.sandbox.data.model.NoteAudio(
                    noteId = resolvedNoteId,
                    filePath = completed.filePath,
                    durationMs = completed.durationMs,
                    recordingStartedAt = completed.recordingStartedAt,
                )
            )
        }
    }

    fun deleteAudioClip(clipId: String) {
        viewModelScope.launch {
            // If the player is currently playing this clip, release first
            // so the MediaPlayer doesn't hold a handle to the deleted file.
            if (audioPlayer.activeClip.value != null) audioPlayer.release()
            audioRepository.deleteAudio(clipId)
        }
    }

    fun playAudioClip(clip: com.aichat.sandbox.data.model.NoteAudio) {
        audioPlayer.play(clip.filePath)
    }

    fun pauseAudio() = audioPlayer.pause()
    fun resumeAudio() = audioPlayer.resume()
    fun seekAudio(positionMs: Long) = audioPlayer.seekTo(positionMs)

    override fun onCleared() {
        super.onCleared()
        if (_isRecordingAudio.value) audioRecorder.abort()
        audioPlayer.release()
    }

    // ── Stamps (sub-phase 8.3) ───────────────────────────────────────────

    val stamps: StateFlow<List<Stamp>> = stampRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    private val _stampDrawerOpen = MutableStateFlow(false)
    val stampDrawerOpen: StateFlow<Boolean> = _stampDrawerOpen.asStateFlow()

    fun openStampDrawer() { _stampDrawerOpen.value = true }
    fun closeStampDrawer() { _stampDrawerOpen.value = false }

    /**
     * Sub-phase 8.3 — save the current selection as a reusable stamp.
     * Captures a 256 px thumbnail and serialises the selection via
     * [com.aichat.sandbox.data.notes.VectorCanvasJson]. Fire-and-forget;
     * the drawer's [stamps] flow surfaces the new entry once persisted.
     */
    fun saveSelectionAsStamp(name: String) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val selected = items.filter { it.id in ids }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val bounds = com.aichat.sandbox.data.notes.NoteRasterizer.computeBounds(selected)
                ?: return@launch
            val thumbnail = com.aichat.sandbox.data.notes.NoteRasterizer.render(
                items = selected,
                bounds = bounds,
                maxEdgePx = STAMP_THUMBNAIL_MAX_EDGE_PX,
                backgroundStyle = com.aichat.sandbox.ui.components.notes.BackgroundLayer.STYLE_PLAIN,
            )
            try {
                val payloadJson = com.aichat.sandbox.data.notes.StampPayloadCodec.encode(
                    items = selected,
                    bounds = bounds,
                )
                stampRepository.saveStamp(
                    name = name,
                    thumbnail = thumbnail,
                    payloadJson = payloadJson,
                )
            } finally {
                thumbnail.recycle()
            }
        }
    }

    /**
     * Sub-phase 8.3 — insert [stampId]'s items at [centerWorldX], [centerWorldY].
     * Each item gets a fresh UUID so re-inserting the same stamp produces
     * independent copies, then a translation centres the union bounds on
     * the supplied point. Drops onto the active layer.
     */
    fun insertStamp(stampId: String, centerWorldX: Float, centerWorldY: Float) {
        viewModelScope.launch {
            val stamp = stampRepository.getStamp(stampId) ?: return@launch
            val parsed = com.aichat.sandbox.data.notes.StampPayloadCodec.parse(stamp.payloadJson)
                ?: return@launch
            // Recentre bounds onto the supplied world point.
            val cx = (parsed.bounds[0] + parsed.bounds[2]) * 0.5f
            val cy = (parsed.bounds[1] + parsed.bounds[3]) * 0.5f
            val dx = centerWorldX - cx
            val dy = centerWorldY - cy
            val shift = com.aichat.sandbox.ui.components.notes.StrokeTransform.translation(dx, dy)
            val translated = parsed.items.map { item ->
                rebuildStampItem(item, shift)
            }
            if (translated.isEmpty()) return@launch
            apply(EditorAction.AddItems(translated))
            stampRepository.touchLastUsed(stampId)
            closeStampDrawer()
        }
    }

    private fun rebuildStampItem(
        item: NoteItem,
        shift: FloatArray,
    ): NoteItem {
        val newPayload = when (item.kind) {
            STROKE_KIND -> {
                val samples = StrokeCodec.decode(item.payload)
                StrokeCodec.encode(
                    com.aichat.sandbox.ui.components.notes.StrokeTransform.applyToSamples(shift, samples)
                )
            }
            TextItemCodec.KIND -> {
                val decoded = TextItemCodec.decode(item.payload)
                val newMatrix = com.aichat.sandbox.ui.components.notes.StrokeTransform.multiply(shift, decoded.matrix)
                TextItemCodec.encode(TextItemCodec.withMatrix(decoded, newMatrix))
            }
            Shape.KIND -> {
                val decoded = ShapeCodec.decode(item.payload)
                val transformed = ShapeCodec.transform(decoded.shape, shift)
                ShapeCodec.encode(transformed, decoded.fillArgb)
            }
            NoteItem.KIND_IMAGE -> {
                val payload = ImageItemCodec.decode(item.payload)
                ImageItemCodec.encode(ImageItemCodec.transform(payload, shift))
            }
            else -> item.payload.copyOf()
        }
        return item.copy(
            id = UUID.randomUUID().toString(),
            noteId = resolvedNoteId,
            zIndex = zIndexFor(item.tool),
            payload = newPayload,
            layerId = layerIdForNewItem(),
        )
    }

    fun renameStamp(stampId: String, newName: String) {
        viewModelScope.launch { stampRepository.rename(stampId, newName) }
    }

    fun deleteStamp(stampId: String) {
        viewModelScope.launch { stampRepository.delete(stampId) }
    }

    // ── Favorites bar (sub-phase 8.4) ────────────────────────────────────
    //
    // The bar's storage lives in `FavoritesStore` (DataStore-backed). The
    // VM surfaces the live slot list and a single `applyFavorite` entry
    // point that resolves the preset, mutates the palette / active preset,
    // and falls back to a no-op when the slot is empty or the linked
    // preset has been deleted.

    val favorites: StateFlow<List<com.aichat.sandbox.data.notes.FavoriteSlot>> =
        favoritesStore.observe().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = com.aichat.sandbox.data.notes.FavoritesStore.DEFAULT_SLOTS,
        )

    fun applyFavorite(slotIndex: Int) {
        val slot = favorites.value.firstOrNull { it.index == slotIndex } ?: return
        val presetId = slot.brushPresetId ?: return
        val preset = brushPresetList.value.firstOrNull { it.id == presetId } ?: return
        // Map the preset's tool back to the palette enum.
        val tool = Tool.entries.firstOrNull { it.id == preset.tool } ?: return
        palette.select(tool)
        applyBrushPreset(preset)
    }

    fun assignFavoriteFromActiveBrush(slotIndex: Int) {
        val activePreset = _activeBrushPreset.value ?: return
        viewModelScope.launch {
            favoritesStore.assignSlot(slotIndex, activePreset.id)
        }
    }

    fun clearFavoriteSlot(slotIndex: Int) {
        viewModelScope.launch {
            favoritesStore.assignSlot(slotIndex, null)
        }
    }

    // ── Brush presets (sub-phase 6.5) ────────────────────────────────────

    val brushPresetList: StateFlow<List<BrushPreset>> = brushPresets.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * Active brush preset per ink tool. Defaults to the first app-scope
     * preset matching the tool when the user hasn't explicitly picked one.
     * Slider edits in the BrushSheet replace this with a live-edited
     * (un-persisted) copy until the user saves a new preset.
     */
    private val _activeBrushPreset = MutableStateFlow<BrushPreset?>(null)
    val activeBrushPreset: StateFlow<BrushPreset?> = _activeBrushPreset.asStateFlow()

    fun applyBrushPreset(preset: BrushPreset) {
        _activeBrushPreset.value = preset
        palette.setColor(palette.lastInkTool, preset.colorArgb)
        palette.setWidth(palette.lastInkTool, preset.baseWidthPx)
    }

    /** Live BrushSheet slider edit. Doesn't persist; resets when the user picks another preset. */
    fun setLiveBrushEdit(preset: BrushPreset) {
        _activeBrushPreset.value = preset
        palette.setColor(palette.lastInkTool, preset.colorArgb)
        palette.setWidth(palette.lastInkTool, preset.baseWidthPx)
    }

    fun setActiveTextureId(textureId: String) {
        val current = _activeBrushPreset.value ?: return
        _activeBrushPreset.value = current.copy(textureId = textureId)
    }

    /** Save the current preset's slider state as a new user-scope preset. */
    fun saveActiveAsUserPreset(name: String) {
        val current = _activeBrushPreset.value ?: return
        viewModelScope.launch {
            val saved = brushPresets.saveUserPreset(
                current.copy(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    ownerScope = BrushPreset.SCOPE_USER,
                )
            )
            _activeBrushPreset.value = saved
        }
    }

    private fun seedActiveBrushPresetIfNeeded(all: List<BrushPreset>) {
        if (_activeBrushPreset.value != null) return
        val toolId = palette.lastInkTool.id
        _activeBrushPreset.value = all.firstOrNull { it.tool == toolId }
    }

    // ── Image insert (sub-phase 6.7) ─────────────────────────────────────

    /**
     * Insert an image from [sourceUri] at the supplied viewport-centre
     * coordinates. Caller is the editor screen, which has access to the
     * `ViewportController` for world-coord mapping. Returns silently on
     * decode / I/O failure (the picker callback can't surface errors).
     */
    fun insertImageFromUri(sourceUri: Uri, centerWorldX: Float, centerWorldY: Float) {
        viewModelScope.launch {
            val imported = noteImageStore.importFromUri(sourceUri) ?: return@launch
            // Scale to fit a 320-world-unit longest edge — large enough to
            // see, small enough that a paste doesn't dominate the viewport.
            val target = IMAGE_INSERT_TARGET_WORLD
            val scale = target / maxOf(imported.naturalWidth, imported.naturalHeight)
            val w = imported.naturalWidth * scale
            val h = imported.naturalHeight * scale
            val payload = ImageItemCodec.ImagePayload(
                relativePath = imported.relativePath,
                naturalWidth = imported.naturalWidth,
                naturalHeight = imported.naturalHeight,
                minX = centerWorldX - w / 2f,
                minY = centerWorldY - h / 2f,
                maxX = centerWorldX + w / 2f,
                maxY = centerWorldY + h / 2f,
            )
            val item = NoteItem(
                noteId = resolvedNoteId,
                zIndex = nextInkZIndex++,
                kind = NoteItem.KIND_IMAGE,
                tool = null,
                colorArgb = 0,
                baseWidthPx = 0f,
                payload = ImageItemCodec.encode(payload),
                layerId = layerIdForNewItem(),
            )
            apply(EditorAction.AddItems(listOf(item)))
        }
    }

    /**
     * Existing chats, newest first — feeds the sub-phase 4.3
     * [SendToChatSheet] picker. Sourced from the same DAO query that backs
     * the chat-list tab so ordering stays consistent.
     */
    val chats: StateFlow<List<Chat>> = chatRepository.getAllChats()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    /**
     * Sub-phase 4.3 send-to-chat sheet state. `null` means hidden; non-null
     * captures whether the picker was opened from the editor's share menu
     * (whole-note PNG + OCR snippet) or from an AI reply (selection PNG +
     * reply text), so the chat-pick callback knows how to build the
     * payload.
     */
    private val _sendToChatMode = MutableStateFlow<SendToChatMode?>(null)
    val sendToChatMode: StateFlow<SendToChatMode?> = _sendToChatMode.asStateFlow()

    init {
        if (entrySource != null || entryStylus) {
            Log.d("NotesEntry", "source=$entrySource stylus=$entryStylus")
        }
        if (entryStylus) {
            // Already the default today (sub-phase 1.6), but pinning it here
            // keeps quick-capture entry points anchored to ink even if the
            // palette default ever changes.
            palette.select(Tool.PEN)
        }
        // Seed the sheet's active model from the user's chat preferences so
        // the first ask hits whatever the user last selected for chat. The
        // header is read-only in 2.6 — the in-sheet model picker lands in 2.8.
        viewModelScope.launch {
            val model = preferencesManager.defaultModel.first()
            _aiSheetState.update { current ->
                if (current.activeModelId.isEmpty()) current.copy(activeModelId = model)
                else current
            }
        }
        if (routeArg != NOTE_ID_NEW) {
            viewModelScope.launch {
                val loadedNote = repository.getNote(routeArg)
                if (loadedNote != null) _note.value = loadedNote
                val loaded = repository.getItems(routeArg)
                items.clear()
                items.addAll(loaded)
                refreshZIndexCounters(loaded)
                // Sub-phase 5.2: rehydrate undo / redo. Malformed payloads
                // land as empty stacks (the codec already logged a warning).
                val decoded = EditorActionCodec.decode(loadedNote?.undoLogJson)
                past.clear()
                future.clear()
                past.addAll(decoded.past)
                future.addAll(decoded.future)
                updateUndoRedoState()
                // Sub-phase 6.4 — load the per-note layer list. If none
                // exist (e.g. migration backfill skipped this note because
                // there were no items at migration time, but the user has
                // since added some), synthesise a single "Ink" layer so
                // every new stroke lands somewhere predictable.
                val loadedLayers = repository.getLayers(routeArg)
                if (loadedLayers.isEmpty()) {
                    addLayer("Ink")
                } else {
                    _layers.value = loadedLayers
                    _activeLayerId.value = loadedLayers.maxByOrNull { it.ordinal }?.id
                }
                // Sub-phase 8.1 — frame list comes from its own table.
                _frames.value = repository.getFrames(routeArg)
                // Sub-phase 9.1 — if this note belongs to a notebook,
                // grab the header so the editor can render notebook UI
                // (page rail, "Add page", pinned page size).
                val notebookId = loadedNote?.notebookId
                if (notebookId != null) {
                    _notebook.value = notebookRepository.getNotebook(notebookId)
                }
            }
        } else {
            // Brand-new note: seed a default "Ink" layer so the first stroke
            // has somewhere to land. Layer is persisted at save() time.
            addLayer("Ink")
        }
        // Seed the active brush preset once presets stream in.
        viewModelScope.launch {
            brushPresetList.collect { all ->
                if (all.isNotEmpty()) seedActiveBrushPresetIfNeeded(all)
            }
        }
    }

    fun setTitle(title: String) {
        _note.update { it.copy(title = title) }
    }

    // ── Colour picker (sub-phase 5.3) ────────────────────────────────────

    fun openColorPicker() {
        if (!palette.selected.isInk) {
            // Eraser / lasso / text tools don't carry an ink colour; if the
            // user somehow triggered the picker (long-press on a stale
            // swatch, for instance) we fall back to PEN so the picked colour
            // lands somewhere visible.
            palette.select(Tool.PEN)
        }
        _colorPickerOpen.value = true
    }

    fun dismissColorPicker() {
        _colorPickerOpen.value = false
    }

    /**
     * Apply [colorArgb] to the active ink tool and record it on the recents
     * list. The picker stays responsible for parsing the colour; we just
     * propagate. Fire-and-forget for the recents write — a failed datastore
     * commit is non-fatal and would just mean the colour isn't pre-loaded
     * next time.
     */
    fun confirmColorPick(colorArgb: Int) {
        palette.setColor(palette.lastInkTool, colorArgb)
        _colorPickerOpen.value = false
        viewModelScope.launch {
            recentColorsStore.record(colorArgb)
        }
    }

    fun setBackgroundStyle(style: String) {
        if (_note.value.backgroundStyle == style) return
        _note.update { it.copy(backgroundStyle = style) }
    }

    /**
     * Append a freshly drawn item to the in-memory list. The DrawingSurface emits items
     * without a note id or zIndex; this method assigns both before storage.
     *
     * z-index policy: highlighter strokes sit in a negative range so they
     * always render *under* pen / pencil strokes regardless of insertion
     * order — the user's ink never gets obscured by a highlighter drawn later.
     */
    fun addItem(item: NoteItem) {
        val prepared = item.copy(
            noteId = resolvedNoteId,
            zIndex = zIndexFor(item.tool),
            layerId = item.layerId ?: layerIdForNewItem(),
        )
        apply(EditorAction.AddItems(listOf(prepared)))
    }

    /** Remove items matching [ids] (issued by the eraser swipe). */
    fun removeItems(ids: List<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val matched = items.filter { it.id in set }
        if (matched.isEmpty()) return
        apply(EditorAction.RemoveItems(matched))
    }

    /**
     * Run [action] against [items], record it on the undo log, and discard
     * any redo history (a new branch invalidates the old future).
     *
     * Must be invoked from the main thread — `SnapshotStateList` throws on
     * background mutation. Touch handlers in `DrawingSurface` already run on
     * the UI thread, so callers don't need to dispatch.
     */
    fun apply(action: EditorAction) {
        action.applyTo(items)
        past.addLast(action)
        while (past.size > UNDO_STACK_CAP) past.removeFirst()
        future.clear()
        updateUndoRedoState()
    }

    fun undo() {
        val action = past.removeLastOrNull() ?: return
        action.invert().applyTo(items)
        // Sub-phase 8.1 — frame mutations live outside the item list.
        // Restore the frame state from the action data on undo.
        if (action is EditorAction.FrameMutation) {
            _frames.value = action.before
        }
        future.addLast(action)
        updateUndoRedoState()
    }

    fun redo() {
        val action = future.removeLastOrNull() ?: return
        action.applyTo(items)
        if (action is EditorAction.FrameMutation) {
            _frames.value = action.after
        }
        past.addLast(action)
        updateUndoRedoState()
    }

    private fun updateUndoRedoState() {
        _canUndo.value = past.isNotEmpty()
        _canRedo.value = future.isNotEmpty()
    }

    // ── Lasso / selection / clipboard ops (sub-phase 1.8) ────────────────

    /**
     * Hit-test the lasso polygon against every committed stroke and stash
     * the matching ids as the active selection. Polygon is in world coords
     * (see `LassoController`); a "nothing was caught" loop clears the
     * selection without disrupting the rest of the state.
     *
     * After the selection lands the active tool snaps back to the user's
     * last ink tool so the next stylus stroke draws normally rather than
     * starting a second lasso right on top of the highlight.
     */
    fun onLassoCompleted(polygon: FloatArray) {
        val vertexCount = polygon.size / LassoController.FLOATS_PER_VERTEX
        val polyBounds = LassoController.polygonBounds(polygon, vertexCount)
        if (polyBounds == null) {
            clearSelection()
            return
        }
        val matched = ArrayList<String>()
        val bounds = ArrayList<FloatArray>()
        for (item in items) {
            val hit: Boolean
            val itemBounds: FloatArray
            when (item.kind) {
                STROKE_KIND -> {
                    val samples = StrokeCodec.decode(item.payload)
                    val sampleCount = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                    val b = HitTest.boundsOf(samples, sampleCount) ?: continue
                    itemBounds = b
                    hit = LassoController.strokeIntersectsPolygon(
                        samples, sampleCount, b, polygon, vertexCount, polyBounds,
                    )
                }
                TextItemCodec.KIND -> {
                    val b = TextItemRenderer.boundsOf(item) ?: continue
                    itemBounds = b
                    hit = TextItemRenderer.intersectsPolygon(
                        item, polygon, vertexCount, polyBounds,
                    )
                }
                Shape.KIND -> {
                    val decoded = ShapeCodec.decode(item.payload)
                    val b = ShapeCodec.boundsOf(decoded.shape) ?: continue
                    itemBounds = b
                    hit = HitTest.shapeIntersectsPolygon(
                        decoded.shape, polygon, vertexCount, polyBounds,
                    )
                }
                NoteItem.KIND_IMAGE -> {
                    val payload = ImageItemCodec.decode(item.payload)
                    val b = ImageItemCodec.boundsOf(payload)
                    itemBounds = b
                    // Use the image's AABB intersected with the polygon's
                    // AABB as a cheap selector — fine-grained polygon-vs-rect
                    // tests would be needed for tight lassos around a corner;
                    // good enough for the v1 UX.
                    hit = b[2] >= polyBounds[0] && b[0] <= polyBounds[2] &&
                        b[3] >= polyBounds[1] && b[1] <= polyBounds[3]
                }
                else -> continue
            }
            // Sub-phase 6.4 — locked-layer items don't fall into a lasso
            // selection. The user expects the layer-panel lock chip to make
            // its items completely inert.
            val layer = _layers.value.firstOrNull { it.id == item.layerId }
            if (layer != null && layer.locked) continue
            if (hit) {
                matched.add(item.id)
                bounds.add(itemBounds)
            }
        }
        if (matched.isEmpty()) {
            clearSelection()
            return
        }
        _selection.value = matched.toHashSet()
        _selectionWorldBounds.value =
            LassoController.unionBounds(bounds)
        _selectionMatrix.value = StrokeTransform.IDENTITY
        // Drop back to the last ink tool so consecutive strokes don't reopen
        // a lasso on top of the existing selection.
        palette.select(palette.lastInkTool)
    }

    fun clearSelection() {
        if (_selection.value.isEmpty()) return
        _selection.value = emptySet()
        _selectionWorldBounds.value = null
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    /**
     * Update the in-flight transform shown by the selection overlay. Caller
     * is the Compose handle gesture; the matrix lives in world coords.
     */
    fun updateSelectionTransform(matrix: FloatArray) {
        if (_selection.value.isEmpty()) return
        _selectionMatrix.value = matrix.copyOf()
    }

    /**
     * Bake the current [selectionMatrix] into every selected item's payload
     * and record the action on the undo log. Identity matrices are dropped
     * silently so a tap that doesn't actually move the selection doesn't
     * pollute the undo history.
     */
    fun bakeSelectionTransform() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val matrix = _selectionMatrix.value
        _selectionMatrix.value = StrokeTransform.IDENTITY
        if (StrokeTransform.isIdentity(matrix)) return
        apply(EditorAction.TransformItems(ids.toList(), matrix))
        _selectionWorldBounds.value = recomputeSelectionBounds()
    }

    fun duplicateSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val originals = items.filter { it.id in ids }
        if (originals.isEmpty()) return
        val copies = originals.map { duplicate(it, paste = false) }
        apply(EditorAction.AddItems(copies))
        // Replace the selection with the duplicates so the user can move them
        // independently of the originals on the next gesture.
        _selection.value = copies.mapTo(HashSet(copies.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    fun deleteSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val matched = items.filter { it.id in ids }
        if (matched.isEmpty()) return
        apply(EditorAction.RemoveItems(matched))
        clearSelection()
    }

    fun copySelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val matched = items.filter { it.id in ids }
        if (matched.isEmpty()) return
        NoteClipboard.put(matched)
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    /**
     * Paste the clipboard contents into the current note. Items are re-IDed,
     * re-anchored to this note, and given a small visual offset so they
     * don't sit directly on top of the source strokes.
     */
    fun pasteFromClipboard() {
        if (NoteClipboard.isEmpty()) return
        val pasted = NoteClipboard.peek().map { duplicate(it, paste = true) }
        if (pasted.isEmpty()) return
        apply(EditorAction.AddItems(pasted))
        _selection.value = pasted.mapTo(HashSet(pasted.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    fun hasClipboardContent(): Boolean = !NoteClipboard.isEmpty()

    // ── Text editor (sub-phase 1.9) ──────────────────────────────────────

    /**
     * Surface dispatched a tap with the TEXT tool active. If the tap lands on
     * an existing text item we open it for editing; otherwise we start a new
     * draft at the tapped world point.
     *
     * "Hit" is a simple bounding-box check — text bounds account for the
     * item's stored affine so rotated / scaled labels are caught correctly.
     * The most-recently-painted (highest zIndex) match wins so overlapping
     * labels are routed predictably.
     */
    fun onTextToolTap(worldX: Float, worldY: Float) {
        // Commit whatever was being edited before we open a new target —
        // otherwise the user's previous draft would silently disappear.
        commitTextEdit()
        val hit = findTopmostTextItemAt(worldX, worldY)
        if (hit != null) {
            val decoded = TextItemCodec.decode(hit.payload)
            textEditDraftBody = decoded.body
            _textEditTarget.value = TextEditTarget.Existing(
                itemId = hit.id,
                initialBody = decoded.body,
                worldX = decoded.matrix[2],
                worldY = decoded.matrix[5],
                fontSize = decoded.fontSize,
                alignment = decoded.alignment,
            )
            // Tapping a text item under TEXT tool implies edit, not select —
            // clear the lasso selection so the overlay doesn't get in the way.
            clearSelection()
        } else {
            textEditDraftBody = ""
            _textEditTarget.value = TextEditTarget.NewAt(
                worldX = worldX,
                worldY = worldY,
                fontSize = TextItemCodec.DEFAULT_FONT_SIZE_PX,
                alignment = TextItemCodec.ALIGN_LEFT,
            )
            clearSelection()
        }
    }

    /** Live body update from the Compose `BasicTextField`. */
    fun onTextEditBodyChanged(body: String) {
        textEditDraftBody = body
    }

    /**
     * Commit the active text edit, if any. Idempotent — safe to call when no
     * target is active. Behaviour by mode:
     *
     *  - **NewAt** with empty body → cancel (no item ever materialises).
     *  - **NewAt** with non-empty body → `AddItems(textItem)` lands one new item.
     *  - **Existing** with empty body → `RemoveItems(item)`.
     *  - **Existing** with body changed → `UpdateText(id, old, new)`.
     *  - **Existing** unchanged → no action; the overlay just closes.
     */
    fun commitTextEdit() {
        val target = _textEditTarget.value ?: return
        val body = textEditDraftBody
        _textEditTarget.value = null
        textEditDraftBody = ""
        when (target) {
            is TextEditTarget.NewAt -> {
                if (body.isEmpty()) return
                val payload = TextItemCodec.newAt(
                    worldX = target.worldX,
                    worldY = target.worldY,
                    body = body,
                    fontSize = target.fontSize,
                    alignment = target.alignment,
                )
                val item = NoteItem(
                    noteId = resolvedNoteId,
                    // Text items share the "ink" z-tier so they paint above
                    // highlighter strokes, consistent with how a real label
                    // sits on top of yellow marker.
                    zIndex = nextInkZIndex++,
                    kind = TextItemCodec.KIND,
                    tool = null,
                    colorArgb = TEXT_DEFAULT_COLOR,
                    baseWidthPx = 0f,
                    payload = TextItemCodec.encode(payload),
                )
                apply(EditorAction.AddItems(listOf(item)))
            }
            is TextEditTarget.Existing -> {
                val item = items.firstOrNull { it.id == target.itemId } ?: return
                if (body.isEmpty()) {
                    apply(EditorAction.RemoveItems(listOf(item)))
                    return
                }
                if (body == target.initialBody) return
                apply(EditorAction.UpdateText(target.itemId, target.initialBody, body))
            }
        }
    }

    /**
     * Drop the active edit without persisting changes. New drafts vanish;
     * existing items keep whatever body was last committed to the DB. Used
     * when the editor is dismissed without an explicit commit path.
     */
    fun cancelTextEdit() {
        if (_textEditTarget.value == null) return
        _textEditTarget.value = null
        textEditDraftBody = ""
    }

    // ── AI side sheet (sub-phase 2.6) ────────────────────────────────────

    /**
     * Open the sheet. [selection] is captured by reference here so the scope
     * chip stays accurate even if the user clears the canvas selection while
     * the sheet is open. `null` selection means "ask about the whole note".
     */
    fun openAiSheet(selection: List<NoteItem>? = null) {
        _aiSheetState.update { current ->
            current.copy(
                isOpen = true,
                pendingSelection = selection?.toList(),
            )
        }
    }

    /**
     * Lasso "Ask" entry point (sub-phase 2.7). Snapshots the items matching
     * the current selection into the sheet's frozen scope so subsequent
     * background changes to the canvas selection don't drift the scope chip.
     * Falls back to whole-note scope when nothing is selected (e.g. defensive
     * call after a lasso cleared itself).
     */
    fun openAiSheetForSelection() {
        val ids = _selection.value
        val snapshot = if (ids.isEmpty()) null else items.filter { it.id in ids }
        openAiSheet(snapshot)
    }

    /**
     * Drop the frozen selection scope without closing the sheet. The chip
     * row's "Whole note" pill calls this so the user can pivot the in-flight
     * conversation from "these strokes" to "this whole note" without losing
     * prior turns.
     */
    fun clearAiSheetScope() {
        if (_aiSheetState.value.pendingSelection == null) return
        _aiSheetState.update { it.copy(pendingSelection = null) }
    }

    /**
     * Hide the sheet without losing the conversation. Reopening restores the
     * turn list so the user can resume reading. Streaming jobs keep running
     * — closing the sheet is not the same as cancelling.
     */
    fun closeAiSheet() {
        _aiSheetState.update { it.copy(isOpen = false) }
    }

    /** Compose `OutlinedTextField` callback. */
    fun onAiInputChanged(text: String) {
        _aiSheetState.update { it.copy(inputText = text) }
    }

    /**
     * Fire a new ask using the current input text and captured selection. The
     * input field clears immediately so the user can queue the next prompt
     * while the reply streams in — though Send is disabled while any turn is
     * still in flight (see [AiSideSheetState.isStreaming]).
     */
    fun submitAiPrompt() {
        val snapshot = _aiSheetState.value
        val prompt = snapshot.inputText.trim()
        if (prompt.isEmpty()) return
        if (snapshot.isStreaming) return
        val turnId = UUID.randomUUID().toString()
        val selection = snapshot.pendingSelection
        val summary = selection?.let { summarizeSelection(it) }
        val turn = AskTurn(
            id = turnId,
            prompt = prompt,
            selectionSummary = summary,
            replyBuffer = "",
            state = TurnState.Streaming,
        )
        _aiSheetState.update { current ->
            current.copy(
                turns = current.turns + turn,
                inputText = "",
            )
        }
        streamingJobs[turnId] = viewModelScope.launch {
            runStream(turnId = turnId, prompt = prompt, selection = selection)
        }
    }

    /**
     * Fire a canned prompt by enum (sub-phase 2.7). [CannedPrompt.CONVERT_TO_TEXT]
     * routes through the OCR fast path; everything else maps to a
     * one-tap template and goes through the standard ask pipeline.
     *
     * The convert-to-text path silently no-ops when there are no strokes
     * in scope; the UI is expected to gate the chip in that case, but the
     * guard here keeps the VM honest if a caller forgets.
     */
    fun submitCannedPrompt(canned: CannedPrompt) {
        if (canned == CannedPrompt.CONVERT_TO_TEXT) {
            launchConvertToText()
            return
        }
        if (_aiSheetState.value.isStreaming) return
        _aiSheetState.update { it.copy(inputText = canned.template) }
        submitAiPrompt()
    }

    /**
     * Convert-to-text fast path (sub-phase 2.7).
     *
     * Bypasses [NoteAiService] entirely — handwriting OCR runs on the
     * in-scope strokes and the recognized text is dropped into the
     * conversation as a finished `Done` turn marked [AskTurn.isConvertResult],
     * which surfaces a preview "Insert as text box" action on the bubble.
     * Empty / unrecognizable input lands as an `Error` turn rather than a
     * blank `Done` so the user sees what happened.
     *
     * Works offline (no network call). If no selection is active, the entire
     * note's strokes are used so the toolbar Convert can still recover the
     * full transcription.
     */
    fun launchConvertToText() {
        val snapshot = _aiSheetState.value
        if (snapshot.isStreaming) return
        val scope = snapshot.pendingSelection ?: items.toList()
        val strokes = scope.filter { it.kind == STROKE_KIND }
        val summary = snapshot.pendingSelection?.let { summarizeSelection(it) }
        val turnId = UUID.randomUUID().toString()
        val turn = AskTurn(
            id = turnId,
            prompt = CannedPrompt.CONVERT_TO_TEXT.label,
            selectionSummary = summary,
            replyBuffer = "",
            state = TurnState.Streaming,
            isConvertResult = true,
        )
        _aiSheetState.update { current ->
            current.copy(
                isOpen = true,
                turns = current.turns + turn,
                inputText = "",
            )
        }
        if (strokes.isEmpty()) {
            mutateTurn(turnId) { t -> t.copy(state = TurnState.Error(NO_HANDWRITING_MESSAGE)) }
            return
        }
        streamingJobs[turnId] = viewModelScope.launch {
            try {
                val result = handwritingOcr.recognize(strokes)
                val text = result.text
                if (text.isBlank()) {
                    mutateTurn(turnId) { t -> t.copy(state = TurnState.Error(NO_HANDWRITING_MESSAGE)) }
                } else {
                    mutateTurn(turnId) { t -> t.copy(replyBuffer = text, state = TurnState.Done) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                mutateTurn(turnId) { current ->
                    current.copy(state = TurnState.Error(t.message ?: "OCR failed"))
                }
            } finally {
                streamingJobs.remove(turnId)
            }
        }
    }

    /**
     * Convert-to-text fast path triggered from the lasso menu (sub-phase 2.7).
     * Opens the sheet (if it isn't already), freezes the current canvas
     * selection as the scope, and kicks the OCR job — all in one call so the
     * lasso button is a single tap.
     */
    fun launchConvertSelectionToText() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val snapshot = items.filter { it.id in ids }
        if (snapshot.isEmpty()) return
        // Capture the scope first so the running turn picks it up via state.
        openAiSheet(snapshot)
        launchConvertToText()
    }

    /**
     * Drop a `Done` Convert-to-text reply onto the canvas as a new text item.
     * Goes through [EditorAction.AddItems] so undo / redo round-trips. Sized
     * at the codec's default font and anchored at the centre of the frozen
     * selection bounds, or `(0, 0)` world when scope is the whole note.
     *
     * This is the sub-phase 2.7 preview of the broader reply-action row that
     * lands in 2.8; the general "Insert as text box" for arbitrary replies
     * arrives there.
     */
    fun insertConvertResultAsTextBox(turnId: String) {
        val state = _aiSheetState.value
        val turn = state.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.state !is TurnState.Done || !turn.isConvertResult) return
        val body = turn.replyBuffer
        if (body.isEmpty()) return
        val (worldX, worldY) = anchorPointForInsert(state.pendingSelection)
        val payload = TextItemCodec.newAt(
            worldX = worldX,
            worldY = worldY,
            body = body,
            fontSize = TextItemCodec.DEFAULT_FONT_SIZE_PX,
            alignment = TextItemCodec.ALIGN_LEFT,
        )
        val item = NoteItem(
            noteId = resolvedNoteId,
            zIndex = nextInkZIndex++,
            kind = TextItemCodec.KIND,
            tool = null,
            colorArgb = TEXT_DEFAULT_COLOR,
            baseWidthPx = 0f,
            payload = TextItemCodec.encode(payload),
        )
        apply(EditorAction.AddItems(listOf(item)))
        closeAiSheet()
    }

    /**
     * In-sheet model picker callback (sub-phase 2.8). Switching mid-
     * conversation affects subsequent turns only — existing turn replies
     * are immutable, which keeps the conversation log honest about which
     * model produced what.
     */
    fun setAiModelId(modelId: String) {
        if (modelId.isBlank()) return
        _aiSheetState.update { it.copy(activeModelId = modelId) }
    }

    /**
     * "Insert as text box" reply action (sub-phase 2.8). Drops the reply
     * onto the canvas as a new text item via [EditorAction.AddItems] so
     * undo / redo round-trips, then closes the sheet.
     *
     * Anchor preference: when the sheet was opened with a selection scope,
     * anchor at that selection's centre so the reply lands near the
     * strokes it was about. Otherwise anchor at the supplied viewport
     * centre (world coords) so the new text appears on-screen. Callers
     * pass viewport centre because the editor screen owns the viewport
     * controller — the VM has no direct access to pan/zoom state.
     *
     * Streaming turns are rejected (the action row is gated on Done in the
     * UI, but the guard keeps the VM honest); empty replies are dropped.
     */
    fun insertReplyAsTextBox(
        turnId: String,
        fallbackWorldX: Float,
        fallbackWorldY: Float,
    ) {
        val state = _aiSheetState.value
        val turn = state.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.state !is TurnState.Done) return
        val body = turn.replyBuffer
        if (body.isEmpty()) return
        val (worldX, worldY) = state.pendingSelection
            ?.takeIf { it.isNotEmpty() }
            ?.let { anchorPointForInsert(it) }
            ?: (fallbackWorldX to fallbackWorldY)
        val payload = TextItemCodec.newAt(
            worldX = worldX,
            worldY = worldY,
            body = body,
            fontSize = TextItemCodec.DEFAULT_FONT_SIZE_PX,
            alignment = TextItemCodec.ALIGN_LEFT,
        )
        val item = NoteItem(
            noteId = resolvedNoteId,
            zIndex = nextInkZIndex++,
            kind = TextItemCodec.KIND,
            tool = null,
            colorArgb = TEXT_DEFAULT_COLOR,
            baseWidthPx = 0f,
            payload = TextItemCodec.encode(payload),
        )
        apply(EditorAction.AddItems(listOf(item)))
        closeAiSheet()
    }

    /**
     * Open the sub-phase 4.3 chat picker for the editor's share menu. The
     * picker calls back with the chosen chat id (existing or fresh-via
     * "+ New chat"); see [finalizeSendToChat].
     */
    fun openSendNoteToChatPicker() {
        _sendToChatMode.value = SendToChatMode.ShareNote
    }

    /**
     * Open the sub-phase 4.3 chat picker for an AI side-sheet reply. Only
     * a Done, non-empty turn qualifies — Streaming/Error turns silently
     * no-op so the action row in the side sheet doesn't need defensive
     * gating beyond what it already does.
     */
    fun openSendReplyToChatPicker(turnId: String) {
        val turn = _aiSheetState.value.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.state !is TurnState.Done) return
        if (turn.replyBuffer.isEmpty()) return
        _sendToChatMode.value = SendToChatMode.AiReply(turnId)
    }

    /** Dismiss the picker without sending. Safe to call when already hidden. */
    fun dismissSendToChatPicker() {
        _sendToChatMode.value = null
    }

    /**
     * Resolve the picker selection into a navigation target (sub-phase 4.3).
     *
     * [chatIdOrNull] is the picked chat's id, or `null` to mint a fresh
     * one via [ChatRepository.createChat]. Renders the appropriate PNG
     * (whole-note for share-menu mode, selection-or-whole-note for AI-reply
     * mode), stashes it on [PendingDraftStore] keyed by the resolved chat
     * id, and returns that id so the screen can issue a single navigation.
     *
     * Closes the picker before doing any work so the sheet animates out
     * while the rasterizer runs on the IO dispatcher — the user perceives
     * the chat opening rather than a stalled sheet.
     *
     * Returns null when the picker isn't open (callers shouldn't hit this,
     * but the guard keeps the VM honest under racey UI events).
     */
    suspend fun finalizeSendToChat(chatIdOrNull: String?): String? {
        val mode = _sendToChatMode.value ?: return null
        _sendToChatMode.value = null
        // Save first so the rasterized PNG reflects the user's most-recent
        // strokes — same contract as [sharePng] / [sharePdf].
        commitTextEdit()
        save()
        val targetChatId = chatIdOrNull ?: chatRepository.createChat().id
        val (renderItems, draftText) = when (mode) {
            is SendToChatMode.ShareNote -> {
                val snippet = _note.value.ocrText
                    ?.take(OCR_SNIPPET_MAX_LEN)
                    ?.takeIf { it.isNotBlank() }
                items.toList() to snippet
            }
            is SendToChatMode.AiReply -> {
                val turn = _aiSheetState.value.turns.firstOrNull { it.id == mode.turnId }
                    ?: return null
                if (turn.state !is TurnState.Done) return null
                val body = turn.replyBuffer
                if (body.isEmpty()) return null
                val scope = _aiSheetState.value.pendingSelection
                    ?.takeIf { it.isNotEmpty() }
                    ?: items.toList()
                scope to body
            }
        }
        val uri = noteExporter.exportPng(note = _note.value, items = renderItems)
        PendingDraftStore.put(targetChatId, imageUri = uri, draftText = draftText)
        if (mode is SendToChatMode.AiReply) closeAiSheet()
        return targetChatId
    }

    private fun anchorPointForInsert(scope: List<NoteItem>?): Pair<Float, Float> {
        if (scope.isNullOrEmpty()) return 0f to 0f
        val rects = ArrayList<FloatArray>(scope.size)
        for (item in scope) {
            val rect = when (item.kind) {
                STROKE_KIND -> {
                    val samples = StrokeCodec.decode(item.payload)
                    val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                    HitTest.boundsOf(samples, count)
                }
                TextItemCodec.KIND -> TextItemRenderer.boundsOf(item)
                Shape.KIND -> ShapeCodec.boundsOf(ShapeCodec.decode(item.payload).shape)
                NoteItem.KIND_IMAGE -> ImageItemCodec.boundsOf(ImageItemCodec.decode(item.payload))
                else -> null
            }
            rect?.let { rects.add(it) }
        }
        val union = LassoController.unionBounds(rects) ?: return 0f to 0f
        // Anchor at the centre of the selection bounds; TextItemCodec.newAt
        // treats this as the text origin (top-left), so the resulting label
        // hangs off the centre of the recognized strokes — close enough for
        // a v1 preview, viewport-aware centring is 2.8 polish.
        val cx = (union[0] + union[2]) * 0.5f
        val cy = (union[1] + union[3]) * 0.5f
        return cx to cy
    }

    /**
     * Cancel every in-flight turn (in practice, at most one — Send is
     * disabled while streaming). Each cancelled turn flips to
     * [TurnState.Error] with a "Cancelled" message so the user sees what
     * happened rather than a silently-frozen stream.
     */
    fun cancelAiStreaming() {
        val jobs = streamingJobs.toMap()
        streamingJobs.clear()
        jobs.values.forEach { it.cancel() }
        _aiSheetState.update { current ->
            current.copy(
                turns = current.turns.map { turn ->
                    if (turn.state is TurnState.Streaming) {
                        turn.copy(state = TurnState.Error(CANCEL_MESSAGE))
                    } else turn
                }
            )
        }
    }

    /**
     * Sub-phase 7.5 — fire an AI EDIT request with the current selection.
     *
     * [description] is the short label that lands as the undo entry's
     * description (e.g. "AI Auto-shape"). [userPrompt] is the natural-
     * language brief sent to the model. Acts like [submitAiPrompt] in that
     * it surfaces a turn in the side sheet for visibility — the model's
     * reply isn't shown as prose; the user sees a preview overlay and an
     * Accept / Reject affordance from [pendingEdit].
     */
    fun submitAiEdit(description: String, userPrompt: String, selection: List<NoteItem>? = null) {
        val snapshot = _aiSheetState.value
        if (snapshot.isStreaming) return
        val resolvedSelection = selection
            ?: snapshot.pendingSelection
            ?: items.filter { it.id in _selection.value }.takeIf { it.isNotEmpty() }
        val turnId = UUID.randomUUID().toString()
        val turn = AskTurn(
            id = turnId,
            prompt = userPrompt,
            selectionSummary = resolvedSelection?.let { summarizeSelection(it) },
            replyBuffer = "Thinking…",
            state = TurnState.Streaming,
        )
        _aiSheetState.update { current ->
            current.copy(
                isOpen = true,
                turns = current.turns + turn,
                inputText = "",
            )
        }
        streamingJobs[turnId] = viewModelScope.launch {
            runStream(
                turnId = turnId,
                prompt = userPrompt,
                selection = resolvedSelection,
                mode = AskMode.EDIT,
                editDescription = description,
            )
        }
    }

    /**
     * Sub-phase 7.4 — apply the staged AI edit to the canvas as a single
     * [EditorAction.CompositeEdit] (one undo entry). Clears the preview.
     */
    fun acceptPendingEdit() {
        val pending = _pendingEdit.value ?: return
        _pendingEdit.value = null
        if (pending.simulation.isEmpty) return
        apply(pending.simulation.toCompositeEdit(pending.description))
    }

    /** Sub-phase 7.4 — drop the staged AI edit without touching the canvas. */
    fun rejectPendingEdit() {
        _pendingEdit.value = null
    }

    /**
     * Sub-phase 7.5 — single entry point for the lasso menu's edit actions.
     * Local entries (Clean up / Straighten) run synchronously; model-backed
     * entries route through [submitAiEdit] and stage a preview.
     */
    fun applyCannedEditAction(action: CannedEditAction, selection: List<NoteItem>? = null) {
        val target = selection
            ?: items.filter { it.id in _selection.value }.takeIf { it.isNotEmpty() }
            ?: return
        when (action) {
            CannedEditAction.CLEAN_UP -> applyLocalCleanUp(target)
            CannedEditAction.STRAIGHTEN -> applyLocalStraighten(target)
            CannedEditAction.AI_CLEAN_UP,
            CannedEditAction.AUTO_SHAPE,
            CannedEditAction.CONTINUE -> submitAiEdit(
                description = action.undoDescription,
                userPrompt = action.prompt,
                selection = target,
            )
        }
    }

    /**
     * Sub-phase 7.5 — recolor selected items via the model. [colorArgb] is
     * the colour the picker returned; we hand the model a fully-formed
     * brief so the only thing it needs to do is emit the op.
     */
    fun applyAiRecolor(colorArgb: Int, selection: List<NoteItem>? = null) {
        val target = selection ?: items.filter { it.id in _selection.value }
        if (target.isEmpty()) return
        val hex = "#%06X".format(colorArgb and 0xFFFFFF)
        submitAiEdit(
            description = "AI Recolor",
            userPrompt = aiRecolorPrompt(hex),
            selection = target,
        )
    }

    /**
     * Sub-phase 7.5 — local Clean-up. Smooths every selected stroke through
     * Chaikin without calling the model. Lands as a single undo entry.
     */
    fun applyLocalCleanUp(selection: List<NoteItem>? = null) {
        val target = selection ?: items.filter { it.id in _selection.value }
        if (target.isEmpty()) return
        val pairs = ArrayList<Pair<NoteItem, NoteItem>>()
        for (item in target) {
            val smoothed = EditPreviewController.smoothStroke(item, iterations = 2) ?: continue
            if (smoothed == item) continue
            pairs += item to smoothed
        }
        if (pairs.isEmpty()) return
        apply(EditorAction.CompositeEdit(
            description = "Clean up",
            added = emptyList(),
            removed = emptyList(),
            modified = pairs,
        ))
    }

    /**
     * Sub-phase 7.5 — local Straighten. Rotates the selection so its
     * dominant line snaps to the nearest 15° increment. Computes the
     * dominant angle from the union bounding box of stroke endpoints; if
     * the selection has no strokes, no-ops.
     */
    fun applyLocalStraighten(selection: List<NoteItem>? = null) {
        val target = selection ?: items.filter { it.id in _selection.value }
        val strokes = target.filter { it.kind == STROKE_KIND }
        if (strokes.isEmpty()) return
        val angleRad = dominantAngleRadians(strokes) ?: return
        // Snap to nearest 15 degrees.
        val snapStep = Math.toRadians(15.0).toFloat()
        val snapped = kotlin.math.round(angleRad / snapStep) * snapStep
        val delta = snapped - angleRad
        if (kotlin.math.abs(delta) < 0.001f) return
        // Rotate around the centroid of the selection bounds.
        val rects = strokes.mapNotNull { item ->
            val samples = StrokeCodec.decode(item.payload)
            HitTest.boundsOf(samples, samples.size / StrokeCodec.FLOATS_PER_SAMPLE)
        }
        val bounds = LassoController.unionBounds(rects) ?: return
        val cx = (bounds[0] + bounds[2]) * 0.5f
        val cy = (bounds[1] + bounds[3]) * 0.5f
        val matrix = StrokeTransform.rotationAround(delta, cx, cy)
        apply(EditorAction.TransformItems(strokes.map { it.id }, matrix))
    }

    private fun dominantAngleRadians(strokes: List<NoteItem>): Float? {
        // Cheap heuristic: angle from each stroke's start to its end, then
        // take the circular mean. Good enough for "straighten this line".
        var sumSin = 0.0
        var sumCos = 0.0
        var count = 0
        for (item in strokes) {
            val samples = StrokeCodec.decode(item.payload)
            val n = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            if (n < 2) continue
            val x0 = samples[0]
            val y0 = samples[1]
            val x1 = samples[(n - 1) * StrokeCodec.FLOATS_PER_SAMPLE]
            val y1 = samples[(n - 1) * StrokeCodec.FLOATS_PER_SAMPLE + 1]
            val dx = x1 - x0
            val dy = y1 - y0
            if (dx == 0f && dy == 0f) continue
            val a = kotlin.math.atan2(dy, dx).toDouble()
            sumSin += kotlin.math.sin(a)
            sumCos += kotlin.math.cos(a)
            count++
        }
        if (count == 0) return null
        return kotlin.math.atan2(sumSin, sumCos).toFloat()
    }

    private suspend fun runStream(
        turnId: String,
        prompt: String,
        selection: List<NoteItem>?,
        mode: AskMode = AskMode.ASK,
        editDescription: String = "AI edit",
    ) {
        val baseUrl = preferencesManager.apiBaseUrl.first()
        val apiKey = preferencesManager.apiKey.first()
        val modelId = _aiSheetState.value.activeModelId
            .ifEmpty { preferencesManager.defaultModel.first() }
        val request = AskRequest(
            note = _note.value,
            allItems = items.toList(),
            selection = selection,
            userPrompt = prompt,
            modelId = modelId,
            baseUrl = baseUrl,
            apiKey = apiKey,
            mode = mode,
            layers = _layers.value,
        )
        try {
            aiService.ask(request).collect { chunk ->
                mutateTurn(turnId) { turn ->
                    when (chunk) {
                        is AiChunk.Delta -> turn.copy(replyBuffer = turn.replyBuffer + chunk.text)
                        is AiChunk.Complete -> turn.copy(state = TurnState.Done)
                        is AiChunk.Error -> turn.copy(state = TurnState.Error(chunk.message))
                        is AiChunk.EditPreview -> {
                            stagePendingEdit(chunk, editDescription)
                            val message = chunk.doc.summary.ifBlank {
                                if (chunk.doc.ops.isEmpty()) "No changes proposed."
                                else "Preview ready (${chunk.doc.ops.size} ops)."
                            }
                            turn.copy(replyBuffer = message, state = TurnState.Done)
                        }
                    }
                }
            }
            // Some upstreams complete the flow without an explicit Complete
            // event (e.g. early termination); promote any still-Streaming
            // turn to Done so the action row eventually appears.
            mutateTurn(turnId) { turn ->
                if (turn.state is TurnState.Streaming) turn.copy(state = TurnState.Done)
                else turn
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Caller (cancelAiStreaming) already flipped the turn state; just
            // let cancellation bubble so the parent scope is informed.
            throw cancelled
        } catch (t: Throwable) {
            mutateTurn(turnId) { turn ->
                turn.copy(state = TurnState.Error(t.message ?: "Unexpected error"))
            }
        } finally {
            streamingJobs.remove(turnId)
        }
    }

    /**
     * Sub-phase 7.4 — convert an EDIT-mode terminal chunk into a staged
     * [PendingEdit] the UI can render as a translucent preview overlay.
     * Re-simulates the ops against the live item list (defense in depth —
     * the parser already validated, but items could have changed since the
     * request was made).
     */
    private fun stagePendingEdit(chunk: AiChunk.EditPreview, description: String) {
        val simulation = EditPreviewController.simulate(
            currentItems = items.toList(),
            doc = chunk.doc,
            idMap = chunk.idMap,
            layerMap = chunk.layerMap,
            layers = _layers.value,
        )
        _pendingEdit.value = PendingEdit(
            description = description,
            doc = chunk.doc,
            simulation = simulation,
        )
    }

    private fun mutateTurn(turnId: String, block: (AskTurn) -> AskTurn) {
        _aiSheetState.update { current ->
            val idx = current.turns.indexOfFirst { it.id == turnId }
            if (idx < 0) current
            else current.copy(
                turns = current.turns.toMutableList().also {
                    it[idx] = block(it[idx])
                }
            )
        }
    }

    private fun summarizeSelection(selection: List<NoteItem>): String {
        val strokes = selection.count { it.kind == STROKE_KIND }
        val texts = selection.count { it.kind == TextItemCodec.KIND }
        return when {
            strokes > 0 && texts > 0 -> "$strokes strokes, $texts text selected"
            strokes > 0 -> if (strokes == 1) "1 stroke selected" else "$strokes strokes selected"
            texts > 0 -> if (texts == 1) "1 text selected" else "$texts text items selected"
            else -> "${selection.size} items selected"
        }
    }

    private fun findTopmostTextItemAt(worldX: Float, worldY: Float): NoteItem? {
        var best: NoteItem? = null
        var bestZ = Int.MIN_VALUE
        for (item in items) {
            if (item.kind != TextItemCodec.KIND) continue
            val bounds = TextItemRenderer.boundsOf(item) ?: continue
            if (worldX < bounds[0] || worldX > bounds[2]) continue
            if (worldY < bounds[1] || worldY > bounds[3]) continue
            if (item.zIndex >= bestZ) {
                best = item
                bestZ = item.zIndex
            }
        }
        return best
    }

    private fun duplicate(source: NoteItem, paste: Boolean): NoteItem {
        val offset = if (paste) PASTE_OFFSET_WORLD else DUPLICATE_OFFSET_WORLD
        val shifted = StrokeTransform.translation(offset, offset)
        val newPayload = when (source.kind) {
            STROKE_KIND -> {
                val samples = StrokeCodec.decode(source.payload)
                StrokeCodec.encode(StrokeTransform.applyToSamples(shifted, samples))
            }
            TextItemCodec.KIND -> {
                val decoded = TextItemCodec.decode(source.payload)
                val newMatrix = StrokeTransform.multiply(shifted, decoded.matrix)
                TextItemCodec.encode(TextItemCodec.withMatrix(decoded, newMatrix))
            }
            Shape.KIND -> {
                val decoded = ShapeCodec.decode(source.payload)
                val transformed = ShapeCodec.transform(decoded.shape, shifted)
                ShapeCodec.encode(transformed, decoded.fillArgb)
            }
            NoteItem.KIND_IMAGE -> {
                val payload = ImageItemCodec.decode(source.payload)
                ImageItemCodec.encode(ImageItemCodec.transform(payload, shifted))
            }
            else -> source.payload.copyOf()
        }
        return source.copy(
            id = UUID.randomUUID().toString(),
            noteId = resolvedNoteId,
            zIndex = zIndexFor(source.tool),
            payload = newPayload,
        )
    }

    private fun recomputeSelectionBounds(): FloatArray? {
        val ids = _selection.value
        if (ids.isEmpty()) return null
        val rects = ArrayList<FloatArray>(ids.size)
        for (item in items) {
            if (item.id !in ids) continue
            val rect = when (item.kind) {
                STROKE_KIND -> {
                    val samples = StrokeCodec.decode(item.payload)
                    val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                    HitTest.boundsOf(samples, count)
                }
                TextItemCodec.KIND -> TextItemRenderer.boundsOf(item)
                Shape.KIND -> ShapeCodec.boundsOf(ShapeCodec.decode(item.payload).shape)
                NoteItem.KIND_IMAGE -> ImageItemCodec.boundsOf(ImageItemCodec.decode(item.payload))
                else -> null
            }
            rect?.let { rects.add(it) }
        }
        return LassoController.unionBounds(rects)
    }

    /**
     * Persist the current in-memory note. Returns the resolved note id so callers on
     * the `note/new` route can navigate to the canonical `note/<id>` route if needed.
     */
    /**
     * Persist the current note and export it as a PNG (sub-phase 4.1).
     *
     * Save runs first so the receiving app sees the user's most recent
     * strokes — re-rendering a stale in-memory snapshot would surprise the
     * user who just clicked Share. The returned URI is granted via
     * [androidx.core.content.FileProvider] and must be paired with
     * `Intent.FLAG_GRANT_READ_URI_PERMISSION` by the caller.
     */
    suspend fun sharePng(): Uri {
        commitTextEdit()
        save()
        return noteExporter.exportPng(note = _note.value, items = items.toList())
    }

    /**
     * Sub-phase 8.1 — share the currently-selected frame as a PNG. Returns
     * null when no frame is selected (UI gates this; the guard keeps the
     * VM honest under racy state).
     */
    suspend fun sharePngForCurrentFrame(): Uri? {
        val bounds = currentFrameBounds() ?: return null
        commitTextEdit()
        save()
        return noteExporter.exportPng(
            note = _note.value,
            items = items.toList(),
            frameBounds = bounds,
        )
    }

    /** Sub-phase 8.1 — SVG export bounded to the active frame. */
    suspend fun shareSvgForCurrentFrame(): Uri? {
        val bounds = currentFrameBounds() ?: return null
        commitTextEdit()
        save()
        return noteSvgExporter.exportSvg(
            note = _note.value,
            items = items.toList(),
            frameBounds = bounds,
        )
    }

    /**
     * Phase 6.8 — vector-fidelity export. Same save-first contract as
     * [sharePng] / [sharePdf] so the resulting SVG reflects the user's most
     * recent geometry.
     */
    suspend fun shareSvg(): Uri {
        commitTextEdit()
        save()
        return noteSvgExporter.exportSvg(note = _note.value, items = items.toList())
    }

    /**
     * Persist the current note and export it as a PDF (sub-phase 4.2).
     *
     * Same save-first contract as [sharePng] so the receiving app sees the
     * latest geometry. [layout] picks between fit-one-page and
     * tile-across-pages; [pageSize] is the user's confirmed paper format
     * from [ExportPdfDialog]. Margins default to half an inch (per
     * [PdfLayout.DEFAULT_MARGIN_PT]) — exposed as a hook in case a future
     * pref wants to override.
     */
    suspend fun sharePdf(
        layout: PdfLayout.Mode,
        pageSize: PdfLayout.PageSize,
    ): Uri {
        commitTextEdit()
        save()
        return noteExporter.exportPdf(
            note = _note.value,
            items = items.toList(),
            layout = layout,
            pageSize = pageSize,
        )
    }

    /**
     * Geometry bounds used by [ExportPdfDialog] to compute its live page-count
     * preview. Returns the default paper rect for empty notes so the dialog
     * still renders a coherent caption (one page).
     */
    fun computeBoundsForExport(): FloatArray =
        com.aichat.sandbox.data.notes.NoteRasterizer.computeBounds(items.toList())
            ?: NoteExporter.defaultPaperBounds()

    suspend fun save(): String {
        val current = _note.value
        val sanitizedTitle = current.title.ifBlank { DEFAULT_TITLE }
        // Sub-phase 5.2: serialize the undo/redo log alongside the note row.
        // [EditorActionCodec.encode] applies the 256 KB cap; if both stacks
        // wind up empty we store `null` so a never-edited note doesn't carry
        // an empty `{schema:1,past:[],future:[]}` blob forever.
        val undoJson = EditorActionCodec.encode(past.toList(), future.toList())
        val toPersist = current.copy(
            title = sanitizedTitle,
            updatedAt = System.currentTimeMillis(),
            undoLogJson = undoJson,
        )
        // Sub-phase 6.4 — flush the layer list atomically with items so the
        // FK invariants stay sound across the save.
        repository.saveNoteWithLayers(
            note = toPersist,
            items = items.toList(),
            layers = _layers.value,
        )
        // Sub-phase 8.1 — flush the frame list. Lives in its own table to
        // keep the items / layers transaction small.
        repository.saveFrames(toPersist.id, _frames.value)
        _note.value = toPersist
        // Fire-and-forget on the repository's singleton-scoped background scope
        // so the user isn't held on the editor while we rasterize — and so the
        // job survives this ViewModel being cleared on navigate-back.
        repository.renderThumbnailAsync(toPersist.id)
        // Same survival guarantee for OCR. Internally debounced to a 2s window
        // (NoteRepository.OCR_DEBOUNCE_MS) so a save burst — e.g. drag-saves,
        // background-style toggle, back-press — coalesces into one ML Kit call.
        repository.runOcrAsync(toPersist.id)
        return toPersist.id
    }

    private fun zIndexFor(tool: String?): Int =
        if (tool == StrokeRenderer.TOOL_HIGHLIGHTER) nextHighlighterZIndex++
        else nextInkZIndex++

    private fun refreshZIndexCounters(loaded: List<NoteItem>) {
        val maxHl = loaded
            .filter { it.tool == StrokeRenderer.TOOL_HIGHLIGHTER }
            .maxOfOrNull { it.zIndex }
        val maxInk = loaded
            .filter { it.tool != StrokeRenderer.TOOL_HIGHLIGHTER }
            .maxOfOrNull { it.zIndex }
        nextHighlighterZIndex = (maxHl ?: (HIGHLIGHTER_Z_BASE - 1)) + 1
        nextInkZIndex = (maxInk ?: -1) + 1
    }

    private fun emptyNote(id: String) = Note(
        id = id,
        title = "",
        backgroundStyle = DEFAULT_BACKGROUND_STYLE,
        schemaVersion = CURRENT_SCHEMA_VERSION,
        minX = 0f,
        minY = 0f,
        maxX = 0f,
        maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )

    companion object {
        /**
         * Highlighter strokes start at a large negative index so a note can
         * accumulate ~1M highlighter items before colliding with the ink tier.
         */
        const val HIGHLIGHTER_Z_BASE = -1_000_000

        private const val STROKE_KIND = "stroke"

        /** Offset applied when duplicating a selection in-place. */
        private const val DUPLICATE_OFFSET_WORLD = 24f

        /** Offset applied when pasting from the clipboard. */
        private const val PASTE_OFFSET_WORLD = 24f

        /**
         * Default text colour. Text items use the same per-tool color slot as
         * strokes but until a color picker for text lands (later sub-phase)
         * everything goes black.
         */
        private const val TEXT_DEFAULT_COLOR: Int = 0xFF000000.toInt()

        /** Shown on a turn that was cancelled by the user mid-stream. */
        private const val CANCEL_MESSAGE: String = "Cancelled."

        /** Shown when Convert-to-text can't recover any words. */
        private const val NO_HANDWRITING_MESSAGE: String = "Couldn't recognize handwriting."

        /**
         * Cap on the OCR snippet pre-filled into the chat composer when the
         * editor's share menu sends a whole note (sub-phase 4.3). 200 chars
         * — long enough to identify the note, short enough to stay below the
         * "tap and type your prompt" threshold so the user still feels they
         * own the message.
         */
        private const val OCR_SNIPPET_MAX_LEN: Int = 200

        /** Sub-phase 6.7 — initial longest-edge world size for inserted images. */
        private const val IMAGE_INSERT_TARGET_WORLD: Float = 320f

        /** Sub-phase 8.3 — stamp thumbnail longest edge. */
        private const val STAMP_THUMBNAIL_MAX_EDGE_PX: Int = 256
    }
}

/**
 * Sub-phase 7.4 — staged AI edit pending user accept / reject.
 *
 * The preview overlay reads [simulation] to render dimmed originals,
 * dashed-outline removals, magenta-outlined additions, and the modified-
 * after-image over the modified-before-image. The undo entry built on
 * Accept is `simulation.toCompositeEdit(description)`.
 */
data class PendingEdit(
    val description: String,
    val doc: EditOpsDoc,
    val simulation: EditPreviewController.Simulation,
)

/**
 * Sub-phase 4.3 picker mode for the [SendToChatSheet]. Drives both how the
 * outgoing payload is rendered (whole-note PNG vs selection-or-whole-note
 * PNG) and what text lands in the destination composer (OCR snippet vs AI
 * reply body).
 */
sealed interface SendToChatMode {
    /** Editor TopAppBar share-menu entry: whole-note PNG + OCR snippet. */
    data object ShareNote : SendToChatMode

    /**
     * AI side-sheet per-reply action. The PNG follows the turn's scope
     * (selection if present, else whole note); the draft text is the reply
     * body.
     */
    data class AiReply(val turnId: String) : SendToChatMode
}

/**
 * Coarse "is OCR doing something right now?" signal for the editor's TopAppBar
 * indicator (sub-phase 2.4). Combines model-download and per-note in-flight
 * state into the three shapes the UI actually cares about.
 */
enum class OcrIndicator {
    /** Nothing to show — model is ready and no job is running for this note. */
    Idle,

    /** ML Kit is fetching the Digital Ink model from Play Services. */
    Downloading,

    /** Recognition is in progress for the active note. */
    Running,
}

/**
 * Open text-edit target driven by the TEXT tool. Two flavours so the editor
 * overlay can render in screen space and the commit path knows whether to
 * `AddItems` a brand-new item or apply an `UpdateText` to an existing one.
 */
sealed interface TextEditTarget {
    /** Screen-positioning origin (world coords). */
    val worldX: Float
    val worldY: Float
    val fontSize: Float
    val alignment: Byte
    val initialBody: String

    data class NewAt(
        override val worldX: Float,
        override val worldY: Float,
        override val fontSize: Float,
        override val alignment: Byte,
    ) : TextEditTarget {
        override val initialBody: String get() = ""
    }

    data class Existing(
        val itemId: String,
        override val initialBody: String,
        override val worldX: Float,
        override val worldY: Float,
        override val fontSize: Float,
        override val alignment: Byte,
    ) : TextEditTarget
}
