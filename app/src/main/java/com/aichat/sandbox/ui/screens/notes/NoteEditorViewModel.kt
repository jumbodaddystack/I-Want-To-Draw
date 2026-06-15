package com.aichat.sandbox.ui.screens.notes

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.ink.LassoTriangulation
import com.aichat.sandbox.data.ink.MeshHitTest
import com.aichat.sandbox.data.ink.StrokeMeshCache
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteFrame
import android.graphics.Bitmap
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.NoteRasterizer
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.model.Stamp
import com.aichat.sandbox.data.notes.AiChunk
import com.aichat.sandbox.data.notes.AskMode
import com.aichat.sandbox.data.notes.AskRequest
import com.aichat.sandbox.data.notes.BrushSpec
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
import com.aichat.sandbox.data.notes.StampSearch
import com.aichat.sandbox.data.notes.ToolPalettePrefsStore
import com.aichat.sandbox.data.repository.BrushPresetRepository
import com.aichat.sandbox.data.repository.ChatRepository
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.repository.StampRepository
import com.aichat.sandbox.data.vector.edit.boolean.PathBoolean
import com.aichat.sandbox.ui.components.notes.AlignmentMath
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.FillStyle
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.ItemTransformer
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.ConnectorResolver
import com.aichat.sandbox.ui.components.notes.PathBooleanBridge
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.PathConversions
import com.aichat.sandbox.ui.components.notes.PathMerge
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.Snap
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.aichat.sandbox.ui.components.notes.TextItemRenderer
import com.aichat.sandbox.ui.components.notes.Tool
import com.aichat.sandbox.ui.components.notes.ToolPaletteState
import com.aichat.sandbox.ui.components.notes.ZOrderMath
import androidx.compose.runtime.snapshotFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
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
/** `note/new?source=icon` — seeds icon mode (artboard + grid + AI prompt). */
const val ENTRY_SOURCE_ICON = "icon"
/** 14.3 — `note/new?template=user:<id>` seeds a user-saved template. */
const val USER_TEMPLATE_PREFIX = "user:"
private const val DEFAULT_TITLE = "Untitled"
private const val DEFAULT_BACKGROUND_STYLE = "plain"
private const val CURRENT_SCHEMA_VERSION = 1
private const val UNDO_STACK_CAP = 200
/**
 * Icon artboard edge in world units. 768 = 24 cells × the 32-unit background
 * grid spacing, so a "graph" background draws a clean 24×24 icon grid.
 */
private const val ICON_ARTBOARD_WORLD = 768f

/**
 * Selectable icon-artboard sizes, expressed as a count of 32-unit background
 * grid cells. The artboard is no longer locked to the seeded 24-grid — the
 * editor exposes these so a user can design against a tighter or roomier grid.
 */
enum class IconCanvasSize(val cells: Int, val world: Float, val label: String) {
    SMALL_16(16, 512f, "Small · 16-grid"),
    MEDIUM_24(24, 768f, "Medium · 24-grid"),
    LARGE_32(32, 1024f, "Large · 32-grid"),
    XLARGE_48(48, 1536f, "Extra large · 48-grid");

