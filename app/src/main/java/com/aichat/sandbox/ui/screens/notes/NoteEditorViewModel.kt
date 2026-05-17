package com.aichat.sandbox.ui.screens.notes

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.aichat.sandbox.ui.components.notes.TextItemRenderer
import com.aichat.sandbox.ui.components.notes.ToolPaletteState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val routeArg: String = savedStateHandle["noteId"] ?: NOTE_ID_NEW

    // Stable id for the "new" path: generated once, reused across saves so rapid
    // back-presses don't create duplicate notes.
    private val resolvedNoteId: String =
        if (routeArg == NOTE_ID_NEW) UUID.randomUUID().toString() else routeArg

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

    /** Live draft body — Compose `BasicTextField` writes this on every keystroke. */
    private var textEditDraftBody: String = ""

    init {
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
    }
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
