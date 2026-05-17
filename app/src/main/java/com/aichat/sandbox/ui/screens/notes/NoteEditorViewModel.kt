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
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.AiChunk
import com.aichat.sandbox.data.notes.AskRequest
import com.aichat.sandbox.data.notes.HandwritingOcr
import com.aichat.sandbox.data.notes.HandwritingOcr.OcrModelState
import com.aichat.sandbox.data.notes.NoteAiService
import com.aichat.sandbox.data.notes.NoteExporter
import com.aichat.sandbox.data.repository.ChatRepository
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.ui.components.notes.HitTest
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

    // In-memory undo / redo log. Capped to keep a long session bounded; the
    // oldest action is dropped silently when [UNDO_STACK_CAP] is exceeded.
    // Not persisted across editor exit (v1 scope).
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
                repository.getNote(routeArg)?.let { loaded -> _note.value = loaded }
                val loaded = repository.getItems(routeArg)
                items.clear()
                items.addAll(loaded)
                refreshZIndexCounters(loaded)
            }
        }
    }

    fun setTitle(title: String) {
        _note.update { it.copy(title = title) }
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
        future.addLast(action)
        updateUndoRedoState()
    }

    fun redo() {
        val action = future.removeLastOrNull() ?: return
        action.applyTo(items)
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
                else -> continue
            }
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
     * "Send to chat" reply action (sub-phase 2.8). Creates a brand-new
     * chat via [ChatRepository.createChat] using the user's current
     * default model + base URL (re-read from preferences inside
     * `createChat`), then returns the new chat id so the editor screen can
     * navigate with the reply text as a `?draftText=` arg.
     *
     * **Decision** (per the phase doc): we do NOT auto-send. The reply
     * lands in the composer so the user reviews and confirms what's
     * actually sent. The Phase 4 "Send to chat" picker will replace this
     * with an existing-chat sheet.
     *
     * Returns null for streaming, missing, or empty turns so the caller
     * can no-op safely.
     */
    suspend fun prepareSendReplyToChat(turnId: String): SendReplyResult? {
        val turn = _aiSheetState.value.turns.firstOrNull { it.id == turnId } ?: return null
        if (turn.state !is TurnState.Done) return null
        val body = turn.replyBuffer
        if (body.isEmpty()) return null
        val chat = chatRepository.createChat()
        return SendReplyResult(chatId = chat.id, draftText = body)
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

    private suspend fun runStream(
        turnId: String,
        prompt: String,
        selection: List<NoteItem>?,
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
        )
        try {
            aiService.ask(request).collect { chunk ->
                mutateTurn(turnId) { turn ->
                    when (chunk) {
                        is AiChunk.Delta -> turn.copy(replyBuffer = turn.replyBuffer + chunk.text)
                        is AiChunk.Complete -> turn.copy(state = TurnState.Done)
                        is AiChunk.Error -> turn.copy(state = TurnState.Error(chunk.message))
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

    suspend fun save(): String {
        val current = _note.value
        val sanitizedTitle = current.title.ifBlank { DEFAULT_TITLE }
        val toPersist = current.copy(
            title = sanitizedTitle,
            updatedAt = System.currentTimeMillis(),
        )
        repository.saveNote(toPersist, items = items.toList())
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
    }
}

/**
 * Result of [NoteEditorViewModel.prepareSendReplyToChat] (sub-phase 2.8).
 * Bundles the freshly-created chat id with the reply text so the editor
 * screen can issue a single `chat/{id}?draftText=…` navigation without
 * re-reading the turn from the side-sheet state.
 */
data class SendReplyResult(val chatId: String, val draftText: String)

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