    companion object {
        /** Nearest preset for a measured artboard edge (for selecting the live row). */
        fun nearest(world: Float): IconCanvasSize =
            entries.minBy { kotlin.math.abs(it.world - world) }
    }
}

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
    private val noteVectorDrawableExporter: com.aichat.sandbox.data.notes.NoteVectorDrawableExporter,
    private val noteIconSetExporter: com.aichat.sandbox.data.notes.NoteIconSetExporter,
    private val recentColorsStore: RecentColorsStore,
    private val palettePrefsStore: ToolPalettePrefsStore,
    private val brushPresets: BrushPresetRepository,
    private val noteImageStore: NoteImageStore,
    private val stampRepository: StampRepository,
    private val userTemplateRepository: com.aichat.sandbox.data.repository.UserTemplateRepository,
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

    // Sub-phase 11.4 — `note/new?template=<id>` seeds a starter template
    // into the fresh note (frames + shapes + stickies + text + connectors).
    private val entryTemplate: String? = savedStateHandle["template"]

    private val _note = MutableStateFlow(emptyNote(resolvedNoteId))
    val note: StateFlow<Note> = _note.asStateFlow()

    // In-flight initial DB load for an existing note. save() awaits it so a
    // back-press (or share / AI ask, which also save) racing the load can't
    // persist the still-empty item list over the note's real content —
    // saveNoteWithLayers deletes-then-reinserts, so an early save would wipe
    // the note.
    private var initialLoad: kotlinx.coroutines.Job? = null

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

    // ── In-session autosave ──────────────────────────────────────────────
    //
    // Historically the note persisted only on close / share / AI submit, so a
    // crash mid-session lost everything since open. Every content mutation
    // now schedules a debounced background persist via [scheduleAutosave];
    // rapid edits (a stroke burst, per-keystroke title edits) coalesce into
    // one DB write [AUTOSAVE_DEBOUNCE_MS] after the last one.

    /** Pending debounced autosave; cancelled and re-armed on every edit. */
    private var autosaveJob: Job? = null

    /** Serializes [persistSnapshot] so an in-flight autosave can't interleave
     *  with an explicit save (back-press / share). */
    private val persistMutex = Mutex()

    /**
     * Gate: autosave must not run before the initial load finishes — for an
     * existing note the in-memory state is an empty placeholder until the DB
     * read lands, and persisting that would wipe the real content.
     */
    @Volatile
    private var initialLoadComplete = false

    /** True once any save (auto or explicit) has written a row for this note.
     *  Lets the editor clean up an autosaved row if the user empties a brand-new
     *  note (e.g. draws, undoes, backs out) — see [discardAutosavedIfBlank]. */
    private var persistedOnce = false

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

    // ── Sticky editor target (sub-phase 11.1) ────────────────────────────
    //
    // Unlike the text tool there is no "NewAt" mode: tapping empty canvas
    // with the STICKY tool *immediately* drops the sticky (an empty sticky
    // is a valid board artifact, and the user sees the colour land), then
    // opens the inline editor on it. Commit diffs the body into one
    // CompositeEdit("Edit sticky").

    private val _stickyEditTarget = MutableStateFlow<StickyEditTarget?>(null)
    val stickyEditTarget: StateFlow<StickyEditTarget?> = _stickyEditTarget.asStateFlow()

    /** Live sticky draft body — the Compose editor writes this per keystroke. */
    private var stickyEditDraftBody: String = ""

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
     * Phase 10.2 — when true, the next [confirmColorPick] writes the shape
     * fill slot instead of the active ink tool. Set by
     * [openShapeFillColorPicker], cleared on confirm or dismiss.
     */
    private var colorPickerTargetsShapeFill = false

    /**
     * When the colour picker was opened by the AI side sheet's "Recolor"
     * quick action, this holds the items to recolor. Non-null routes the next
     * [confirmColorPick] to [applyAiRecolor] instead of changing the ink tool;
     * cleared on confirm or dismiss.
     */
    private val _pendingAiRecolorScope = MutableStateFlow<List<NoteItem>?>(null)

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
        scheduleAutosave()
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
        scheduleAutosave()
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
        scheduleAutosave()
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
        scheduleAutosave()
    }

    private fun updateLayer(id: String, mutate: (NoteLayer) -> NoteLayer) {
        _layers.value = _layers.value.map { if (it.id == id) mutate(it) else it }
        scheduleAutosave()
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

    // ── Presentation mode (sub-phase 11.5) ───────────────────────────────
    //
    // A full-screen stepper over the note's frames in ordinal order. The
    // index points into [presentationFrames]; null = not presenting. The
    // screen hides the editor chrome and flies the viewport to the active
    // frame; the surface switches the stylus to transient laser ink and
    // maps the barrel button to "advance".

    private val _presentationIndex = MutableStateFlow<Int?>(null)
    val presentationIndex: StateFlow<Int?> = _presentationIndex.asStateFlow()

    /** Frames in presentation order (ordinal ascending). */
    fun presentationFrames(): List<NoteFrame> = _frames.value.sortedBy { it.ordinal }

    fun startPresentation() {
        if (_frames.value.isEmpty()) return
        commitTextEdit()
        commitStickyEdit()
        clearSelection()
        _presentationIndex.value = 0
    }

    /** Step the presentation by [delta] frames, clamped to the deck. */
    fun stepPresentation(delta: Int) {
        val current = _presentationIndex.value ?: return
        val count = _frames.value.size
        if (count == 0) {
            _presentationIndex.value = null
            return
        }
        _presentationIndex.value = (current + delta).coerceIn(0, count - 1)
    }

    fun exitPresentation() {
        _presentationIndex.value = null
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

    /**
     * Icon mode — mark this note as an icon, switch to a grid background, and
     * drop a single square artboard frame for the user to draw inside. The
     * artboard is [ICON_ARTBOARD_WORLD] world units square so it lines up with
     * the 32-unit background grid (a clean 24×24 icon grid). Seeded directly
     * into state (no undo entry) since it's the note's initial condition.
     */
    private fun seedIconArtboard() {
        _note.update {
            it.copy(
                isIcon = true,
                backgroundStyle = com.aichat.sandbox.ui.components.notes.BackgroundLayer.STYLE_GRAPH,
            )
        }
        val artboard = NoteFrame(
            noteId = resolvedNoteId,
            name = "Artboard",
            minX = 0f,
            minY = 0f,
            maxX = ICON_ARTBOARD_WORLD,
            maxY = ICON_ARTBOARD_WORLD,
            ordinal = 0,
        )
        _frames.value = listOf(artboard)
        _currentFrameId.value = artboard.id
    }

    /**
     * Sub-phase 11.4 — stamp [template]'s content into this fresh note.
     * Seeded directly into state (no undo entries), same convention as
     * [seedIconArtboard]: a template is the note's initial condition, and
     * "undo right after open" shouldn't strip the scaffolding. Sets the
     * note title to the template's display name so the list stays legible.
     */
    private fun seedTemplate(template: com.aichat.sandbox.data.notes.NoteTemplate) {
        val content = com.aichat.sandbox.data.notes.NoteTemplates.build(
            template = template,
            noteId = resolvedNoteId,
            layerId = _activeLayerId.value,
        )
        items.addAll(content.items)
        refreshZIndexCounters(content.items)
        if (content.frames.isNotEmpty()) {
            _frames.value = content.frames
            _currentFrameId.value = content.frames.minByOrNull { it.ordinal }?.id
        }
        _note.update { it.copy(title = template.displayName) }
    }

    /**
     * Sub-phase 14.3 — seed a user-saved template. Same convention as
     * [seedTemplate], but the content instantiates from the persisted JSON
     * via [com.aichat.sandbox.data.notes.TemplatePayloadCodec] — every item
     * and frame re-keyed fresh, connector bindings and groupIds remapped.
     */
    private suspend fun seedUserTemplate(templateId: String) {
        val template = userTemplateRepository.get(templateId) ?: return
        val content = com.aichat.sandbox.data.notes.TemplatePayloadCodec.instantiate(
            json = template.payloadJson,
            noteId = resolvedNoteId,
            layerId = _activeLayerId.value,
        ) ?: return
        items.addAll(content.items)
        refreshZIndexCounters(content.items)
        if (content.frames.isNotEmpty()) {
            _frames.value = content.frames
            _currentFrameId.value = content.frames.minByOrNull { it.ordinal }?.id
        }
        _note.update { it.copy(title = template.name) }
        userTemplateRepository.touchLastUsed(templateId)
    }

    /**
     * Sub-phase 14.3 — "save this note as a template": snapshot the current
     * items + frames into the template codec under the note's title. The
     * new-note flow lists it alongside the built-ins.
     */
    fun saveNoteAsTemplate() {
        val snapshot = items.toList()
        val frames = _frames.value
        if (snapshot.isEmpty() && frames.isEmpty()) return
        val name = _note.value.title.ifBlank { "Untitled template" }
        viewModelScope.launch {
            userTemplateRepository.save(
                name = name,
                payloadJson = com.aichat.sandbox.data.notes.TemplatePayloadCodec.encode(
                    items = snapshot,
                    frames = frames,
                ),
            )
            _templateSaved.tryEmit(name)
        }
    }

    /** One-shot "saved as template" feedback, mirroring [stampSaved]. */
    private val _templateSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val templateSaved: SharedFlow<String> = _templateSaved.asSharedFlow()

    /** Returns the world-bounds of the currently-selected frame, or null. */
    fun currentFrameBounds(): FloatArray? {
        val id = _currentFrameId.value ?: return null
        val frame = _frames.value.firstOrNull { it.id == id } ?: return null
        return frame.bounds()
    }

    /**
     * Bounds to frame when an icon is first shown. The viewport is otherwise
     * left at its default (origin, 100%), which leaves a reopened icon's
     * artboard and strokes off-screen — the work looks lost even though it's
     * loaded. We return the artboard rect unioned with any drawn content so
     * the whole saved icon is guaranteed visible on open. Null when this note
     * has no artboard frame (i.e. it isn't an icon).
     */
    fun iconOpenBounds(): FloatArray? {
        val frame = currentFrameBounds() ?: _frames.value.firstOrNull()?.bounds() ?: return null
        val content = com.aichat.sandbox.data.notes.NoteRasterizer.computeBounds(items.toList())
            ?: return frame
        return floatArrayOf(
            minOf(frame[0], content[0]),
            minOf(frame[1], content[1]),
            maxOf(frame[2], content[2]),
            maxOf(frame[3], content[3]),
        )
    }

    // Latest viewport (pan offset + zoom) pushed from the screen so [save]
    // can persist it for icons. The VM doesn't own the ViewportController
    // (the screen creates it via onViewportReady), so the screen hands the
    // live values down right before a save. `[offsetX, offsetY, scale]`.
    private var pendingIconViewport: FloatArray? = null

    /** Screen → VM bridge: stash the live viewport so [save] can persist it. */
    fun setIconViewport(offsetX: Float, offsetY: Float, scale: Float) {
        pendingIconViewport = floatArrayOf(offsetX, offsetY, scale)
    }

    /** Edge of the current icon artboard in world units, or null if absent. */
    fun iconArtboardWorld(): Float? {
        val b = currentFrameBounds() ?: return null
        return maxOf(b[2] - b[0], b[3] - b[1])
    }

    /**
     * Resize the icon artboard to a square [worldSize], keeping its top-left
     * corner anchored. Routes through [updateFrameBounds] so the change is a
     * normal, undoable frame mutation — no new persistence path.
     */
    fun resizeIconArtboard(worldSize: Float) {
        val id = _currentFrameId.value ?: _frames.value.firstOrNull()?.id ?: return
        val frame = _frames.value.firstOrNull { it.id == id } ?: return
        updateFrameBounds(
            id,
            floatArrayOf(frame.minX, frame.minY, frame.minX + worldSize, frame.minY + worldSize),
        )
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

    // ── Stamp library tags + search (Phase 17.5 follow-on, mirrors 17.1) ──

    /** `stampId → normalized tags`, derived from the junction table. */
    val stampTags: StateFlow<Map<String, List<String>>> = stampRepository.observeAllTags()
        .map { rows -> StampSearch.groupTags(rows.map { it.stampId to it.tag }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyMap(),
        )

    /** Distinct stamp tags with counts — the drawer's filter-chip row. */
    val stampTagCounts: StateFlow<List<com.aichat.sandbox.data.local.TagCount>> =
        stampRepository.observeTagCounts()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )

    private val _stampQuery = MutableStateFlow("")
    val stampQuery: StateFlow<String> = _stampQuery.asStateFlow()

    private val _stampTagFilter = MutableStateFlow<String?>(null)
    val stampTagFilter: StateFlow<String?> = _stampTagFilter.asStateFlow()

    /** Stamps after the active search query + tag-chip filter (drawer content). */
    val filteredStamps: StateFlow<List<Stamp>> = combine(
        stamps, stampTags, _stampQuery, _stampTagFilter,
    ) { all, tags, query, tagFilter ->
        StampSearch.filter(all, tags, query, tagFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
    )

    fun setStampQuery(query: String) { _stampQuery.value = query }

    /** Toggle a tag chip: tapping the active one clears the filter. */
    fun setStampTagFilter(tag: String?) {
        _stampTagFilter.value = if (tag != null && tag == _stampTagFilter.value) null else tag
    }

    /** Replace a stamp's tag set (free-form input parsed via [com.aichat.sandbox.data.notes.IconTags]). */
    fun setStampTags(stampId: String, rawInput: String) {
        viewModelScope.launch {
            stampRepository.setTags(stampId, com.aichat.sandbox.data.notes.IconTags.parse(rawInput))
        }
    }

    private val _stampDrawerOpen = MutableStateFlow(false)
    val stampDrawerOpen: StateFlow<Boolean> = _stampDrawerOpen.asStateFlow()

    fun openStampDrawer() { _stampDrawerOpen.value = true }
    fun closeStampDrawer() {
        _stampDrawerOpen.value = false
        _stampQuery.value = ""
        _stampTagFilter.value = null
    }

    /**
     * Sub-phase 8.3 — save the current selection as a reusable stamp.
     * Captures a 256 px thumbnail and serialises the selection via
     * [com.aichat.sandbox.data.notes.VectorCanvasJson]. Fire-and-forget;
     * the drawer's [stamps] flow surfaces the new entry once persisted.
     */
    // One-shot UI events for transient feedback (e.g. "Stamp saved"). A
    // SharedFlow keeps these from re-firing on recomposition/rotation the way a
    // StateFlow would.
    private val _stampSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val stampSaved: SharedFlow<String> = _stampSaved.asSharedFlow()

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
                _stampSaved.tryEmit(name)
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
                ShapeCodec.encode(transformed, decoded.fillArgb, decoded.strokeStyle, decoded.gradient)
            }
            NoteItem.KIND_IMAGE -> {
                val payload = ImageItemCodec.decode(item.payload)
                ImageItemCodec.encode(ImageItemCodec.transform(payload, shift))
            }
            StickyCodec.KIND -> {
                val payload = StickyCodec.decode(item.payload)
                StickyCodec.encode(StickyCodec.transform(payload, shift))
            }
            ConnectorCodec.KIND -> {
                // Stamp items reference ids from the source note — those
                // can't resolve here, so shift the fallback geometry and
                // drop the bindings.
                val payload = ConnectorCodec.decode(item.payload)
                ConnectorCodec.encode(
                    ConnectorCodec.transform(
                        payload.copy(fromItemId = null, toItemId = null),
                        shift,
                    )
                )
            }
            PathCodec.KIND -> {
                val payload = PathCodec.decode(item.payload)
                PathCodec.encode(PathCodec.transform(payload, shift))
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
        // Keep the AI sheet's `isIcon` in sync with the note. The note loads
        // asynchronously for existing notes, so reading it once at sheet-open
        // time would race; collecting here guarantees the chip set and the
        // edit-first default are correct before the user can interact. Icons
        // default the footer to EDIT (they are design surfaces); only flip the
        // default while the user hasn't typed anything yet so we never stomp an
        // in-progress ask.
        viewModelScope.launch {
            _note.collect { note ->
                _aiSheetState.update { current ->
                    if (current.isIcon == note.isIcon) current
                    else current.copy(
                        isIcon = note.isIcon,
                        footerMode = if (note.isIcon && current.turns.isEmpty() && current.inputText.isEmpty()) {
                            AiFooterMode.EDIT
                        } else {
                            current.footerMode
                        },
                    )
                }
            }
        }
        if (routeArg != NOTE_ID_NEW) {
            initialLoad = viewModelScope.launch {
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
                // Icon mode — preselect the artboard so the VectorDrawable
                // export uses it as the viewport without a tap first.
                if (_note.value.isIcon && _currentFrameId.value == null) {
                    _currentFrameId.value = _frames.value.minByOrNull { it.ordinal }?.id
                }
                // Sub-phase 9.1 — if this note belongs to a notebook,
                // grab the header so the editor can render notebook UI
                // (page rail, "Add page", pinned page size).
                val notebookId = loadedNote?.notebookId
                if (notebookId != null) {
                    _notebook.value = notebookRepository.getNotebook(notebookId)
                }
                // Autosave stays gated until here — persisting the empty
                // placeholder note before this load lands would wipe the
                // real content.
                initialLoadComplete = true
            }
        } else {
            // Brand-new note: seed a default "Ink" layer so the first stroke
            // has somewhere to land. Layer is persisted at save() time.
            addLayer("Ink")
            if (entrySource == ENTRY_SOURCE_ICON) seedIconArtboard()
            val userTemplateId = entryTemplate
                ?.takeIf { it.startsWith(USER_TEMPLATE_PREFIX) }
                ?.removePrefix(USER_TEMPLATE_PREFIX)
            if (userTemplateId != null) {
                // 14.3 — user templates live in the DB, so seeding is async;
                // gate save() on it (via initialLoad) exactly like an
                // existing note's load so an early back-press can't persist
                // the still-empty note.
                initialLoad = viewModelScope.launch {
                    seedUserTemplate(userTemplateId)
                    initialLoadComplete = true
                    if (items.isNotEmpty()) scheduleAutosave()
                }
            } else {
                com.aichat.sandbox.data.notes.NoteTemplate.fromId(entryTemplate)
                    ?.let { seedTemplate(it) }
                initialLoadComplete = true
                // Templates carry real content — arm an autosave so backing
                // out immediately still keeps the seeded note.
                if (items.isNotEmpty()) scheduleAutosave()
            }
        }
        // Seed the active brush preset once presets stream in.
        viewModelScope.launch {
            brushPresetList.collect { all ->
                if (all.isNotEmpty()) seedActiveBrushPresetIfNeeded(all)
            }
        }
        restoreAndPersistPalette()
    }

    /**
     * Restore the persisted palette (selected tool, per-tool colour/width,
     * eraser radius), then mirror every subsequent change back to
     * [ToolPalettePrefsStore] on a short debounce so the next editor open
     * lands on the user's last setup instead of Pen / black / 4 px.
     *
     * `entryStylus` deep-links keep their pinned PEN selection — only the
     * colour/width slots are restored for those.
     */
    @OptIn(FlowPreview::class)
    private fun restoreAndPersistPalette() {
        viewModelScope.launch {
            val saved = palettePrefsStore.prefs.first()
            palette.restore(
                selectedToolId = if (entryStylus) null else saved.selectedToolId,
                inkColors = saved.inkColors,
                inkWidths = saved.inkWidths,
                areaEraserRadiusPx = saved.areaEraserRadiusPx,
                shapeFillEnabled = saved.shapeFillEnabled,
                shapeFillColor = saved.shapeFillColor,
                shapeStrokeStyle = saved.shapeStrokeStyle,
                stickyFillColor = saved.stickyFillColor,
                inkBeautify = saved.inkBeautify,
                connectorRouteStyle = saved.connectorRouteStyle,
                screenAnchoredPenSize = saved.screenAnchoredPenSize,
                fixedWidthInk = saved.fixedWidthInk,
            )
            val inkTools = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.PENCIL)
            snapshotFlow {
                PaletteSnapshot(
                    selectedToolId = palette.selected.id,
                    inkColors = inkTools.associate { it.id to palette.colorFor(it) },
                    inkWidths = inkTools.associate { it.id to palette.widthFor(it) },
                    areaEraserRadiusPx = palette.areaEraserRadiusPx,
                    shapeFillEnabled = palette.shapeFillEnabled,
                    shapeFillColor = palette.shapeFillColor,
                    shapeStrokeStyle = palette.shapeStrokeStyle,
                    stickyFillColor = palette.stickyFillColor,
                    inkBeautify = palette.inkBeautify,
                    connectorRouteStyle = palette.connectorRouteStyle,
                    screenAnchoredPenSize = palette.screenAnchoredPenSize,
                    fixedWidthInk = palette.fixedWidthInk,
                )
            }
                // The first emission is the state we just restored.
                .drop(1)
                .debounce(PALETTE_PERSIST_DEBOUNCE_MS)
                .collect { snap ->
                    palettePrefsStore.savePalette(
                        selectedToolId = snap.selectedToolId,
                        inkColors = snap.inkColors,
                        inkWidths = snap.inkWidths,
                        areaEraserRadiusPx = snap.areaEraserRadiusPx,
                        shapeFillEnabled = snap.shapeFillEnabled,
                        shapeFillColor = snap.shapeFillColor,
                        shapeStrokeStyle = snap.shapeStrokeStyle,
                        stickyFillColor = snap.stickyFillColor,
                        inkBeautify = snap.inkBeautify,
                        connectorRouteStyle = snap.connectorRouteStyle,
                        screenAnchoredPenSize = snap.screenAnchoredPenSize,
                        fixedWidthInk = snap.fixedWidthInk,
                    )
                }
        }
    }

    private data class PaletteSnapshot(
        val selectedToolId: String,
        val inkColors: Map<String, Int>,
        val inkWidths: Map<String, Float>,
        val areaEraserRadiusPx: Float,
        val shapeFillEnabled: Boolean,
        val shapeFillColor: Int,
        val shapeStrokeStyle: Int,
        val stickyFillColor: Int,
        val inkBeautify: Boolean,
        val connectorRouteStyle: Int,
        val screenAnchoredPenSize: Boolean,
        val fixedWidthInk: Boolean,
    )

    // ── "Draw with finger" (user setting) ────────────────────────────────

    val fingerDrawing: StateFlow<Boolean> = palettePrefsStore.prefs
        .map { it.fingerDrawing }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    fun setFingerDrawing(enabled: Boolean) {
        viewModelScope.launch {
            palettePrefsStore.setFingerDrawing(enabled)
        }
    }

    // ── Ink-first authoring (phase I1) ───────────────────────────────────

    /**
     * Experimental: route live ink through AndroidX Ink's front-buffered
     * authoring layer instead of the custom quad-Bézier path. Off by default
     * and fallback-capable — see `docs/ANDROIDX_INK_MIGRATION_PLAN.md` (I1/I2).
     */
    val inkAuthoring: StateFlow<Boolean> = palettePrefsStore.prefs
        .map { it.inkAuthoring }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    fun setInkAuthoring(enabled: Boolean) {
        viewModelScope.launch {
            palettePrefsStore.setInkAuthoring(enabled)
        }
    }

    /**
     * Phase **I6** — derived, cached per-stroke ink [androidx.ink.geometry.PartitionedMesh]
     * layer backing lasso selection (and, on the surface, erasing) with robust
     * geometry. Used **only** while the ink engine is enabled ([inkAuthoring]);
     * with ink off, the point-to-segment fallback runs and selection is identical
     * to before, so I6 does not flip the I2 default-on switch. Derived/cached
     * only — never persisted; `StrokeCodec` stays canonical (Adoption principle 2).
     */
    private val meshCache = StrokeMeshCache()

    // ── Icon pixel grid (phase 15.3) ─────────────────────────────────────

    /** Snap-to-pixel + keyline overlay on icon artboards. On by default. */
    val iconPixelGrid: StateFlow<Boolean> = palettePrefsStore.prefs
        .map { it.iconPixelGrid }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    fun setIconPixelGrid(enabled: Boolean) {
        viewModelScope.launch {
            palettePrefsStore.setIconPixelGrid(enabled)
        }
    }

    fun setTitle(title: String) {
        _note.update { it.copy(title = title) }
        scheduleAutosave()
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
        colorPickerTargetsShapeFill = false
        _colorPickerOpen.value = true
    }

    /**
     * Phase 10.2 — open the picker with the shape-fill slot as the target.
     * No PEN fallback: a shape tool stays selected so the picked colour is
     * immediately visible on the next rubber-band preview.
     */
    fun openShapeFillColorPicker() {
        colorPickerTargetsShapeFill = true
        _colorPickerOpen.value = true
    }

    fun dismissColorPicker() {
        _colorPickerOpen.value = false
        _pendingAiRecolorScope.value = null
        colorPickerTargetsShapeFill = false
    }

    /**
     * Apply [colorArgb] to the active ink tool and record it on the recents
     * list. The picker stays responsible for parsing the colour; we just
     * propagate. Fire-and-forget for the recents write — a failed datastore
     * commit is non-fatal and would just mean the colour isn't pre-loaded
     * next time.
     */
    fun confirmColorPick(colorArgb: Int) {
        // AI recolor path — the picker was opened from the AI sheet's Recolor
        // quick action, so apply the colour to the staged scope as a model
        // edit rather than changing the active ink tool.
        val recolorScope = _pendingAiRecolorScope.value
        if (recolorScope != null) {
            _pendingAiRecolorScope.value = null
            _colorPickerOpen.value = false
            applyAiRecolor(colorArgb, recolorScope)
            return
        }
        if (colorPickerTargetsShapeFill) {
            // Phase 10.2 — route to the shape-fill slot; picking a colour
            // implies the user wants the fill on.
            palette.setFillColor(colorArgb)
            palette.setFillEnabled(true)
            colorPickerTargetsShapeFill = false
        } else {
            palette.setColor(palette.lastInkTool, colorArgb)
        }
        _colorPickerOpen.value = false
        viewModelScope.launch {
            recentColorsStore.record(colorArgb)
        }
    }

    fun setBackgroundStyle(style: String) {
        if (_note.value.backgroundStyle == style) return
        _note.update { it.copy(backgroundStyle = style) }
        scheduleAutosave()
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

    /**
     * Sub-phase 11.3 — hold-to-snap shape recognition. The surface already
     * committed [item] as raw ink (via [addItem]); if the recognizer fires,
     * one `CompositeEdit("Recognized …")` swaps the stroke for the shape —
     * a single undo restores the raw ink. The shape inherits the stroke's
     * colour / width / layer / z and carries no fill, so the replacement is
     * exactly the user's outline, snapped.
     */
    fun onStrokeHoldRecognized(item: NoteItem) {
        val committed = items.firstOrNull { it.id == item.id } ?: return
        if (committed.kind != STROKE_KIND) return
        val samples = StrokeCodec.decode(committed.payload)
        val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
        val result = com.aichat.sandbox.ui.components.notes.ShapeRecognizer
            .recognize(samples, count) ?: return
        val shapeItem = NoteItem(
            noteId = resolvedNoteId,
            zIndex = committed.zIndex,
            kind = Shape.KIND,
            tool = null,
            colorArgb = committed.colorArgb,
            baseWidthPx = committed.baseWidthPx,
            payload = ShapeCodec.encode(result.shape),
            layerId = committed.layerId,
        )
        apply(EditorAction.CompositeEdit(
            description = "Recognized ${result.label}",
            added = listOf(shapeItem),
            removed = listOf(committed),
            modified = emptyList(),
        ))
    }

    /**
     * Phase I5 — the user accepted the live-beautify ghost. The surface already
     * committed [rawItem] as raw ink (via [addItem]); swap it for [beautified]
     * (same id / colour / width / layer / z, cleaned [NoteItem.payload]) as one
     * `CompositeEdit("Beautify")`, so a single undo restores the raw stroke.
     *
     * The swap goes through `modified` (matched by id), and [beautified] is a
     * plain `STROKE_KIND` item carrying a canonical [StrokeCodec] payload — the
     * AI edit-ops pipeline never sees ink (Adoption principle 2).
     */
    fun onStrokeBeautifyAccepted(rawItem: NoteItem, beautified: NoteItem) {
        val committed = items.firstOrNull { it.id == rawItem.id } ?: return
        if (committed.kind != STROKE_KIND) return
        // Re-anchor the cleaned payload onto the *stored* item so layer / z /
        // noteId assigned at commit time are preserved exactly.
        val cleaned = committed.copy(payload = beautified.payload)
        if (cleaned.payload.contentEquals(committed.payload)) return
        apply(EditorAction.CompositeEdit(
            description = "Beautify",
            added = emptyList(),
            removed = emptyList(),
            modified = listOf(committed to cleaned),
        ))
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
        scheduleAutosave()
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
        scheduleAutosave()
    }

    fun redo() {
        val action = future.removeLastOrNull() ?: return
        action.applyTo(items)
        if (action is EditorAction.FrameMutation) {
            _frames.value = action.after
        }
        past.addLast(action)
        updateUndoRedoState()
        scheduleAutosave()
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
        // Phase I6 — when the ink engine is on, back stroke selection with ink's
        // robust mesh/triangle intersection (catches strokes the loop *crosses*,
        // not just those whose samples land inside it). The lasso loop is
        // ear-clipped once; per stroke we register its canonical geometry and
        // test the cached mesh, falling back to the point-in-polygon loop when no
        // mesh is available. With ink off this whole path is skipped.
        val meshSelection = inkAuthoring.value
        val lassoTriangles: FloatArray =
            if (meshSelection) LassoTriangulation.triangulate(polygon, vertexCount) else FloatArray(0)
        if (meshSelection) {
            meshCache.retain(items.mapTo(HashSet(items.size)) { it.id })
        }
        for (item in items) {
            val hit: Boolean
            val itemBounds: FloatArray
            when (item.kind) {
                STROKE_KIND -> {
                    val samples = StrokeCodec.decode(item.payload)
                    val sampleCount = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                    val b = HitTest.boundsOf(samples, sampleCount) ?: continue
                    itemBounds = b
                    val loopHit = {
                        LassoController.strokeIntersectsPolygon(
                            samples, sampleCount, b, polygon, vertexCount, polyBounds,
                        )
                    }
                    hit = if (meshSelection &&
                        LassoController.boundsOverlap(b, polyBounds) &&
                        meshCache.register(item.id, item.payload, item.tool ?: "", item.baseWidthPx)
                    ) {
                        MeshHitTest.lassoSelectsStroke(meshCache.meshFor(item.id), lassoTriangles, loopHit)
                    } else {
                        loopHit()
                    }
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
                StickyCodec.KIND -> {
                    // 11.1 — same cheap AABB selector as images.
                    val b = StickyCodec.boundsOf(StickyCodec.decode(item.payload))
                    itemBounds = b
                    hit = b[2] >= polyBounds[0] && b[0] <= polyBounds[2] &&
                        b[3] >= polyBounds[1] && b[1] <= polyBounds[3]
                }
                ConnectorCodec.KIND -> {
                    // 11.2 — test the resolved segment's ends + midpoint,
                    // mirroring the Line shape's representative points.
                    val ep = resolveConnectorEndpoints(item)
                    itemBounds = floatArrayOf(
                        minOf(ep[0], ep[2]), minOf(ep[1], ep[3]),
                        maxOf(ep[0], ep[2]), maxOf(ep[1], ep[3]),
                    )
                    hit = HitTest.shapeIntersectsPolygon(
                        Shape.Line(ep[0], ep[1], ep[2], ep[3]),
                        polygon, vertexCount, polyBounds,
                    )
                }
                PathCodec.KIND -> {
                    // 12.1 — lasso against the flattened curve.
                    val payload = PathCodec.decode(item.payload)
                    val b = PathCodec.boundsOf(payload) ?: continue
                    itemBounds = b
                    hit = HitTest.pathIntersectsPolygon(payload, polygon, vertexCount, polyBounds)
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
        // Phase 10.4 — lassoing any member of a group selects the whole
        // group. Members on a locked layer stay inert, mirroring the lasso's
        // own locked-layer rule above.
        val expanded = expandSelectionToGroups(matched.toHashSet(), items) { item ->
            val layer = _layers.value.firstOrNull { it.id == item.layerId }
            layer == null || !layer.locked
        }
        _selection.value = expanded
        _selectionWorldBounds.value = if (expanded.size == matched.size) {
            LassoController.unionBounds(bounds)
        } else {
            recomputeSelectionBounds()
        }
        _selectionMatrix.value = StrokeTransform.IDENTITY
        // Drop back to the last ink tool so consecutive strokes don't reopen
        // a lasso on top of the existing selection.
        palette.select(palette.lastInkTool)
    }

    fun clearSelection() {
        // 12.3 — node-edit mode rides on the selection lifecycle: anything
        // that clears the selection (stray stroke, presentation start) also
        // backs out of node editing.
        _nodeEditTarget.value = null
        if (_selection.value.isEmpty()) return
        _selection.value = emptySet()
        _selectionWorldBounds.value = null
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    // ── Path node editing (sub-phase 12.3) ───────────────────────────────
    //
    // A single selected path can enter node-edit mode: the editor swaps the
    // selection overlay for [PathNodeEditor], drags live-mutate the item's
    // payload (no undo entries), and each finished gesture commits exactly
    // one CompositeEdit with the gesture-start payload as `before`.

    private val _nodeEditTarget = MutableStateFlow<String?>(null)
    val nodeEditTarget: StateFlow<String?> = _nodeEditTarget.asStateFlow()

    /**
     * True when the selection is exactly one path item (gates "Edit nodes").
     * 17.2 — multi-subpath payloads (boolean results with holes, imported
     * vectors) are now editable too; the editor's selection model carries a
     * `(subpath, anchor)` pair, so no single-subpath restriction remains.
     */
    fun selectionIsSinglePath(): Boolean {
        val ids = _selection.value
        if (ids.size != 1) return false
        val id = ids.first()
        return items.any { it.id == id && it.kind == PathCodec.KIND }
    }

    fun enterNodeEdit() {
        val ids = _selection.value
        if (ids.size != 1) return
        val item = items.firstOrNull { it.id == ids.first() } ?: return
        if (item.kind != PathCodec.KIND) return
        clearSelection()
        _nodeEditTarget.value = item.id
    }

    fun exitNodeEdit() {
        _nodeEditTarget.value = null
    }

    /**
     * 17.2 — the node editor deleted the path's last drawable anchors,
     * emptying every subpath. Remove the whole item (one `CompositeEdit`, so
     * undo restores it) and leave node-edit mode.
     */
    fun deleteNodeEditItem(itemId: String) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        apply(EditorAction.CompositeEdit(
            description = "Delete path",
            added = emptyList(),
            removed = listOf(item),
            modified = emptyList(),
        ))
        exitNodeEdit()
    }

    /**
     * Live drag preview: replace the item's payload directly, bypassing the
     * undo log. The matching [commitPathNodeGesture] turns the whole drag
     * into one undo entry.
     */
    fun previewPathNodeEdit(itemId: String, payload: PathCodec.PathPayload) {
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0 || items[idx].kind != PathCodec.KIND) return
        items[idx] = items[idx].copy(payload = PathCodec.encode(payload))
    }

    /**
     * End-of-gesture commit: the item currently holds the live-previewed
     * "after" payload; [beforePayload] is the gesture-start snapshot. The
     * item is restored to `before` first so [apply]'s replay leaves the
     * list exactly as a later undo/redo round-trip would.
     */
    fun commitPathNodeGesture(itemId: String, beforePayload: ByteArray, description: String) {
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val after = items[idx]
        if (after.payload.contentEquals(beforePayload)) return
        val before = after.copy(payload = beforePayload)
        items[idx] = before
        apply(EditorAction.CompositeEdit(
            description = description,
            added = emptyList(),
            removed = emptyList(),
            modified = listOf(before to after),
        ))
    }

    // ── Convert to path (sub-phase 12.4) ─────────────────────────────────

    /** True when the selection has anything shape→path / stroke→path can eat. */
    fun selectionHasConvertibles(): Boolean {
        val ids = _selection.value
        return items.any { it.id in ids && (it.kind == Shape.KIND || it.kind == STROKE_KIND) }
    }

    /**
     * Replace every selected shape / stroke with its bezier-path
     * equivalent — one `CompositeEdit("Convert to path")`, so a single undo
     * restores the originals byte-identical. Colour / width / layer / z /
     * group carry over; shape fill + stroke style move into the payload.
     * Arrows expand to two items (shaft + filled head); other selected
     * kinds are left untouched.
     */
    fun convertSelectionToPaths() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val removed = ArrayList<NoteItem>()
        val added = ArrayList<NoteItem>()
        fun pathItem(source: NoteItem, payload: PathCodec.PathPayload) = NoteItem(
            noteId = resolvedNoteId,
            zIndex = source.zIndex,
            kind = PathCodec.KIND,
            tool = null,
            colorArgb = source.colorArgb,
            baseWidthPx = source.baseWidthPx,
            payload = PathCodec.encode(payload),
            layerId = source.layerId,
            groupId = source.groupId,
        )
        for (item in items) {
            if (item.id !in ids) continue
            when (item.kind) {
                Shape.KIND -> {
                    val decoded = ShapeCodec.decode(item.payload)
                    val payloads = PathConversions.fromShape(decoded, item.colorArgb)
                    if (payloads.isEmpty()) continue
                    removed += item
                    payloads.forEach { added += pathItem(item, it) }
                }
                STROKE_KIND -> {
                    val samples = StrokeCodec.decode(item.payload)
                    val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                    val payload = PathConversions.fromStroke(samples, count) ?: continue
                    removed += item
                    added += pathItem(item, payload)
                }
            }
        }
        if (added.isEmpty()) return
        apply(EditorAction.CompositeEdit(
            description = "Convert to path",
            added = added,
            removed = removed,
            modified = emptyList(),
        ))
        // Select the conversions so the user can immediately edit nodes.
        _selection.value = added.mapTo(HashSet(added.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    // ── Outline stroke (phase 15.2) ───────────────────────────────────────

    /** True when the selection contains at least one freehand stroke. */
    fun selectionHasOutlinableStrokes(): Boolean {
        val ids = _selection.value
        return items.any { it.id in ids && it.kind == STROKE_KIND }
    }

    /**
     * Replace every selected stroke with its pressure-faithful filled
     * outline path ([PathConversions.fromStrokeOutline]) — the icon-studio
     * "outline stroke" primitive: the result combines cleanly with boolean
     * ops and exports as plain filled geometry. One
     * `CompositeEdit("Outline ink")` so a single undo restores the strokes
     * byte-identical. Colour becomes the path fill; layer / z / group carry
     * over; non-stroke selection members are left untouched.
     */
    fun outlineSelectionStrokes() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val removed = ArrayList<NoteItem>()
        val added = ArrayList<NoteItem>()
        for (item in items) {
            if (item.id !in ids || item.kind != STROKE_KIND) continue
            val samples = StrokeCodec.decode(item.payload)
            val payload = PathConversions
                .fromStrokeOutline(samples, item.tool, item.baseWidthPx)
                ?: continue
            removed += item
            added += NoteItem(
                noteId = resolvedNoteId,
                zIndex = item.zIndex,
                kind = PathCodec.KIND,
                tool = null,
                colorArgb = item.colorArgb,
                // The width is baked into the outline geometry; a zero-width
                // same-colour stroke keeps the boundary crisp on canvas
                // without fattening the silhouette.
                baseWidthPx = 0f,
                payload = PathCodec.encode(payload.copy(fillArgb = item.colorArgb)),
                layerId = item.layerId,
                groupId = item.groupId,
            )
        }
        if (added.isEmpty()) return
        apply(EditorAction.CompositeEdit(
            description = "Outline ink",
            added = added,
            removed = removed,
            modified = emptyList(),
        ))
        // Select the outlines so the user can immediately combine or style.
        _selection.value = added.mapTo(HashSet(added.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    /** Tap-like node edit (insert / delete / toggle) — commits immediately. */
    fun applyPathNodeEdit(itemId: String, payload: PathCodec.PathPayload, description: String) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        if (item.kind != PathCodec.KIND) return
        val encoded = PathCodec.encode(payload)
        if (encoded.contentEquals(item.payload)) return
        apply(EditorAction.CompositeEdit(
            description = description,
            added = emptyList(),
            removed = emptyList(),
            modified = listOf(item to item.copy(payload = encoded)),
        ))
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
        // Phase 10.4 — copies of grouped items stay grouped with each other,
        // under fresh ids so they're independent of the source group.
        val copies = remapGroupIds(originals.map { duplicate(it, paste = false) })
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
        val pasted = remapGroupIds(NoteClipboard.peek().map { duplicate(it, paste = true) })
        if (pasted.isEmpty()) return
        apply(EditorAction.AddItems(pasted))
        _selection.value = pasted.mapTo(HashSet(pasted.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    fun hasClipboardContent(): Boolean = !NoteClipboard.isEmpty()

    // ── Phase 10 — group / restyle / arrange ─────────────────────────────

    /**
     * True when the selection has restyleable vector items (shapes / paths;
     * stickies since 13.2 — they take fills and gradients).
     */
    fun selectionHasShapes(): Boolean {
        val ids = _selection.value
        return items.any {
            it.id in ids &&
                (it.kind == Shape.KIND || it.kind == PathCodec.KIND || it.kind == StickyCodec.KIND)
        }
    }

    /** True when the active selection contains at least one grouped item. */
    fun selectionHasGroup(): Boolean {
        val ids = _selection.value
        return items.any { it.id in ids && it.groupId != null }
    }

    /** Tag every selected item with a fresh shared groupId (10.4). */
    fun groupSelection() {
        val ids = _selection.value
        if (ids.size < 2) return
        val members = items.filter { it.id in ids }
        if (members.size < 2) return
        val groupId = UUID.randomUUID().toString()
        apply(EditorAction.CompositeEdit(
            description = "Group",
            added = emptyList(),
            removed = emptyList(),
            modified = members.map { it to it.copy(groupId = groupId) },
        ))
    }

    /** Strip the groupId from every selected member (10.4). */
    fun ungroupSelection() {
        val ids = _selection.value
        val members = items.filter { it.id in ids && it.groupId != null }
        if (members.isEmpty()) return
        apply(EditorAction.CompositeEdit(
            description = "Ungroup",
            added = emptyList(),
            removed = emptyList(),
            modified = members.map { it to it.copy(groupId = null) },
        ))
    }

    /**
     * Re-fill every selected shape / path (10.2, paths since 12.5). `null`
     * removes the fill. Picking a solid fill clears any gradient (13.2);
     * stickies switch to the solid colour too but ignore "No fill" — a
     * sticky is always opaque. One CompositeEdit so the restyle is a single
     * undo entry; other kinds in the selection are untouched.
     */
    fun setSelectionFill(fillArgb: Int?) {
        restyleSelectedShapes(
            description = if (fillArgb == null) "Remove fill" else "Set fill",
            reencode = { decoded ->
                ShapeCodec.encode(decoded.shape, fillArgb ?: 0, decoded.strokeStyle)
            },
            reencodePath = { payload ->
                PathCodec.encode(payload.copy(fillArgb = fillArgb ?: 0, gradient = null))
            },
            reencodeSticky = fillArgb?.let { solid ->
                { payload -> StickyCodec.encode(payload.copy(fillArgb = solid, gradient = null)) }
            },
        )
    }

    /**
     * 13.2 — gradient-fill every selected shape / path / sticky. The legacy
     * `fillArgb` becomes the first stop's colour so old builds and the
     * VectorDrawable export show a sensible solid fallback.
     */
    fun setSelectionGradient(gradient: FillStyle.Gradient) {
        val fallback = gradient.firstStopArgb
        restyleSelectedShapes(
            description = "Gradient fill",
            reencode = { decoded ->
                ShapeCodec.encode(decoded.shape, fallback, decoded.strokeStyle, gradient)
            },
            reencodePath = { payload ->
                PathCodec.encode(payload.copy(fillArgb = fallback, gradient = gradient))
            },
            reencodeSticky = { payload ->
                StickyCodec.encode(payload.copy(fillArgb = fallback, gradient = gradient))
            },
        )
    }

    /** Re-style every selected shape / path outline — a [ShapeCodec] STROKE_STYLE_* value. */
    fun setSelectionStrokeStyle(style: Int) {
        restyleSelectedShapes(
            description = "Line style",
            reencode = { decoded ->
                ShapeCodec.encode(decoded.shape, decoded.fillArgb, style.toByte(), decoded.gradient)
            },
            reencodePath = { payload ->
                PathCodec.encode(payload.copy(strokeStyle = style.toByte()))
            },
        )
    }

    private fun restyleSelectedShapes(
        description: String,
        reencode: (ShapeCodec.DecodedShape) -> ByteArray,
        reencodePath: (PathCodec.PathPayload) -> ByteArray,
        reencodeSticky: ((StickyCodec.StickyPayload) -> ByteArray)? = null,
    ) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val pairs = ArrayList<Pair<NoteItem, NoteItem>>()
        for (item in items) {
            if (item.id !in ids) continue
            val payload = when (item.kind) {
                Shape.KIND -> reencode(ShapeCodec.decode(item.payload))
                PathCodec.KIND -> reencodePath(PathCodec.decode(item.payload))
                StickyCodec.KIND -> reencodeSticky?.invoke(StickyCodec.decode(item.payload))
                    ?: continue
                else -> continue
            }
            if (payload.contentEquals(item.payload)) continue
            pairs += item to item.copy(payload = payload)
        }
        if (pairs.isEmpty()) return
        apply(EditorAction.CompositeEdit(description, emptyList(), emptyList(), pairs))
    }

    // ── Phase 13.1 — boolean ops ─────────────────────────────────────────

    /** True when ≥ 2 selected items are shapes / paths (gates "Combine"). */
    fun selectionCanCombine(): Boolean {
        val ids = _selection.value
        if (ids.size < 2) return false
        return items.count {
            it.id in ids && (it.kind == Shape.KIND || it.kind == PathCodec.KIND)
        } >= 2
    }

    /**
     * Combine the selected shapes / paths under [op] via the pure
     * flatten → clip → refit pipeline ([PathBooleanBridge]). Inputs are
     * ordered by zIndex ascending; the bottom-most item is the subject, so
     * Subtract removes everything stacked above it ("minus front"). One
     * `CompositeEdit("Union 2 paths")` removes the inputs and adds the
     * result as **one** path item — 16.1: all result rings (holes included)
     * land as subpaths of a single payload, so they punch through instead
     * of stacking as filled blobs. Empty results (disjoint intersect) are a
     * silent no-op.
     */
    fun combineSelection(op: PathBoolean.Op) {
        val ids = _selection.value
        val eligible = items
            .filter { it.id in ids && (it.kind == Shape.KIND || it.kind == PathCodec.KIND) }
            .sortedBy { it.zIndex }
        if (eligible.size < 2) return
        val geometries = eligible.map { item ->
            when (item.kind) {
                Shape.KIND -> PathConversions.fromShape(ShapeCodec.decode(item.payload), item.colorArgb)
                else -> listOf(PathCodec.decode(item.payload))
            }
        }
        val result = PathBooleanBridge.combine(geometries, op) ?: return
        // An area op should produce a visible area: the subject's fill when
        // it had one, else its stroke colour. Stroke styling rides along.
        val subject = eligible.first()
        val subjectFill: Int
        val subjectGradient: FillStyle.Gradient?
        val subjectStrokeStyle: Byte
        val subjectCapJoin: Int
        if (subject.kind == PathCodec.KIND) {
            val p = PathCodec.decode(subject.payload)
            subjectFill = if (p.fillArgb != 0) p.fillArgb else subject.colorArgb
            subjectGradient = p.gradient
            subjectStrokeStyle = p.strokeStyle
            subjectCapJoin = p.capJoin
        } else {
            val d = ShapeCodec.decode(subject.payload)
            subjectFill = if (d.fillArgb != 0) d.fillArgb else subject.colorArgb
            subjectGradient = d.gradient
            subjectStrokeStyle = d.strokeStyle
            subjectCapJoin = PathCodec.DEFAULT_CAP_JOIN
        }
        val added = listOf(
            NoteItem(
                noteId = resolvedNoteId,
                zIndex = subject.zIndex,
                kind = PathCodec.KIND,
                tool = null,
                colorArgb = subject.colorArgb,
                baseWidthPx = subject.baseWidthPx,
                payload = PathCodec.encode(result.copy(
                    fillArgb = subjectFill,
                    strokeStyle = subjectStrokeStyle,
                    capJoin = subjectCapJoin,
                    gradient = subjectGradient,
                )),
                layerId = subject.layerId,
            ),
        )
        val opName = when (op) {
            PathBoolean.Op.UNION -> "Union"
            PathBoolean.Op.SUBTRACT -> "Subtract"
            PathBoolean.Op.INTERSECT -> "Intersect"
            PathBoolean.Op.EXCLUDE -> "Exclude"
        }
        apply(EditorAction.CompositeEdit(
            description = "$opName ${eligible.size} paths",
            added = added,
            removed = eligible,
            modified = emptyList(),
        ))
        _selection.value = added.mapTo(HashSet(added.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    /**
     * Phase 17.5 — true when the selection holds at least one style-compatible
     * pair of path items (gates the local "Merge paths" action). Unlike
     * [selectionCanCombine] this is concatenation, not a boolean op, so the
     * inputs must already share colour / width / fill styling.
     */
    fun selectionCanMergePaths(): Boolean {
        val ids = _selection.value
        if (ids.size < 2) return false
        val paths = items.filter { it.id in ids && it.kind == PathCodec.KIND }
        for (i in paths.indices) {
            for (j in i + 1 until paths.size) {
                if (pathsMergeable(paths[i], paths[j])) return true
            }
        }
        return false
    }

    /**
     * Phase 17.5 follow-on — gate for the selection "Tidy" action: there's
     * something simplify / snap / merge could act on (a path or a stroke).
     * Tidy itself no-ops gracefully when nothing actually changes.
     */
    fun selectionCanTidy(): Boolean {
        val ids = _selection.value
        if (ids.isEmpty()) return false
        return items.any { it.id in ids && (it.kind == PathCodec.KIND || it.kind == STROKE_KIND) }
    }

    private fun pathsMergeable(a: NoteItem, b: NoteItem): Boolean =
        a.colorArgb == b.colorArgb && a.baseWidthPx == b.baseWidthPx &&
            PathMerge.compatible(PathCodec.decode(a.payload), PathCodec.decode(b.payload))

    /**
     * Phase 17.5 — fold style-compatible selected paths into one multi-subpath
     * path each (holes preserved as subpaths). Mixed selections are partitioned
     * by style, so each compatible run of ≥ 2 becomes one merged path while
     * incompatible singletons are left as-is. One `CompositeEdit("Merge N
     * paths")` removes the sources and adds the merged results.
     */
    fun mergeSelectionPaths() {
        val ids = _selection.value
        val paths = items
            .filter { it.id in ids && it.kind == PathCodec.KIND }
            .sortedBy { it.zIndex }
        if (paths.size < 2) return
        val groups = ArrayList<MutableList<NoteItem>>()
        outer@ for (p in paths) {
            for (g in groups) {
                if (pathsMergeable(g.first(), p)) {
                    g.add(p)
                    continue@outer
                }
            }
            groups.add(mutableListOf(p))
        }
        val removed = ArrayList<NoteItem>()
        val added = ArrayList<NoteItem>()
        for (g in groups) {
            if (g.size < 2) continue
            val merged = PathMerge.merge(g.map { PathCodec.decode(it.payload) }) ?: continue
            val base = g.first() // lowest zIndex — sources are zIndex-sorted
            removed += g
            added += NoteItem(
                noteId = resolvedNoteId,
                zIndex = base.zIndex,
                kind = PathCodec.KIND,
                tool = null,
                colorArgb = base.colorArgb,
                baseWidthPx = base.baseWidthPx,
                payload = PathCodec.encode(merged),
                layerId = base.layerId,
                groupId = base.groupId,
            )
        }
        if (added.isEmpty()) return
        apply(EditorAction.CompositeEdit(
            description = "Merge ${removed.size} paths",
            added = added,
            removed = removed,
            modified = emptyList(),
        ))
        _selection.value = added.mapTo(HashSet(added.size)) { it.id }
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    /**
     * Phase 17.5 follow-on — one-tap **tidy** over the selection: simplify
     * strokes, snap path anchors to the icon grid, and fold style-compatible
     * paths together ([NoteTidy]). Lands as a single `CompositeEdit("Tidy")`.
     * Grid snapping only applies to icons (the artboard grid); plain notes get
     * simplify + merge. No-ops when nothing changes.
     */
    fun tidySelection(selection: List<NoteItem>? = null) {
        val target = selection ?: items.filter { it.id in _selection.value }
        if (target.isEmpty()) return
        val step = if (_note.value.isIcon) BackgroundLayer.SPACING_WORLD else 0f
        val result = com.aichat.sandbox.data.notes.NoteTidy.tidy(
            items = target,
            gridStep = step,
            bounds = currentFrameBounds(),
            newItemNoteId = resolvedNoteId,
        )
        if (result.isEmpty) return
        apply(EditorAction.CompositeEdit(
            description = "Tidy",
            added = result.added,
            removed = result.removed,
            modified = result.modified,
        ))
        val removedIds = result.removed.mapTo(HashSet(result.removed.size)) { it.id }
        val newSelection = target.mapNotNull { it.id.takeUnless { id -> id in removedIds } }
            .toMutableSet()
        newSelection += result.added.map { it.id }
        _selection.value = newSelection
        _selectionWorldBounds.value = recomputeSelectionBounds()
        _selectionMatrix.value = StrokeTransform.IDENTITY
    }

    // ── Phase 13.3 — eyedropper + style copy/paste ───────────────────────
    /** True when exactly one styleable item is selected (gates "Copy style"). */
    fun selectionIsSingleStyleSource(): Boolean {
        val ids = _selection.value
        if (ids.size != 1) return false
        val item = items.firstOrNull { it.id == ids.first() } ?: return false
        return StyleTransfer.styleOf(item) != null
    }

    /** Lift the single selected item's style into the [StyleClipboard]. */
    fun copySelectionStyle() {
        val ids = _selection.value
        if (ids.size != 1) return
        val item = items.firstOrNull { it.id == ids.first() } ?: return
        StyleTransfer.styleOf(item)?.let { StyleClipboard.put(it) }
    }

    fun hasStyleClipboard(): Boolean = !StyleClipboard.isEmpty()

    /** Apply the copied style to every styleable selected item — one undo entry. */
    fun pasteStyleToSelection() {
        val style = StyleClipboard.peek() ?: return
        val ids = _selection.value
        if (ids.isEmpty()) return
        val pairs = items
            .filter { it.id in ids }
            .mapNotNull { item -> StyleTransfer.applyTo(item, style)?.let { item to it } }
        if (pairs.isEmpty()) return
        apply(EditorAction.CompositeEdit("Paste style", emptyList(), emptyList(), pairs))
    }

    /**
     * The eyedropper: copy the single selected item's stroke colour into
     * the active ink tool and the recents list — same landing spot as a
     * colour-picker confirm.
     */
    fun pickColorFromSelection() {
        val ids = _selection.value
        if (ids.size != 1) return
        val item = items.firstOrNull { it.id == ids.first() } ?: return
        palette.setColor(palette.lastInkTool, item.colorArgb)
        viewModelScope.launch {
            recentColorsStore.record(item.colorArgb)
        }
    }

    /** Align the selected items to [edge] of their union bounds (10.5). */
    fun alignSelection(edge: AlignmentMath.AlignEdge) {
        val entries = selectionBoundsEntries() ?: return
        applyPerItemTranslations(AlignmentMath.align(entries, edge), "Align")
    }

    /** Equalize gaps between the selected items along [axis] (10.5). */
    fun distributeSelection(axis: AlignmentMath.Axis) {
        val entries = selectionBoundsEntries() ?: return
        applyPerItemTranslations(AlignmentMath.distribute(entries, axis), "Distribute")
    }

    private fun selectionBoundsEntries(): List<Pair<String, FloatArray>>? {
        val ids = _selection.value
        if (ids.size < 2) return null
        val entries = items.filter { it.id in ids }
            .mapNotNull { item -> itemBounds(item)?.let { item.id to it } }
        return entries.takeIf { it.size >= 2 }
    }

    private fun applyPerItemTranslations(
        matrices: Map<String, FloatArray>,
        description: String,
    ) {
        if (matrices.isEmpty()) return
        val pairs = items.filter { it.id in matrices.keys }
            .map { it to ItemTransformer.transform(it, matrices.getValue(it.id)) }
        if (pairs.isEmpty()) return
        apply(EditorAction.CompositeEdit(description, emptyList(), emptyList(), pairs))
        _selectionWorldBounds.value = recomputeSelectionBounds()
    }

    /**
     * Re-stack the selection (10.5). Reordering stays within each z band —
     * [ZOrderMath] reuses the band's existing zIndex slots, so highlighters
     * can never climb above the ink tier and the z counters stay valid.
     */
    fun reorderSelection(op: ZOrderMath.Op) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val entries = items.map {
            ZOrderMath.Entry(
                id = it.id,
                zIndex = it.zIndex,
                band = if (it.tool == StrokeRenderer.TOOL_HIGHLIGHTER) {
                    ZOrderMath.BAND_HIGHLIGHTER
                } else {
                    ZOrderMath.BAND_INK
                },
            )
        }
        val newZ = ZOrderMath.reorder(entries, ids, op)
        if (newZ.isEmpty()) return
        val pairs = items.filter { it.id in newZ.keys }
            .map { it to it.copy(zIndex = newZ.getValue(it.id)) }
        val description = when (op) {
            ZOrderMath.Op.BRING_TO_FRONT -> "Bring to front"
            ZOrderMath.Op.BRING_FORWARD -> "Bring forward"
            ZOrderMath.Op.SEND_BACKWARD -> "Send backward"
            ZOrderMath.Op.SEND_TO_BACK -> "Send to back"
        }
        apply(EditorAction.CompositeEdit(description, emptyList(), emptyList(), pairs))
    }

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

    // ── Sticky notes (sub-phase 11.1) ────────────────────────────────────

    /**
     * Surface dispatched a tap with the STICKY tool. Tap on an existing
     * sticky → open its inline editor; tap on empty canvas → drop a fresh
     * 160×160 sticky (one `AddItems` undo entry) and open the editor on it.
     */
    fun onStickyToolTap(worldX: Float, worldY: Float) {
        commitStickyEdit()
        commitTextEdit()
        val hit = findTopmostStickyAt(worldX, worldY)
        val target = if (hit != null) {
            hit
        } else {
            val payload = StickyCodec.newAt(
                centerX = worldX,
                centerY = worldY,
                fillArgb = palette.stickyFillColor,
            )
            val item = NoteItem(
                noteId = resolvedNoteId,
                zIndex = nextInkZIndex++,
                kind = StickyCodec.KIND,
                tool = null,
                colorArgb = 0,
                baseWidthPx = 0f,
                payload = StickyCodec.encode(payload),
                layerId = layerIdForNewItem(),
            )
            apply(EditorAction.AddItems(listOf(item)))
            item
        }
        val decoded = StickyCodec.decode(target.payload)
        stickyEditDraftBody = decoded.body
        _stickyEditTarget.value = StickyEditTarget(
            itemId = target.id,
            initialBody = decoded.body,
            worldX = decoded.minX + StickyCodec.TEXT_INSET_WORLD,
            worldY = decoded.minY + StickyCodec.TEXT_INSET_WORLD,
            fontSize = decoded.fontSize,
            maxWidthWorld = (decoded.width - 2 * StickyCodec.TEXT_INSET_WORLD)
                .coerceAtLeast(StickyCodec.TEXT_INSET_WORLD),
        )
        clearSelection()
    }

    /** Live body update from the Compose editor. */
    fun onStickyEditBodyChanged(body: String) {
        stickyEditDraftBody = body
    }

    /**
     * Commit the active sticky edit, if any. Idempotent. A changed body
     * lands as one `CompositeEdit("Edit sticky")` so undo restores the old
     * payload byte-identical; an unchanged body just closes the editor. An
     * emptied body keeps the sticky — an empty sticky is a valid artifact;
     * deletion goes through the selection menu / eraser like other items.
     */
    fun commitStickyEdit() {
        val target = _stickyEditTarget.value ?: return
        val body = stickyEditDraftBody
        _stickyEditTarget.value = null
        stickyEditDraftBody = ""
        if (body == target.initialBody) return
        val item = items.firstOrNull { it.id == target.itemId } ?: return
        if (item.kind != StickyCodec.KIND) return
        val decoded = StickyCodec.decode(item.payload)
        val updated = item.copy(payload = StickyCodec.encode(StickyCodec.withBody(decoded, body)))
        apply(EditorAction.CompositeEdit(
            description = "Edit sticky",
            added = emptyList(),
            removed = emptyList(),
            modified = listOf(item to updated),
        ))
    }

    /** Drop the active sticky edit without persisting the draft. */
    fun cancelStickyEdit() {
        if (_stickyEditTarget.value == null) return
        _stickyEditTarget.value = null
        stickyEditDraftBody = ""
    }

    private fun findTopmostStickyAt(worldX: Float, worldY: Float): NoteItem? {
        var best: NoteItem? = null
        var bestZ = Int.MIN_VALUE
        for (item in items) {
            if (item.kind != StickyCodec.KIND) continue
            val decoded = try {
                StickyCodec.decode(item.payload)
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (worldX < decoded.minX || worldX > decoded.maxX) continue
            if (worldY < decoded.minY || worldY > decoded.maxY) continue
            if (item.zIndex >= bestZ) {
                best = item
                bestZ = item.zIndex
            }
        }
        return best
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
                isIcon = _note.value.isIcon,
            )
        }
    }

    /** Footer Ask|Edit toggle. */
    fun setAiFooterMode(mode: AiFooterMode) {
        _aiSheetState.update { it.copy(footerMode = mode) }
    }

    /**
     * Single Send entry point for the footer text box. Routes by
     * [AiSideSheetState.footerMode]: ASK produces a prose reply via
     * [submitAiPrompt]; EDIT produces a staged preview via [submitAiEdit]. In
     * EDIT we pass the frozen scope explicitly — a null selection deliberately
     * edits the whole note/icon (the EDIT pipeline falls back to all items)
     * rather than grabbing whatever happens to be lasso-selected on the canvas.
     */
    fun submitAiFooter() {
        val snapshot = _aiSheetState.value
        if (snapshot.inputText.isBlank() || snapshot.isStreaming) return
        when (snapshot.footerMode) {
            AiFooterMode.ASK -> submitAiPrompt()
            AiFooterMode.EDIT -> {
                // 17.5 #1 — an empty icon artboard + an EDIT instruction means
                // "generate a new icon" (there's nothing to edit yet); a
                // populated canvas edits the existing geometry as before.
                val generate = snapshot.isIcon &&
                    snapshot.pendingSelection.isNullOrEmpty() &&
                    items.isEmpty()
                submitAiEdit(
                    description = if (generate) "AI Generate icon" else "AI edit",
                    userPrompt = snapshot.inputText.trim(),
                    selection = snapshot.pendingSelection,
                    generate = generate,
                )
            }
        }
    }

    /**
     * One-tap icon design action. The first four route through the model-backed
     * EDIT pipeline ([submitAiEdit]); [IconQuickAction.RECOLOR] opens the colour
     * picker and applies an AI recolor with the chosen colour (see
     * [_pendingAiRecolorScope]). All operate on the frozen scope, falling back
     * to the whole icon when nothing is selected.
     */
    fun submitIconQuickAction(action: IconQuickAction) {
        if (_aiSheetState.value.isStreaming) return
        val scope = _aiSheetState.value.pendingSelection
        when (action) {
            // 17.5 #2 — refine the selected sketch (or the whole icon) into a
            // clean vector placed beside it; the footer text, if any, steers
            // the redraw and enables the annotate-and-iterate loop.
            IconQuickAction.MAKE_REAL -> refineSketch(_aiSheetState.value.inputText)
            IconQuickAction.SIMPLIFY -> submitAiEdit(
                CannedEditAction.SIMPLIFY.undoDescription, CannedEditAction.SIMPLIFY.prompt, scope,
            )
            IconQuickAction.FLAT_STYLE -> submitAiEdit(
                CannedEditAction.FLAT_STYLE.undoDescription, CannedEditAction.FLAT_STYLE.prompt, scope,
            )
            IconQuickAction.ADD_DETAIL -> submitAiEdit(
                CannedEditAction.ADD_DETAIL.undoDescription, CannedEditAction.ADD_DETAIL.prompt, scope,
            )
            IconQuickAction.AUTO_SHAPE -> submitAiEdit(
                CannedEditAction.AUTO_SHAPE.undoDescription, CannedEditAction.AUTO_SHAPE.prompt, scope,
            )
            IconQuickAction.RECOLOR -> {
                // Open the picker without the ink-tool side effect of
                // `openColorPicker` — this pick feeds an AI recolor, not the pen.
                _pendingAiRecolorScope.value = scope ?: items.toList()
                _colorPickerOpen.value = true
            }
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
     * A6 fix — re-point the frozen scope at whatever is lasso-selected on the
     * canvas right now. The scope is captured once at open time and held for
     * the sheet's life, so a user who selects something new mid-conversation
     * was silently still asking about the old selection; this gives them an
     * explicit "ask about *this* instead" without reopening the sheet. No-ops
     * when nothing is selected (the chip that calls this is hidden in that
     * case).
     */
    fun reScopeAiSheetToSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val snapshot = items.filter { it.id in ids }
        if (snapshot.isEmpty()) return
        _aiSheetState.update { it.copy(pendingSelection = snapshot) }
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
                    // A8 fix — make convert-to-text a single tap: the moment OCR
                    // succeeds, drop the recognized text onto the canvas (one
                    // undoable AddItems) instead of waiting for a second
                    // "Insert as text box" tap. The bubble then offers an
                    // "Insert again" re-placement rather than a first insert.
                    placeConvertTextBox(turnId)
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
        // A8 — the bubble's "Insert again" affordance routes here. Insertion
        // now happens automatically on OCR success (see [placeConvertTextBox]);
        // this re-places another copy without dismissing the sheet so the user
        // can drop a second box or recover after an undo.
        placeConvertTextBox(turnId)
    }

    /**
     * Drop the recognized text of a Done convert-to-text [turnId] onto the
     * canvas as a new text item (undoable via [EditorAction.AddItems]). Anchors
     * at the centre of the frozen selection bounds, or `(0, 0)` world for
     * whole-note scope. Marks the turn [AskTurn.convertInserted] so the UI can
     * reflect that the placement happened. Leaves the sheet open.
     */
    private fun placeConvertTextBox(turnId: String) {
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
        mutateTurn(turnId) { t -> t.copy(convertInserted = true) }
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
                PathCodec.KIND -> PathCodec.boundsOf(PathCodec.decode(item.payload))
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
    fun submitAiEdit(
        description: String,
        userPrompt: String,
        selection: List<NoteItem>? = null,
        generate: Boolean = false,
        refine: Boolean = false,
    ) {
        val snapshot = _aiSheetState.value
        if (snapshot.isStreaming) return
        // From-scratch generation deliberately ignores any frozen selection so
        // the model isn't asked to "edit" nothing. A refine (17.5 #2) keeps the
        // selection: it's the sketch the model rasterizes and redraws beside.
        val resolvedSelection = if (generate && !refine) null else selection
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
                generate = generate,
                refine = refine,
            )
        }
    }

    /**
     * Phase 17.5 #1 — generate a new icon on the current artboard in the style
     * of a few existing gallery icons. Lands as a staged preview (add_path /
     * add_shape ops) the user accepts or rejects, exactly like an AI edit.
     */
    fun generateIcon(prompt: String) {
        if (prompt.isBlank()) return
        submitAiEdit(
            description = "AI Generate icon",
            userPrompt = prompt,
            selection = null,
            generate = true,
        )
    }

    /**
     * Phase 17.5 #2 — "Make real" / annotate-and-iterate refine. Rasterizes
     * the selected sketch, asks the model to redraw it as a clean vector, and
     * stages the result *beside* the original (a placement offset) so the user
     * can compare, mark up, and refine again. [extraInstruction] (the footer
     * text, if any) lets the user steer or re-prompt the redraw. Falls back to
     * the whole-icon scope when nothing is selected.
     */
    fun refineSketch(extraInstruction: String = "") {
        if (_aiSheetState.value.isStreaming) return
        val scope = _aiSheetState.value.pendingSelection
            ?: items.filter { it.id in _selection.value }.takeIf { it.isNotEmpty() }
            ?: items.toList()
        if (scope.isEmpty()) return
        submitAiEdit(
            description = "AI Make real",
            userPrompt = extraInstruction.trim(),
            selection = scope,
            generate = true,
            refine = true,
        )
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
        // Auto-select model-authored geometry so the user can immediately see
        // and grab it (move / resize / tweak). This is essential on a clipped
        // icon artboard, where freshly-placed geometry is otherwise easy to
        // miss and awkward to lasso. No-op for edits that add nothing.
        val addedIds = pending.simulation.added.mapTo(HashSet()) { it.id }
        if (addedIds.isNotEmpty()) {
            _selection.value = addedIds
            _selectionWorldBounds.value = recomputeSelectionBounds()
            _selectionMatrix.value = StrokeTransform.IDENTITY
        }
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
            // Everything else is a model-backed EDIT: smoothing, shape
            // detection, pattern continuation, and the icon design actions
            // (Simplify / Flat style / Add detail) all share the same
            // description + prompt → preview path.
            CannedEditAction.AI_CLEAN_UP,
            CannedEditAction.AUTO_SHAPE,
            CannedEditAction.CONTINUE,
            CannedEditAction.SIMPLIFY,
            CannedEditAction.FLAT_STYLE,
            CannedEditAction.ADD_DETAIL -> submitAiEdit(
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

    /**
     * Phase 17.5 #2 — placement *target* for a refine result: a sketch-sized
     * box one sketch-width (plus a small gap) to the right of the original.
     * The cleaned vector is *fit* into this box (uniform scale, aspect
     * preserved) so it lands at the sketch's size beside the source for
     * side-by-side comparison — regardless of the coordinate space the model
     * drew in. A refine only shows the model a cropped raster of the sketch,
     * so its raw output coordinates carry no world position or scale; fitting
     * (rather than a raw translation) is what keeps the result on canvas and
     * correctly sized. Null when the selection has no measurable bounds (the
     * result then lands at the model's own coordinates).
     */
    private fun refinePlacementTarget(selection: List<NoteItem>?): FloatArray? {
        val bounds = NoteRasterizer.computeBounds(selection ?: return null) ?: return null
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        if (width <= 0f || height <= 0f) return null
        val dx = width + width * REFINE_GAP_FRACTION
        return floatArrayOf(bounds[0] + dx, bounds[1], bounds[2] + dx, bounds[3])
    }

    /**
     * Phase 17.5 #2 — in-place fit target for an *icon* refine: the original
     * sketch's own bounds. The cleaned vector is fit onto the sketch's
     * footprint and replaces it (see [stagePendingEdit]), so it stays inside
     * the clipped artboard and lands exactly where the user drew. Null when
     * the sketch has no measurable bounds (the result then lands at the
     * model's own coordinates).
     */
    private fun refineInPlaceTarget(selection: List<NoteItem>?): FloatArray? {
        val bounds = NoteRasterizer.computeBounds(selection ?: return null) ?: return null
        if (bounds[2] - bounds[0] <= 0f || bounds[3] - bounds[1] <= 0f) return null
        return bounds
    }

    private suspend fun runStream(
        turnId: String,
        prompt: String,
        selection: List<NoteItem>?,
        mode: AskMode = AskMode.ASK,
        editDescription: String = "AI edit",
        generate: Boolean = false,
        refine: Boolean = false,
    ) {
        val modelId = _aiSheetState.value.activeModelId
            .ifEmpty { preferencesManager.defaultModel.first() }
        val creds = preferencesManager.credentialsFor(modelId)
        // 17.5 #1: from-scratch generation pulls gallery style references.
        // 17.5 #2: placement of a refine's cleaned vector. An icon is a
        // *clipped* artboard — "beside the sketch" lands off-canvas and
        // invisible — so we fit the result onto the sketch's own footprint and
        // replace it (Undo restores the sketch to compare). A regular note has
        // an infinite canvas, so the side-by-side comparison still works.
        val styleReferences = if (generate && !refine) loadStyleReferenceIcons() else emptyList()
        val isIcon = _note.value.isIcon
        val authoredFit = when {
            !refine -> null
            isIcon -> refineInPlaceTarget(selection)
            else -> refinePlacementTarget(selection)
        }
        val refineReplaceIds: Set<String> = if (refine && isIcon) {
            selection?.mapTo(HashSet()) { it.id } ?: emptySet()
        } else {
            emptySet()
        }
        val request = AskRequest(
            note = _note.value,
            allItems = items.toList(),
            selection = selection,
            userPrompt = prompt,
            modelId = modelId,
            baseUrl = creds.baseUrl,
            apiKey = creds.apiKey,
            mode = mode,
            layers = _layers.value,
            isIcon = _note.value.isIcon,
            generate = generate,
            styleReferences = styleReferences,
            refine = refine,
        )
        try {
            aiService.ask(request).collect { chunk ->
                // I4 / N1 — a designed brush is persisted to the user's brush
                // library (a suspend write), not staged as a canvas edit. Handle
                // it outside `mutateTurn`, whose lambda is a pure state transform.
                if (chunk is AiChunk.BrushDesign) {
                    val saved = saveDesignedBrush(chunk.spec)
                    mutateTurn(turnId) { turn ->
                        if (saved) turn.copy(
                            replyBuffer = "Saved brush “${chunk.spec.name}” to your library.",
                            state = TurnState.Done,
                        ) else turn.copy(state = TurnState.Error("Couldn't save the designed brush."))
                    }
                    return@collect
                }
                mutateTurn(turnId) { turn ->
                    when (chunk) {
                        is AiChunk.Delta -> turn.copy(replyBuffer = turn.replyBuffer + chunk.text)
                        is AiChunk.Complete -> turn.copy(state = TurnState.Done)
                        is AiChunk.Error -> turn.copy(state = TurnState.Error(chunk.message))
                        is AiChunk.EditPreview -> {
                            stagePendingEdit(chunk, editDescription, authoredFit, refineReplaceIds)
                            val message = chunk.doc.summary.ifBlank {
                                if (chunk.doc.ops.isEmpty()) "No changes proposed."
                                else "Preview ready (${chunk.doc.ops.size} ops)."
                            }
                            turn.copy(replyBuffer = message, state = TurnState.Done)
                        }
                        // Handled above via early return; unreachable here.
                        is AiChunk.BrushDesign -> turn
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
     * Phase I4 / N1 — kick off the AI brush designer. A pure text round-trip on
     * its own turn: the model returns a brush-spec JSON that becomes a new
     * user-scope `BrushPreset`. No selection, no canvas mutation.
     */
    fun designBrush(prompt: String, turnId: String) {
        val job = viewModelScope.launch {
            runStream(turnId = turnId, prompt = prompt, selection = null, mode = AskMode.DESIGN_BRUSH)
        }
        streamingJobs[turnId] = job
    }

    /**
     * Phase I4 / N1 — persist a designed [BrushSpec] as a user-scope preset,
     * assigning the next ordinal within its tool. Returns true on success.
     */
    private suspend fun saveDesignedBrush(spec: BrushSpec): Boolean = try {
        val ordinal = brushPresets.forTool(spec.tool).size
        brushPresets.saveUserPreset(spec.toPreset(ordinal))
        true
    } catch (t: Throwable) {
        Log.w("NoteEditorViewModel", "saveDesignedBrush failed", t)
        false
    }

    /**
     * Phase 17.5 #1 — load up to three other gallery icons, serialized as
     * [com.aichat.sandbox.data.notes.VectorCanvasJson], to seed the generation
     * system prompt with a concrete style reference. Skips the current note and
     * any empty icons. Best-effort: returns empty on any failure so generation
     * still proceeds (just without a style anchor).
     */
    private suspend fun loadStyleReferenceIcons(): List<String> = try {
        val icons = repository.observeIcons().first()
            .filter { it.id != resolvedNoteId }
        val refs = ArrayList<String>(3)
        for (icon in icons) {
            if (refs.size >= 3) break
            val iconItems = repository.getItems(icon.id)
            if (iconItems.isEmpty()) continue
            val serialized = com.aichat.sandbox.data.notes.VectorCanvasJson.serialize(
                items = iconItems,
                bounds = null,
                layers = repository.getLayers(icon.id),
            )
            refs += serialized.json
        }
        refs
    } catch (t: Throwable) {
        Log.w("NoteEditorViewModel", "style reference load failed", t)
        emptyList()
    }

    /**
     * Sub-phase 7.4 — convert an EDIT-mode terminal chunk into a staged
     * [PendingEdit] the UI can render as a translucent preview overlay.
     * Re-simulates the ops against the live item list (defense in depth —
     * the parser already validated, but items could have changed since the
     * request was made).
     */
    private fun stagePendingEdit(
        chunk: AiChunk.EditPreview,
        description: String,
        authoredFit: FloatArray? = null,
        replaceIds: Set<String> = emptySet(),
    ) {
        val simulation = EditPreviewController.simulate(
            currentItems = items.toList(),
            doc = chunk.doc,
            idMap = chunk.idMap,
            layerMap = chunk.layerMap,
            layers = _layers.value,
            newItemNoteId = _note.value.id,
            authoredFit = authoredFit,
        )
        // Replace-in-place (icon refine): drop the original sketch items in the
        // same edit so there's no invisible leftover. Guarded on the model
        // having actually produced geometry — an empty reply must not silently
        // wipe the sketch with nothing to show for it.
        val finalSimulation = if (replaceIds.isNotEmpty() && simulation.added.isNotEmpty()) {
            val alreadyRemoved = simulation.removed.mapTo(HashSet()) { it.id }
            val extraRemoved = items.filter { it.id in replaceIds && it.id !in alreadyRemoved }
            simulation.copy(removed = simulation.removed + extraRemoved)
        } else {
            simulation
        }
        _pendingEdit.value = PendingEdit(
            description = description,
            doc = chunk.doc,
            simulation = finalSimulation,
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
                ShapeCodec.encode(transformed, decoded.fillArgb, decoded.strokeStyle, decoded.gradient)
            }
            NoteItem.KIND_IMAGE -> {
                val payload = ImageItemCodec.decode(source.payload)
                ImageItemCodec.encode(ImageItemCodec.transform(payload, shifted))
            }
            StickyCodec.KIND -> {
                val payload = StickyCodec.decode(source.payload)
                StickyCodec.encode(StickyCodec.transform(payload, shifted))
            }
            ConnectorCodec.KIND -> {
                // A duplicated connector drops its bindings — the copy
                // shouldn't stay glued to the *original* endpoints' items —
                // and keeps the resolved geometry as plain fallback ends.
                val payload = ConnectorCodec.decode(source.payload)
                val ep = resolveConnectorEndpoints(source)
                ConnectorCodec.encode(
                    ConnectorCodec.transform(
                        payload.copy(
                            fromItemId = null, toItemId = null,
                            x0 = ep[0], y0 = ep[1], x1 = ep[2], y1 = ep[3],
                        ),
                        shifted,
                    )
                )
            }
            PathCodec.KIND -> {
                val payload = PathCodec.decode(source.payload)
                PathCodec.encode(PathCodec.transform(payload, shifted))
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
            itemBounds(item)?.let { rects.add(it) }
        }
        return LassoController.unionBounds(rects)
    }

    /** World-space `[minX, minY, maxX, maxY]` of a single item, kind-aware. */
    private fun itemBounds(item: NoteItem): FloatArray? = when (item.kind) {
        STROKE_KIND -> {
            val samples = StrokeCodec.decode(item.payload)
            val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            HitTest.boundsOf(samples, count)
        }
        TextItemCodec.KIND -> TextItemRenderer.boundsOf(item)
        Shape.KIND -> ShapeCodec.boundsOf(ShapeCodec.decode(item.payload).shape)
        NoteItem.KIND_IMAGE -> ImageItemCodec.boundsOf(ImageItemCodec.decode(item.payload))
        StickyCodec.KIND -> StickyCodec.boundsOf(StickyCodec.decode(item.payload))
        ConnectorCodec.KIND -> {
            val ep = resolveConnectorEndpoints(item)
            floatArrayOf(
                minOf(ep[0], ep[2]), minOf(ep[1], ep[3]),
                maxOf(ep[0], ep[2]), maxOf(ep[1], ep[3]),
            )
        }
        PathCodec.KIND -> PathCodec.boundsOf(PathCodec.decode(item.payload))
        else -> null
    }

    /**
     * Sub-phase 11.2 — resolve a connector's endpoints against the live item
     * list (bound ends follow their items; missing targets fall back).
     */
    private fun resolveConnectorEndpoints(item: NoteItem): FloatArray {
        val payload = ConnectorCodec.decode(item.payload)
        return ConnectorResolver.resolve(payload) { id ->
            items.firstOrNull { it.id == id && it.kind != ConnectorCodec.KIND }
                ?.let { itemBounds(it) }
        }
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
    suspend fun shareSvgForCurrentFrame(preservePressure: Boolean = false): Uri? {
        val bounds = currentFrameBounds() ?: return null
        commitTextEdit()
        save()
        return noteSvgExporter.exportSvg(
            note = _note.value,
            items = items.toList(),
            frameBounds = bounds,
            preservePressure = preservePressure,
            layers = _layers.value,
        )
    }

    /**
     * Phase 6.8 — vector-fidelity export. Same save-first contract as
     * [sharePng] / [sharePdf] so the resulting SVG reflects the user's most
     * recent geometry.
     */
    suspend fun shareSvg(preservePressure: Boolean = false): Uri {
        commitTextEdit()
        save()
        return noteSvgExporter.exportSvg(
            note = _note.value,
            items = items.toList(),
            preservePressure = preservePressure,
            layers = _layers.value,
        )
    }

    /**
     * Export the drawing as an Android VectorDrawable (`.xml`) at [sizeDp].
     * Same save-first contract as [shareSvg]. For icon notes the active
     * artboard frame defines the viewport; otherwise the content bounds do.
     * Returns the export result (URI + count of skipped, unsupported items).
     */
    suspend fun shareVectorXml(
        sizeDp: Int,
        preservePressure: Boolean = false,
    ): com.aichat.sandbox.data.notes.NoteVectorDrawableExporter.ExportResult {
        commitTextEdit()
        save()
        return noteVectorDrawableExporter.exportVectorDrawable(
            note = _note.value,
            items = items.toList(),
            sizeDp = sizeDp,
            frameBounds = currentFrameBounds(),
            preservePressure = preservePressure,
            layers = _layers.value,
        )
    }

    /**
     * Phase 15.4 — export the icon as a complete set (VectorDrawable XML at
     * 24/48/108 dp + SVG + 512 px PNG) zipped into one share-able file.
     * Same save-first contract as [shareSvg].
     */
    suspend fun shareIconSet(
        preservePressure: Boolean = false,
    ): com.aichat.sandbox.data.notes.NoteIconSetExporter.Result {
        commitTextEdit()
        save()
        return noteIconSetExporter.exportIconSet(
            note = _note.value,
            items = items.toList(),
            frameBounds = currentFrameBounds(),
            preservePressure = preservePressure,
        )
    }

    /**
     * Count of items that would be dropped by a VectorDrawable export (text +
     * images have no `<path>` equivalent). Computed off the wire format so it
     * matches the exporter exactly, including the frame-visibility filter for
     * icon notes. Lets [ExportVectorXmlDialog] show the warning only when it's
     * actually true.
     */
    suspend fun vectorExportSkippedCount(): Int = withContext(Dispatchers.Default) {
        com.aichat.sandbox.data.notes.NoteVectorDrawableExporter
            .render(items.toList(), sizeDp = 48, frameBounds = currentFrameBounds())
            .skippedCount
    }

    /**
     * Rasterize the export-eligible geometry (strokes + shapes only, no grid
     * background) so [ExportVectorXmlDialog] can show a live preview of the
     * resulting icon. Mirrors the exporter's viewport: the active artboard
     * frame for icon notes, content bounds otherwise. Returns null for an
     * empty / text-only drawing so the dialog can show a placeholder.
     */
    suspend fun renderVectorPreview(maxEdgePx: Int = 256): Bitmap? =
        withContext(Dispatchers.Default) {
            val supported = items.toList().filter {
                it.kind == NoteItem.KIND_STROKE ||
                    it.kind == com.aichat.sandbox.ui.components.notes.Shape.KIND ||
                    it.kind == PathCodec.KIND
            }
            if (supported.isEmpty()) return@withContext null
            val frame = currentFrameBounds()
            if (frame != null) {
                NoteRasterizer.renderForFrame(
                    items = supported,
                    frameBounds = frame,
                    maxEdgePx = maxEdgePx,
                    backgroundStyle = com.aichat.sandbox.ui.components.notes.BackgroundLayer.STYLE_PLAIN,
                )
            } else {
                NoteRasterizer.renderSelection(
                    items = supported,
                    maxEdgePx = maxEdgePx,
                    backgroundStyle = com.aichat.sandbox.ui.components.notes.BackgroundLayer.STYLE_PLAIN,
                )
            }
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

    /**
     * True for a brand-new note the user never put anything into — no drawn
     * items, no audio, and no real title. The editor uses this to avoid
     * persisting a junk "Untitled" row when the user opens a fresh note and
     * immediately backs out. Existing notes (opened by id) always return false,
     * so this never interferes with autosave of real content.
     */
    fun isBlankNewNote(): Boolean =
        routeArg == NOTE_ID_NEW &&
            items.isEmpty() &&
            audioClips.value.isEmpty() &&
            _note.value.title.isBlank()

    suspend fun save(): String {
        // An explicit save racing the initial DB load would persist the
        // still-empty item list over the note's real content (the DAO save
        // deletes-then-reinserts) — wait for the load to land first.
        initialLoad?.join()
        // The explicit save persists everything itself — a pending debounced
        // autosave would only repeat the work (or land after navigate-back).
        autosaveJob?.cancel()
        autosaveJob = null
        val id = persistSnapshot()
        // Fire-and-forget on the repository's singleton-scoped background scope
        // so the user isn't held on the editor while we rasterize — and so the
        // job survives this ViewModel being cleared on navigate-back.
        repository.renderThumbnailAsync(id)
        // Same survival guarantee for OCR. Internally debounced to a 2s window
        // (NoteRepository.OCR_DEBOUNCE_MS) so a save burst — e.g. drag-saves,
        // background-style toggle, back-press — coalesces into one ML Kit call.
        repository.runOcrAsync(id)
        return id
    }

    /**
     * Debounced in-session autosave. Armed by every content mutation (stroke
     * commit, erase, undo/redo, text/title/background/layer/frame edits); a
     * burst of edits collapses into one [persistSnapshot] call
     * [AUTOSAVE_DEBOUNCE_MS] after the last one. Skips blank brand-new notes
     * so backing out of an untouched note still leaves no junk row, and stays
     * inert until the initial DB load completes.
     *
     * Runs on [viewModelScope], so an autosave that hasn't fired by the time
     * the user navigates back is cancelled — that's fine, because the exit
     * path runs an explicit [save] first.
     */
    private fun scheduleAutosave() {
        if (!initialLoadComplete) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            if (isBlankNewNote()) return@launch
            persistSnapshot()
        }
    }

    /**
     * Flush a pending debounced autosave immediately. Called from the screen
     * on lifecycle ON_STOP: with only the 3-second debounce, backgrounding the
     * app and getting process-killed inside that window silently drops the
     * last edits. No-op when nothing is pending — the content is then already
     * persisted (or the note is a blank new one the exit path will handle).
     */
    fun flushPendingSave() {
        val pending = autosaveJob ?: return
        autosaveJob = null
        pending.cancel()
        if (!initialLoadComplete) return
        viewModelScope.launch {
            if (isBlankNewNote()) return@launch
            persistSnapshot()
        }
    }

    /**
     * Clean up after autosave when the user empties a brand-new note and backs
     * out (draw → undo → back): the exit path skips the save for blank new
     * notes, but an autosaved row may already exist — delete it so the list
     * doesn't resurrect content the user undid. No-op in every other case.
     */
    suspend fun discardAutosavedIfBlank() {
        autosaveJob?.cancel()
        autosaveJob = null
        if (persistedOnce && isBlankNewNote()) {
            repository.deleteNote(resolvedNoteId)
        }
    }

    /**
     * Persist the full in-memory snapshot (note row + items + layers +
     * frames). Shared by the explicit [save] path and the debounced autosave;
     * [persistMutex] keeps the two from interleaving their DAO transactions.
     */
    private suspend fun persistSnapshot(): String = persistMutex.withLock {
        val current = _note.value
        val sanitizedTitle = current.title.ifBlank { DEFAULT_TITLE }
        // Sub-phase 5.2: serialize the undo/redo log alongside the note row.
        // [EditorActionCodec.encode] applies the 256 KB cap; if both stacks
        // wind up empty we store `null` so a never-edited note doesn't carry
        // an empty `{schema:1,past:[],future:[]}` blob forever.
        val undoJson = EditorActionCodec.encode(past.toList(), future.toList())
        // Persist the geometry bounds the Note model reserves for thumbnails /
        // the icon list's size readout. These were never written, so reopened
        // icons showed a blank "—" dimension. Fall back to the existing values
        // (rather than collapsing to 0,0,0,0) when the note has no geometry.
        val contentBounds = com.aichat.sandbox.data.notes.NoteRasterizer.computeBounds(items.toList())
        // Persist the viewport for icons so reopening restores the exact view
        // (offset + zoom). Notes leave these null and keep their fit-on-open /
        // default behaviour. Falls back to the existing stored values when the
        // screen hasn't pushed a fresh viewport yet.
        val vp = pendingIconViewport.takeIf { current.isIcon }
        val toPersist = current.copy(
            title = sanitizedTitle,
            updatedAt = System.currentTimeMillis(),
            undoLogJson = undoJson,
            minX = contentBounds?.get(0) ?: current.minX,
            minY = contentBounds?.get(1) ?: current.minY,
            maxX = contentBounds?.get(2) ?: current.maxX,
            maxY = contentBounds?.get(3) ?: current.maxY,
            viewportOffsetX = vp?.get(0) ?: current.viewportOffsetX,
            viewportOffsetY = vp?.get(1) ?: current.viewportOffsetY,
            viewportScale = vp?.get(2) ?: current.viewportScale,
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
        // The sanitized "Untitled" fallback is for the DB row only — keep the
        // user's (possibly blank) in-memory title. An autosave mid-session must
        // not visibly stomp the title field, and `isBlankNewNote` relies on a
        // blank title to know the discard-on-exit path still applies.
        _note.value = toPersist.copy(title = current.title)
        persistedOnce = true
        toPersist.id
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
         * Quiet window after the last content mutation before the debounced
         * autosave fires. Long enough that a multi-stroke burst coalesces into
         * one write, short enough that a crash loses seconds, not a session.
         */
        const val AUTOSAVE_DEBOUNCE_MS = 3_000L

        /**
         * Quiet window before palette changes (tool / colour / width) are
         * written back to [ToolPalettePrefsStore] — a width-slider drag emits
         * dozens of states; one DataStore write at the end is plenty.
         */
        const val PALETTE_PERSIST_DEBOUNCE_MS = 500L

        /**
         * Highlighter strokes start at a large negative index so a note can
         * accumulate ~1M highlighter items before colliding with the ink tier.
         */
        const val HIGHLIGHTER_Z_BASE = -1_000_000

        private const val STROKE_KIND = "stroke"

        /** 17.5 #2 — gap (as a fraction of sketch width) between a refine's
         *  source sketch and the cleaned result placed beside it. */
        private const val REFINE_GAP_FRACTION = 0.2f

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

/**
 * Sub-phase 11.1 — open sticky-edit target. The world origin is the text
 * inset corner (rect min + [com.aichat.sandbox.ui.components.notes.StickyCodec.TEXT_INSET_WORLD])
 * so the Compose editor overlays exactly where the renderer lays the body out.
 */
data class StickyEditTarget(
    val itemId: String,
    val initialBody: String,
    val worldX: Float,
    val worldY: Float,
    val fontSize: Float,
    val maxWidthWorld: Float,
)
