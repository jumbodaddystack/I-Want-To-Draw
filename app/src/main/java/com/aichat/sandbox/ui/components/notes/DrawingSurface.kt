package com.aichat.sandbox.ui.components.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.aichat.sandbox.data.ink.InkInterop
import com.aichat.sandbox.data.ink.MeshHitTest
import com.aichat.sandbox.data.ink.StrokeMeshCache
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.ui.screens.notes.LassoController
import kotlin.math.hypot

/**
 * Front-buffered ink surface (sub-phase 1.4) + infinite viewport & background
 * layer (sub-phase 1.5) + tool palette / per-tool rendering / erasers /
 * side-button mapping (sub-phase 1.6).
 *
 * Implementation notes:
 *
 *  - Plain [View] base class rather than the SurfaceView +
 *    `CanvasFrontBufferedRenderer` path mentioned as the primary option in
 *    `STYLUS_NOTES_PHASE_1.md` sub-phase 1.4. The front-buffer library
 *    (`androidx.graphics:graphics-core`) is still RC and its integration with
 *    `AndroidView` is fiddly. Latency reduction comes from variable-width
 *    per-segment rendering, quadratic-Bezier smoothing between sample
 *    midpoints, one-frame look-ahead via [MotionEventPredictor], and history
 *    iteration so we never drop S-Pen samples.
 *
 *  - Stroke samples are stored in **world** coordinates. The viewport
 *    transform is re-applied on every render. Committed strokes are
 *    rasterized to a screen-space scene bitmap on viewport changes (and on
 *    each commit); the live + predicted strokes are drawn directly each
 *    frame under `canvas.translate + scale`.
 *
 *  - Touch routing: any active stylus pointer wins (ink mode). Otherwise
 *    1-finger pan, 2-finger pinch.
 *
 *  - The S-Pen side button is captured at ACTION_DOWN — while held, the
 *    in-progress stroke is treated as an eraser regardless of the palette's
 *    selected tool. This matches how every other note app handles the
 *    barrel-button toggle.
 */
class DrawingSurface(context: Context) : View(context) {

    /** Pan / zoom state. */
    val viewport: ViewportController = ViewportController().also {
        it.onChanged = ::onViewportChanged
    }

    /** Per-note background pattern (plain / dot / line / graph). */
    var backgroundStyle: String = BackgroundLayer.STYLE_PLAIN
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Icon artboard world-rect `[minX, minY, maxX, maxY]`, or null for the
     * infinite (notes) canvas. When set, committed + live ink is clipped to
     * the artboard so the icon reads as a bounded canvas and stray ink past
     * the edge stays hidden (it remains in the data, just not rendered here).
     */
    var artboardClipBounds: FloatArray? = null
        set(value) {
            if (field?.contentEquals(value) == true || (field == null && value == null)) return
            field = value
            invalidate()
        }

    /**
     * Phase 15.3 — icon pixel grid. World units per icon pixel; when > 0
     * (and an artboard is set) shape endpoints and pen anchors quantize to
     * the grid and the keyline overlay renders. 0 = off (notes default).
     * The grid itself is the artboard's graph background — one 32-world
     * cell per icon pixel for every icon canvas size — so this only adds
     * the snap behaviour and the optical guides.
     */
    var pixelGridSpacingWorld: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Sketch-attachment mode (sub-phase 3.4). When enabled:
     *  - every pointer (finger or stylus) is routed through the ink path so
     *    the chat composer's sheet works without an S-Pen,
     *  - viewport pan / zoom is suppressed — the sheet renders at a fixed
     *    size, no infinite canvas, no pinch.
     * The Phase 1 note editor never sets this; defaults to off.
     */
    var sketchMode: Boolean = false

    /**
     * "Draw with finger" (user setting). When enabled, a single finger inks
     * through the same path as the stylus while two fingers still pan/zoom —
     * a second finger landing mid-stroke abandons the stroke and hands the
     * gesture to the viewport. A stylus keeps absolute priority either way.
     * Off by default: the classic routing (finger = viewport) is what S-Pen
     * users expect for palm rejection.
     */
    var fingerInkEnabled: Boolean = false

    /**
     * Phase I1 — ink-first authoring switch (default off, fallback-capable).
     * When on **and** an [InProgressStrokesView] is attached (see
     * [attachInkAuthoring]) **and** the in-flight tool is an ink tool, the live
     * stroke is authored by AndroidX Ink's front-buffered low-latency layer
     * instead of the custom quad-Bézier path. The pen-lift conversion
     * (`Stroke → StrokeCodec` via [InkInterop.fromStroke]) keeps the committed
     * payload byte-identical to a stroke drawn the old way, so storage, undo,
     * and the AI edit pipeline never see ink. Eraser / lasso / shapes / text /
     * etc. always stay on the existing path — ink only owns live ink authoring.
     *
     * Not default until the I2 parity checklist passes (see
     * `docs/ANDROIDX_INK_MIGRATION_PLAN.md`).
     */
    var inkAuthoringEnabled: Boolean = false

    /**
     * The attached ink front-buffer view, or null when ink authoring is off.
     * Owned by the [DrawingSurfaceView] host (added as a sibling overlay), wired
     * in via [attachInkAuthoring]. `internal` so the composable can attach /
     * detach it lazily without exposing ink types on the public surface API.
     */
    internal var inkAuthoringView: InProgressStrokesView? = null
        private set

    /** True while the in-flight stroke is being authored by ink (not our path). */
    private var inkStrokeActive: Boolean = false

    /** The ink id of the in-flight stroke, or null when none is active. */
    private var activeInkStrokeId: InProgressStrokeId? = null

    /**
     * Per-in-flight-stroke metadata captured at [startInkStroke], keyed by ink
     * id. ink hands the finished [Stroke] back asynchronously via
     * [onInkStrokesFinished]; this map carries the tool / colour / width /
     * recording origin / hold-recognition decision forward so the committed
     * [NoteItem] matches what the user actually drew.
     */
    private data class InkPendingStroke(
        val tool: Tool,
        val color: Int,
        val widthPx: Float,
        val fixedWidth: Boolean,
        val recordingOriginMs: Long?,
        val holdRecognition: Boolean,
    )

    private val inkPending: HashMap<InProgressStrokeId, InkPendingStroke> = HashMap()

    /** Converts each finished ink [Stroke] to a committed [NoteItem]. */
    private val inkFinishedListener = object : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            onInkStrokesFinished(strokes)
        }
    }

    private var sceneBitmap: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private var sceneDirty = true

    /** Committed strokes, kept on the view so we can re-rasterize on viewport changes. */
    private var committedItems: List<NoteItem> = emptyList()

    /** Per-note layer list (sub-phase 6.4). Empty list = legacy / unlayered note. */
    private var layerLookup: LayerLookup = LayerLookup(emptyList())

    /** Absolute path to the app's filesDir — feeds [ImageRenderer.draw]. Set lazily. */
    private var filesDir: java.io.File? = null

    /** Active live-stroke samples packed as `[x, y, pressure, tilt]` per sample, world coords. */
    private var liveSamples: FloatArray =
        FloatArray(INITIAL_SAMPLE_CAPACITY * StrokeCodec.FLOATS_PER_SAMPLE)
    private var liveSampleCount: Int = 0

    /** Look-ahead samples (world coords); re-derived every move event. */
    private var predictedSamples: FloatArray? = null
    private var predictedSampleCount: Int = 0

    /**
     * Phase 9.4 — monotonic anchor for the active audio recording.
     * When non-zero, [commitLiveStroke] encodes the stroke in v2 format
     * with per-sample timestamps. Zero (the default) keeps the legacy
     * v1 encoding path: strokes drawn without an active recording stay
     * binary-identical to pre-9.4 commits.
     */
    var recordingStartedAt: Long = 0L

    /**
     * Parallel array of per-sample timestamps (ms relative to
     * [recordingStartedAt]). Only populated when recording is active.
     */
    private var liveSampleTimes: FloatArray =
        FloatArray(INITIAL_SAMPLE_CAPACITY)

    private var hoverX: Float = 0f
    private var hoverY: Float = 0f
    private var hoverVisible: Boolean = false

    // Palette-driven config; set by [DrawingSurfaceView] every recomposition.
    private var paletteTool: Tool = Tool.PEN
    private var inkColor: Int = DEFAULT_INK_COLOR
    private var baseWidthPx: Float = DEFAULT_STROKE_WIDTH_PX
    private var areaEraserRadiusPx: Float = 24f

    // Phase: pen-size zoom scaling.
    // When true (default), the chosen pen width is anchored to *screen* pixels
    // at the moment a stroke starts: the committed world width is divided by
    // the zoom at stroke-start so the brush feels the same thickness on screen
    // at any zoom. When false, the width is taken as a world-space value (the
    // pre-change behaviour, where zooming out thins every stroke).
    private var screenAnchoredPenSize: Boolean = true

    // When true, newly drawn ink strokes keep a constant on-screen width at
    // any zoom (CAD / "fixed width pen"). Persisted per-item so committed
    // strokes keep rendering non-scaling. Independent of pressure dynamics.
    private var fixedWidthInk: Boolean = false

    // Phase 10.2 — shape fill + stroke style for newly committed shapes.
    // 0 fill means "no fill". Only consulted while a shape tool is active
    // (the frame tool reuses the rect rubber-band and must stay unfilled).
    private var shapeFillArgb: Int = 0
    private var shapeStrokeStyle: Byte = ShapeCodec.STROKE_STYLE_SOLID

    // 12.5 — packed PathCodec cap/join byte for newly committed paths.
    private var pathCapJoin: Int = PathCodec.DEFAULT_CAP_JOIN

    // 14.1 — run [InkBeautifier] on ink samples at commit time.
    private var beautifyInk: Boolean = false

    // 14.2 — route style encoded on the next connector commit.
    private var connectorRouteStyle: Byte = ConnectorCodec.ROUTE_STRAIGHT

    /** Tool actually used for the in-flight stroke (palette tool or side-button override). */
    private var strokeTool: Tool = Tool.PEN
    private var strokeColor: Int = DEFAULT_INK_COLOR
    private var strokeWidthPx: Float = DEFAULT_STROKE_WIDTH_PX

    /**
     * Effective world-space width for the in-flight stroke. With screen-anchored
     * sizing this is [strokeWidthPx] divided by the zoom captured at stroke
     * start, so the live preview and the committed stroke share one value.
     */
    private var strokeEffectiveWidthPx: Float = DEFAULT_STROKE_WIDTH_PX

    /** Whether the in-flight stroke should render at a constant on-screen width. */
    private var strokeFixedWidth: Boolean = false

    /** Texture id for the in-flight stroke — comes from the current brush preset (6.5 / 6.6). */
    private var strokeTextureId: String? = null
    private var strokeTextureForStroke: String? = null

    /** Strokes hit by the current eraser swipe; filtered out of the scene rasterization. */
    private val pendingErase: HashSet<String> = HashSet()

    /** Selected item ids (driven by the editor's selection state). Excluded from the scene bitmap. */
    private var selectedIds: Set<String> = emptySet()

    /**
     * Currently in-edit text item id (sub-phase 1.9). Skipped everywhere so the
     * Compose-side `TextItemEditor` overlay doesn't double-render on top of
     * the rasterized copy. Null when no text is being edited.
     */
    private var editingTextId: String? = null

    /**
     * Live-transform matrix applied to selected items at render time (sub-phase 1.8).
     * Identity = "selection sits at its baked-in position"; non-identity = a
     * Compose-side handle drag is in progress. Buffer layout matches
     * [StrokeTransform.SIZE].
     */
    private var selectionMatrix: FloatArray = StrokeTransform.IDENTITY
    private val selectionAndroidMatrix: Matrix = Matrix()
    private val textScratchMatrix: Matrix = Matrix()

    /** Lasso loop points (`[x0, y0, x1, y1, …]` world coords) — only when [strokeTool] == LASSO. */
    private var lassoPoints: FloatArray =
        FloatArray(INITIAL_SAMPLE_CAPACITY * LassoController.FLOATS_PER_VERTEX)
    private var lassoCount: Int = 0
    private val lassoPath: Path = Path()

    // ── Shape tool state (Phase 6.2) ─────────────────────────────────────
    // Active rubber-band in world coordinates. Non-null while the user is
    // dragging out a line / rect / ellipse / arrow / polygon-via-drag.
    private var shapeStartX: Float = 0f
    private var shapeStartY: Float = 0f
    private var shapeEndX: Float = 0f
    private var shapeEndY: Float = 0f
    private var shapeInProgress: Boolean = false
    // Polyline buffer for the polygon tool — captured at every ACTION_MOVE
    // and the final committed shape.
    private var polygonPoints: FloatArray = FloatArray(INITIAL_SAMPLE_CAPACITY * 2)
    private var polygonCount: Int = 0
    private val shapePaint: Paint = Paint().apply { isAntiAlias = true }

    // ── Snap visual feedback (Phase 6.3) ─────────────────────────────────
    // World-space marker that we paint on top of the canvas as a small
    // magenta dot to confirm an engaged snap. Decays via [snapAnchorTimestamp]
    // — non-zero while the most recent snap is still within fade window.
    private var snapAnchorX: Float = 0f
    private var snapAnchorY: Float = 0f
    private var snapAnchorTimestamp: Long = 0L

    /** Decoded sample cache keyed by item id — re-decoding every sample during an erase is wasteful. */
    private val decodedCache: HashMap<String, DecodedStroke> = HashMap()

    /**
     * Phase **I6** — derived, cached per-stroke ink `PartitionedMesh` layer
     * backing the eraser with robust geometry (the tip box vs the stroke's
     * *rendered width*, not just its centerline). Used **only** while
     * [inkAuthoringEnabled]; with ink off the eraser keeps the exact
     * [EraserHitTest] / [HitTest] path, so I6 doesn't change default behaviour.
     * Registered in lockstep with [decodedCache] in [replayItems] (so transform /
     * restyle / delete invalidate the mesh too — it keys on a content signature)
     * and queried in [eraseAtLastSample].
     */
    private val meshCache = StrokeMeshCache()

    private val livePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        color = DEFAULT_INK_COLOR
    }
    private val predictedPaint = Paint(livePaint).apply { alpha = PREDICTED_ALPHA }
    private val replayPaint = Paint(livePaint)
    private val hoverPaint = Paint().apply {
        style = Paint.Style.FILL
        color = HOVER_COLOR
        isAntiAlias = true
    }
    private val eraserCursorPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = ERASER_CURSOR_COLOR
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val brushCursorPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = BRUSH_CURSOR_COLOR
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = LASSO_COLOR
        strokeWidth = LASSO_STROKE_WIDTH_PX
        isAntiAlias = true
        // Dash on screen-space — drawn outside the viewport transform.
        pathEffect = DashPathEffect(floatArrayOf(LASSO_DASH_PX, LASSO_GAP_PX), 0f)
    }
    private val scratchPath = Path()

    private var motionPredictor: MotionEventPredictor? = null

    /** Invoked once per committed stroke (or shape). Caller assigns noteId / zIndex. */
    var strokeListener: ((NoteItem) -> Unit)? = null

    /**
     * Sub-phase 11.3 — fired right after [strokeListener] when the stylus was
     * held still ≥ [ShapeRecognizer.HOLD_DURATION_MS] before lifting on a
     * PEN / PENCIL stroke. The receiver runs [ShapeRecognizer] and, on a hit,
     * replaces the (already-committed) raw stroke with the shape as one
     * CompositeEdit so undo restores the ink.
     */
    var strokeHoldRecognizeListener: ((NoteItem) -> Unit)? = null

    /**
     * Phase I5 — fired when the user taps the live-beautify ghost to accept it.
     * Carries the already-committed [rawItem] and the [beautified] copy (same
     * id / colour / width / layer, cleaned payload); the receiver swaps them as
     * one undoable `CompositeEdit` so a single undo restores the raw ink.
     */
    var strokeBeautifyListener: ((rawItem: NoteItem, beautified: NoteItem) -> Unit)? = null

    /**
     * Phase I5 — the beautify ghost currently awaiting accept/decline, or null.
     * Set right after an ink stroke commits (when the clean would visibly alter
     * it); the next ACTION_DOWN resolves it: a tap on the candidate accepts, a
     * tap elsewhere declines and falls through to normal handling.
     */
    private var pendingBeautify: PendingBeautify? = null

    private class PendingBeautify(
        val rawItem: NoteItem,
        val beautified: NoteItem,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    ) {
        /** Whether world point (`wx`,`wy`) lands on the candidate (bbox + slop). */
        fun hit(wx: Float, wy: Float, slop: Float): Boolean =
            wx >= minX - slop && wx <= maxX + slop && wy >= minY - slop && wy <= maxY + slop
    }

    /** Translucent paint for the beautify ghost (I5); colour set per-stroke. */
    private val beautifyGhostPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Hold-to-snap tracking: the uptime + screen position of the last sample
    // that moved more than the touch slop. A lift whose event time is ≥
    // HOLD_DURATION_MS past this means "drew, then held still".
    private var strokeLastMoveUptime: Long = 0L
    private var strokeLastMoveX: Float = 0f
    private var strokeLastMoveY: Float = 0f

    /** Invoked at the end of an eraser swipe with all matched item ids. */
    var eraseListener: ((List<String>) -> Unit)? = null

    /**
     * Invoked when the lasso loop closes (ACTION_UP). The buffer is freshly
     * allocated, sized to `vertexCount * 2`, and in world coordinates. The
     * receiver (editor VM) runs hit-test and sets the selection.
     */
    var lassoListener: ((polygonWorld: FloatArray) -> Unit)? = null

    /**
     * Fired on stylus ACTION_DOWN that is *not* on a Compose-side selection
     * handle (which would intercept the event before us). The receiver clears
     * any active selection; lasso strokes re-establish a fresh selection on
     * commit.
     */
    var selectionShouldClearListener: (() -> Unit)? = null

    /**
     * Sub-phase 8.1 — fired when the user finishes dragging a rectangle
     * with the FRAME tool. Coordinates are world-space `[minX, minY, maxX, maxY]`.
     * Degenerate drags (no measurable motion) are suppressed so a stray
     * stylus tap doesn't create a 0×0 frame.
     */
    var frameDragListener: ((bounds: FloatArray) -> Unit)? = null

    /**
     * Sub-phase 8.1 — fired when the user taps (no drag) the canvas with
     * the FRAME tool active. Hands the world-space point so the editor can
     * resolve "which frame contains this tap" and update the current-frame
     * highlight.
     */
    var frameTapListener: ((worldX: Float, worldY: Float) -> Unit)? = null

    /**
     * Fired when the user taps the canvas with the TEXT tool active
     * (sub-phase 1.9). The receiver decides whether to begin a new text
     * item at `(worldX, worldY)` or open the editor for whichever text
     * item is under that point. The tap fires from both stylus and finger
     * because the text tool's interaction model is tap-only — no drawing,
     * no palm rejection conflicts.
     */
    var textTapListener: ((worldX: Float, worldY: Float) -> Unit)? = null

    /**
     * Sub-phase 11.1 — fired when the user taps the canvas with the STICKY
     * tool active. The receiver drops a fresh sticky at the tap point or
     * opens the inline editor for the sticky already under it.
     */
    var stickyTapListener: ((worldX: Float, worldY: Float) -> Unit)? = null

    // Viewport gesture state — only used for finger input (stylus has its own branch).
    private enum class GestureMode { NONE, PAN, PINCH }
    private var gestureMode: GestureMode = GestureMode.NONE
    private var panLastX: Float = 0f
    private var panLastY: Float = 0f
    private var pinchLastDist: Float = 0f
    // Pinch focal-point tracking — moving both fingers together pans, so the
    // viewport stays reachable when finger drawing claims the one-finger drag.
    private var pinchLastFocalX: Float = 0f
    private var pinchLastFocalY: Float = 0f

    // ── Text-tool tap detection (sub-phase 1.9) ──────────────────────────
    // Below the slop a single-pointer DOWN/UP counts as a tap and fires
    // [textTapListener]. Pinch zoom remains available via two fingers.
    private var textTapStartX: Float = 0f
    private var textTapStartY: Float = 0f
    private var textTapActive: Boolean = false
    private val tapSlopPx: Float =
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motionPredictor = MotionEventPredictor.newInstance(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionPredictor = null
    }

    /**
     * Replace the committed-item set and re-rasterize the scene. Items keep
     * their original list order — layer-aware sort + visibility filter is
     * applied during render through [LayerLookup.renderOrder] so eraser /
     * snap / hit-test paths still see the full set.
     */
    fun replayItems(items: List<NoteItem>) {
        val sorted = items.sortedBy { it.zIndex }
        committedItems = sorted
        // Re-decode lazily; drop entries no longer present so the cache
        // doesn't grow without bound across erase/undo cycles.
        val keep = sorted.mapTo(HashSet(sorted.size)) { it.id }
        if (decodedCache.isNotEmpty()) {
            decodedCache.keys.retainAll(keep)
        }
        // Phase I6 — keep the derived mesh cache in step with the canonical item
        // set. Deletes drop here; the content signature handles same-id edits.
        // Registering the cheap AABBs (no native mesh build) only when ink is on
        // primes the eraser's spatial prefilter without any cost when ink is off.
        meshCache.retain(keep)
        if (inkAuthoringEnabled) {
            for (item in sorted) {
                if (item.kind == NoteItem.KIND_STROKE) {
                    meshCache.register(item.id, item.payload, item.tool ?: "", item.baseWidthPx)
                }
            }
        }
        TextItemRenderer.evictUnused(keep)
        StickyRenderer.evictUnused(keep)
        pendingErase.clear()
        sceneDirty = true
        invalidate()
    }

    /**
     * Apply the palette's current selection. Live strokes already in flight
     * are unaffected — the change takes effect on the next ACTION_DOWN.
     */
    fun setToolConfig(
        tool: Tool,
        colorArgb: Int,
        widthPx: Float,
        areaEraserRadiusPx: Float,
        textureId: String? = null,
        shapeFillArgb: Int = 0,
        shapeStrokeStyle: Byte = ShapeCodec.STROKE_STYLE_SOLID,
        pathCapJoin: Int = PathCodec.DEFAULT_CAP_JOIN,
        beautifyInk: Boolean = false,
        connectorRouteStyle: Byte = ConnectorCodec.ROUTE_STRAIGHT,
        screenAnchoredPenSize: Boolean = true,
        fixedWidthInk: Boolean = false,
    ) {
        // 12.2 — leaving the pen tool commits whatever path is in progress;
        // an abandoned two-anchor stub is more useful than silent loss.
        if (paletteTool == Tool.PATH_PEN && tool != Tool.PATH_PEN) {
            commitPendingPath(close = false)
        }
        // I5 — a real tool change abandons any unresolved beautify ghost. Guard
        // on an actual change: setToolConfig runs on every recomposition, and
        // an unconditional clear would wipe the ghost the frame after it's set.
        if (tool != paletteTool && pendingBeautify != null) {
            pendingBeautify = null
            invalidate()
        }
        paletteTool = tool
        inkColor = colorArgb
        baseWidthPx = widthPx
        strokeTextureId = textureId
        this.areaEraserRadiusPx = areaEraserRadiusPx
        this.shapeFillArgb = shapeFillArgb
        this.shapeStrokeStyle = shapeStrokeStyle
        this.pathCapJoin = pathCapJoin
        this.beautifyInk = beautifyInk
        this.connectorRouteStyle = connectorRouteStyle
        this.screenAnchoredPenSize = screenAnchoredPenSize
        this.fixedWidthInk = fixedWidthInk
        invalidate()
    }

    /** Sub-phase 6.4 — replace the layer set and re-rasterize. */
    fun setLayers(layers: List<NoteLayer>) {
        layerLookup = LayerLookup(layers)
        sceneDirty = true
        invalidate()
    }

    /** Sub-phase 6.7 — caller supplies filesDir so [ImageRenderer] can resolve relative paths. */
    fun setFilesDir(dir: java.io.File) {
        filesDir = dir
    }

    /**
     * Update the active selection (sub-phase 1.8). Selected items are
     * excluded from the scene bitmap and re-rendered each frame with
     * [matrix] applied so a Compose-side transform gesture is reflected
     * live. Pass [StrokeTransform.IDENTITY] when no transform is in flight.
     */
    fun setSelection(ids: Set<String>, matrix: FloatArray) {
        val changedIds = ids != selectedIds
        val changedMatrix = !matrix.contentEquals(selectionMatrix)
        if (!changedIds && !changedMatrix) return
        selectedIds = ids
        selectionMatrix = matrix.copyOf()
        if (changedIds) sceneDirty = true
        invalidate()
    }

    /**
     * Hide a single text item from this surface so the Compose-side
     * `TextItemEditor` overlay can take over without a duplicate rasterized
     * copy showing through. Pass `null` to restore normal rendering.
     */
    fun setEditingTextId(id: String?) {
        if (editingTextId == id) return
        editingTextId = id
        sceneDirty = true
        invalidate()
    }

    /**
     * Sub-phase 11.1 — sticky whose body is being edited in the Compose
     * overlay. The rect keeps rendering; only the laid-out text is hidden.
     */
    private var editingStickyId: String? = null

    fun setEditingStickyId(id: String?) {
        if (editingStickyId == id) return
        editingStickyId = id
        sceneDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val previous = sceneBitmap
        val next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        sceneBitmap = next
        sceneCanvas = Canvas(next)
        previous?.recycle()
        sceneDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background runs in screen space so stroke widths stay perceptually
        // constant across zoom levels; the function handles paper fill too.
        BackgroundLayer.draw(canvas, viewport, backgroundStyle, width, height)

        if (sceneDirty) {
            rasterizeScene()
            sceneDirty = false
        }
        // For icons, clip the committed scene to the artboard so ink drawn past
        // the edge isn't shown (the canvas is bounded). Interaction affordances
        // below (selection, lasso, hover) stay unclipped.
        val clip = artboardClipBounds
        sceneBitmap?.let { bmp ->
            if (clip != null) {
                canvas.save()
                clipToArtboard(canvas, clip)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }

        // Phase 15.3 — icon keylines above the committed scene, below the
        // interaction affordances.
        if (pixelGridSpacingWorld > 0f && clip != null) {
            drawIconKeylines(canvas, clip)
        }

        drawSelectedItems(canvas)

        if (strokeTool.isLasso && lassoCount > 0) {
            drawLassoLoop(canvas)
        }

        if (shapeInProgress) {
            drawShapePreview(canvas)
        }

        if (connectorInProgress) {
            drawConnectorPreview(canvas)
        }

        if (penAnchors.isNotEmpty() || penCandidate != null) {
            drawPathPenPreview(canvas)
        }

        drawSnapMarker(canvas)

        // I5 — the live-beautify ghost sits above the committed scene so the
        // user sees the proposed clean overlaid on the raw stroke.
        drawBeautifyGhost(canvas)

        if (strokeTool.isInk && (liveSampleCount > 0 || predictedSampleCount > 0)) {
            canvas.save()
            // Clip the in-progress stroke to the artboard too (icons only) so it
            // matches the committed rendering as the user draws past the edge.
            clip?.let { clipToArtboard(canvas, it) }
            canvas.translate(viewport.offsetX, viewport.offsetY)
            canvas.scale(viewport.scale, viewport.scale)
            if (liveSampleCount > 0) {
                StrokeRenderer.configureToolPaint(
                    livePaint, strokeTool.id, strokeColor, strokeTextureForStroke,
                )
                StrokeRenderer.drawStrokePath(
                    canvas, livePaint, liveSamples, liveSampleCount,
                    strokeEffectiveWidthPx, strokeTool.id, scratchPath,
                    viewport.scale, strokeFixedWidth, ToolDynamics.MIN_SCREEN_WIDTH_PX,
                )
            }
            val predicted = predictedSamples
            if (predicted != null && predictedSampleCount > 0) {
                StrokeRenderer.configureToolPaint(
                    predictedPaint, strokeTool.id, strokeColor, strokeTextureForStroke,
                )
                // Predicted tail fades in to mask overshoot at direction changes.
                predictedPaint.alpha =
                    (predictedPaint.alpha * PREDICTED_ALPHA_FRACTION).toInt().coerceIn(0, 255)
                StrokeRenderer.drawStrokePath(
                    canvas, predictedPaint, predicted, predictedSampleCount,
                    strokeEffectiveWidthPx, strokeTool.id, scratchPath,
                    viewport.scale, strokeFixedWidth, ToolDynamics.MIN_SCREEN_WIDTH_PX,
                )
            }
            canvas.restore()
        }

        if (presentationMode) {
            drawLaser(canvas)
        }

        if (hoverVisible) {
            if (paletteTool.isEraser) {
                // The preview circle uses the palette tool (not strokeTool) so
                // the user sees what they're about to do before touching down.
                val r = if (paletteTool == Tool.ERASER_AREA) areaEraserRadiusPx
                else ToolPaletteState.STROKE_ERASER_RADIUS_PX
                canvas.drawCircle(hoverX, hoverY, r, eraserCursorPaint)
            } else {
                if (paletteTool.isInk) {
                    // Brush-size ring: radius = the actual on-screen brush
                    // radius for the current sizing mode, so the user sees how
                    // thick the stroke will land before touching down.
                    val ringRadius = brushCursorScreenRadius()
                    if (ringRadius >= BRUSH_CURSOR_MIN_RADIUS_PX) {
                        canvas.drawCircle(hoverX, hoverY, ringRadius, brushCursorPaint)
                    }
                }
                canvas.drawCircle(hoverX, hoverY, HOVER_RADIUS_PX, hoverPaint)
            }
        }
    }

    /**
     * On-screen radius (px) of the brush-size hover ring for the active ink
     * tool, mirroring how a stroke would actually land:
     *  - screen-anchored or fixed-width sizing → the base width is already in
     *    screen px, so the ring is `baseWidthPx / 2`;
     *  - world-space sizing → the width scales with zoom, so the ring is
     *    `baseWidthPx * viewport.scale / 2`.
     */
    private fun brushCursorScreenRadius(): Float {
        val screenWidth =
            if (screenAnchoredPenSize || fixedWidthInk) baseWidthPx
            else baseWidthPx * viewport.scale
        return screenWidth * 0.5f
    }

    /**
     * Clip [canvas] (in screen space) to the artboard world-rect [bounds],
     * mapping each edge through the current viewport. Caller owns the
     * surrounding save/restore.
     */
    private fun clipToArtboard(canvas: Canvas, bounds: FloatArray) {
        val left = viewport.worldToScreenX(bounds[0])
        val top = viewport.worldToScreenY(bounds[1])
        val right = viewport.worldToScreenX(bounds[2])
        val bottom = viewport.worldToScreenY(bounds[3])
        canvas.clipRect(left, top, right, bottom)
    }

    private val keylinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = KEYLINE_WIDTH_PX
        color = KEYLINE_COLOR
    }

    /**
     * Phase 15.3 — Material-style icon keylines over the artboard: keyline
     * circle (20/24 of the grid), live-area square (18/24) and a centre
     * cross. Screen space so the line weight stays zoom-stable; the pixel
     * grid itself comes from the artboard's graph background.
     */
    private fun drawIconKeylines(canvas: Canvas, bounds: FloatArray) {
        val left = viewport.worldToScreenX(bounds[0])
        val top = viewport.worldToScreenY(bounds[1])
        val right = viewport.worldToScreenX(bounds[2])
        val bottom = viewport.worldToScreenY(bounds[3])
        val w = right - left
        if (w <= 0f || bottom - top <= 0f) return
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        canvas.drawCircle(cx, cy, w * (10f / 24f), keylinePaint)
        val half = w * (9f / 24f)
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, keylinePaint)
        canvas.drawLine(cx, top, cx, bottom, keylinePaint)
        canvas.drawLine(left, cy, right, cy, keylinePaint)
    }

    /**
     * Phase 15.3 — quantize a world point to the icon pixel grid, or null
     * when the grid is inactive. Unconditional (no tolerance window) and
     * clamped to the artboard, mirroring [EditSnap]'s reducer contract.
     */
    private fun pixelQuantize(wx: Float, wy: Float): Snap.SnapResult? {
        val clip = artboardClipBounds ?: return null
        if (pixelGridSpacingWorld <= 0f) return null
        return EditSnap.quantizeInBounds(wx, wy, clip, pixelGridSpacingWorld)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Hover cursor follows the stylus only.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.onHoverEvent(event)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = event.x
                hoverY = event.y
                hoverVisible = true
                invalidate()
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoverVisible) {
                    hoverVisible = false
                    invalidate()
                }
                true
            }
            else -> super.onHoverEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // I5 — a staged beautify ghost intercepts the next touch-down: a tap on
        // the candidate accepts it (consumed here); any other tap declines and
        // falls through to normal handling below.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && pendingBeautify != null) {
            if (resolveBeautifyTap(event.x, event.y)) return true
        }
        if (presentationMode) {
            // Sub-phase 11.5 — presenting: the stylus draws transient laser
            // ink (never committed); fingers keep pan / pinch so the
            // presenter can still move around inside a frame.
            val stylusIdx = stylusPointerIndex(event)
            if (stylusIdx >= 0) {
                gestureMode = GestureMode.NONE
                return handleLaserEvent(event, stylusIdx)
            }
            return handleViewportEvent(event)
        }
        if (sketchMode) {
            // Fixed-size sheet canvas: every pointer is ink, no viewport
            // gestures. Pressure / tilt fall back to finger defaults when
            // not reported.
            return handleStylusEvent(event, 0)
        }
        if (paletteTool.isText || paletteTool.isSticky) {
            // Text tool: tap (stylus or finger) → create / edit text item.
            // Two-finger pinch still works so the user can zoom while in
            // text mode; pan is disabled because every move-with-one-finger
            // would otherwise either pan or commit a stray tap.
            // The sticky tool (11.1) shares the exact same tap state machine;
            // only the UP dispatch differs.
            return handleTextToolEvent(event)
        }
        if (paletteTool.isPathPen) {
            // Sub-phase 12.2 — multi-tap pen tool; anchors accumulate across
            // gestures until the path closes or the tool changes.
            return handlePathPenEvent(event)
        }
        if (paletteTool.isShape) {
            // Phase 6.2 — shape tools accept any pointer (stylus or finger)
            // and emit a rubber-band preview between ACTION_DOWN / UP.
            return handleShapeToolEvent(event)
        }
        if (paletteTool.isConnector) {
            // Sub-phase 11.2 — drag between items to bind a connector.
            return handleConnectorToolEvent(event)
        }
        if (paletteTool.isFrame) {
            // Sub-phase 8.1 — frame tool: drag a rectangle to create, tap
            // an existing frame to select it.
            return handleFrameToolEvent(event)
        }
        val stylusIdx = stylusPointerIndex(event)
        if (stylusIdx >= 0) {
            // Stylus + finger: ink wins, drop any in-flight viewport gesture
            // so a stray finger doesn't pan mid-stroke.
            gestureMode = GestureMode.NONE
            return handleStylusEvent(event, stylusIdx)
        }
        if (fingerInkEnabled) {
            return handleFingerInkEvent(event)
        }
        return handleViewportEvent(event)
    }

    /**
     * "Draw with finger" routing: a single finger inks exactly like a stylus;
     * a second finger landing mid-stroke abandons the stroke and converts the
     * gesture into a viewport pinch (zoom + two-finger pan). The pinch stays
     * sticky until every pointer lifts so a finger that lingers after the
     * zoom can't leave a stray mark.
     */
    private fun handleFingerInkEvent(event: MotionEvent): Boolean {
        if (gestureMode != GestureMode.NONE) {
            // Mid-viewport gesture (pinch, or pan after one finger lifted) —
            // keep feeding the viewport until ACTION_UP resets the mode.
            return handleViewportEvent(event)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger arrived: this is a zoom, not a stroke.
                cancelLiveStroke()
                if (event.pointerCount >= 2) {
                    pinchLastDist = pointerDistance(event, 0, 1)
                    pinchLastFocalX = (event.getX(0) + event.getX(1)) * 0.5f
                    pinchLastFocalY = (event.getY(0) + event.getY(1)) * 0.5f
                    gestureMode = GestureMode.PINCH
                }
                true
            }
            else -> handleStylusEvent(event, 0)
        }
    }

    private fun handleTextToolEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                textTapStartX = event.x
                textTapStartY = event.y
                textTapActive = true
                gestureMode = GestureMode.NONE
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger arrived — give up on the tap and switch to pinch.
                textTapActive = false
                if (event.pointerCount >= 2) {
                    pinchLastDist = pointerDistance(event, 0, 1)
                    gestureMode = GestureMode.PINCH
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == GestureMode.PINCH && event.pointerCount >= 2) {
                    val newDist = pointerDistance(event, 0, 1)
                    if (pinchLastDist > 1f && newDist > 1f) {
                        val factor = newDist / pinchLastDist
                        val focalX = (event.getX(0) + event.getX(1)) * 0.5f
                        val focalY = (event.getY(0) + event.getY(1)) * 0.5f
                        viewport.applyZoom(focalX, focalY, factor)
                    }
                    pinchLastDist = newDist
                } else if (textTapActive) {
                    val dx = event.x - textTapStartX
                    val dy = event.y - textTapStartY
                    if (hypot(dx, dy) > tapSlopPx) textTapActive = false
                }
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Drop back to a single pointer — but the tap is gone,
                // because we never count a tap that started life as a pinch.
                if (event.pointerCount - 1 == 1) gestureMode = GestureMode.NONE
                true
            }
            MotionEvent.ACTION_UP -> {
                if (textTapActive && gestureMode == GestureMode.NONE) {
                    val wx = viewport.screenToWorldX(event.x)
                    val wy = viewport.screenToWorldY(event.y)
                    if (paletteTool.isSticky) {
                        stickyTapListener?.invoke(wx, wy)
                    } else {
                        textTapListener?.invoke(wx, wy)
                    }
                }
                textTapActive = false
                gestureMode = GestureMode.NONE
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                textTapActive = false
                gestureMode = GestureMode.NONE
                true
            }
            else -> false
        }
    }

    private fun stylusPointerIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) return i
        }
        return -1
    }

    private fun handleStylusEvent(event: MotionEvent, idx: Int): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Side button held at DOWN time forces eraser for this stroke
                // regardless of the palette's selected tool.
                strokeTool = resolveStrokeTool(event)
                strokeColor = inkColor
                strokeWidthPx = baseWidthPx
                // Phase: pen-size zoom scaling — freeze the width model for this
                // stroke at touch-down. Fixed-width strokes always render at a
                // constant screen width; otherwise screen-anchored sizing stores
                // the chosen on-screen width as a world width by dividing out the
                // current zoom (so it lands the same thickness at any zoom).
                strokeFixedWidth = fixedWidthInk
                strokeEffectiveWidthPx = ToolDynamics.startWorldWidthPx(
                    baseWidthPx = baseWidthPx,
                    viewportScale = viewport.scale,
                    screenAnchored = screenAnchoredPenSize,
                    fixedWidth = strokeFixedWidth,
                    minDivScale = MIN_DIV_SCALE,
                )
                strokeTextureForStroke = strokeTextureId
                liveSampleCount = 0
                lassoCount = 0
                clearPredicted()
                hoverVisible = false
                pendingErase.clear()
                // Any stylus stroke that lands here (Compose overlay didn't
                // intercept it) means the user is starting fresh; clear any
                // active selection so a stray ink stroke doesn't get baked
                // into a stale transform.
                if (selectedIds.isNotEmpty()) selectionShouldClearListener?.invoke()
                inkStrokeActive = false
                if (strokeTool.isLasso) {
                    appendLassoVertex(event, idx)
                } else if (inkAuthoringEnabled && strokeTool.isInk && inkAuthoringView != null) {
                    // Ink-first authoring: hand the live stroke to ink's
                    // front buffer. On failure [startInkStroke] resets
                    // [inkStrokeActive] so the stroke is simply dropped rather
                    // than half-drawn through two engines.
                    inkStrokeActive = true
                    startInkStroke(event, idx)
                } else {
                    appendStylusSample(event, idx)
                    motionPredictor?.record(event)
                    if (strokeTool.isEraser) eraseAtLastSample()
                }
                strokeLastMoveUptime = event.eventTime
                strokeLastMoveX = event.getX(idx)
                strokeLastMoveY = event.getY(idx)
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (inkStrokeActive) {
                    addInkStroke(event, idx)
                    // Keep the hold-to-recognize timer running so a held lift
                    // still triggers shape recognition (handled at ACTION_UP).
                    if (hypot(event.getX(idx) - strokeLastMoveX, event.getY(idx) - strokeLastMoveY) > tapSlopPx) {
                        strokeLastMoveUptime = event.eventTime
                        strokeLastMoveX = event.getX(idx)
                        strokeLastMoveY = event.getY(idx)
                    }
                    return true
                }
                if (strokeTool.isLasso) {
                    for (h in 0 until event.historySize) {
                        appendLassoWorldPoint(
                            viewport.screenToWorldX(event.getHistoricalX(idx, h)),
                            viewport.screenToWorldY(event.getHistoricalY(idx, h)),
                        )
                    }
                    appendLassoVertex(event, idx)
                    invalidate()
                    return true
                }
                motionPredictor?.record(event)
                // S-Pen samples faster than the frame rate; iterating history
                // avoids dropped samples and jagged segments.
                for (h in 0 until event.historySize) {
                    appendLiveSample(
                        viewport.screenToWorldX(event.getHistoricalX(idx, h)),
                        viewport.screenToWorldY(event.getHistoricalY(idx, h)),
                        event.getHistoricalPressure(idx, h),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h),
                    )
                    if (strokeTool.isEraser) eraseAtLastSample()
                }
                appendStylusSample(event, idx)
                if (strokeTool.isEraser) eraseAtLastSample()
                if (strokeTool.isInk) updatePredictedFromPredictor() else clearPredicted()
                // 11.3 — only movement beyond the touch slop resets the hold
                // timer, so the natural micro-jitter of a held stylus still
                // counts as "still".
                if (hypot(event.getX(idx) - strokeLastMoveX, event.getY(idx) - strokeLastMoveY) > tapSlopPx) {
                    strokeLastMoveUptime = event.eventTime
                    strokeLastMoveX = event.getX(idx)
                    strokeLastMoveY = event.getY(idx)
                }
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                if (inkStrokeActive) {
                    finishInkStroke(
                        event, idx,
                        holdRecognition = (strokeTool == Tool.PEN || strokeTool == Tool.PENCIL) &&
                            event.eventTime - strokeLastMoveUptime >= ShapeRecognizer.HOLD_DURATION_MS,
                    )
                    inkStrokeActive = false
                    return true
                }
                clearPredicted()
                when {
                    strokeTool.isLasso -> commitLassoLoop()
                    strokeTool.isEraser -> commitEraseStroke()
                    else -> commitLiveStroke(
                        holdRecognition = (strokeTool == Tool.PEN || strokeTool == Tool.PENCIL) &&
                            event.eventTime - strokeLastMoveUptime >= ShapeRecognizer.HOLD_DURATION_MS,
                    )
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (inkStrokeActive) {
                    cancelInkStroke()
                    inkStrokeActive = false
                } else {
                    cancelLiveStroke()
                }
                true
            }
            else -> false
        }
    }

    /** Abandon the in-flight stroke / lasso / pending erase without committing. */
    private fun cancelLiveStroke() {
        liveSampleCount = 0
        lassoCount = 0
        clearPredicted()
        if (strokeTool.isEraser && pendingErase.isNotEmpty()) {
            // Undo the visual erase — items return to the scene.
            pendingErase.clear()
            sceneDirty = true
        }
        invalidate()
    }

    private fun appendLassoVertex(event: MotionEvent, idx: Int) {
        appendLassoWorldPoint(
            viewport.screenToWorldX(event.getX(idx)),
            viewport.screenToWorldY(event.getY(idx)),
        )
    }

    private fun appendLassoWorldPoint(x: Float, y: Float) {
        ensureLassoCapacity(lassoCount + 1)
        val base = lassoCount * LassoController.FLOATS_PER_VERTEX
        lassoPoints[base] = x
        lassoPoints[base + 1] = y
        lassoCount++
    }

    private fun ensureLassoCapacity(vertices: Int) {
        val needed = vertices * LassoController.FLOATS_PER_VERTEX
        if (needed <= lassoPoints.size) return
        var newSize = lassoPoints.size
        while (newSize < needed) newSize *= 2
        lassoPoints = lassoPoints.copyOf(newSize)
    }

    private fun commitLassoLoop() {
        val count = lassoCount
        if (count < MIN_LASSO_VERTICES) {
            lassoCount = 0
            invalidate()
            return
        }
        // Copy out exactly the populated range; the receiver retains the buffer.
        val polygon = FloatArray(count * LassoController.FLOATS_PER_VERTEX)
        System.arraycopy(lassoPoints, 0, polygon, 0, polygon.size)
        lassoCount = 0
        invalidate()
        lassoListener?.invoke(polygon)
    }

    /**
     * Determine the tool that should govern this stroke. Side-button held →
     * stroke-eraser override; otherwise the palette's current selection.
     */
    private fun resolveStrokeTool(event: MotionEvent): Tool {
        val buttonHeld = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        return if (buttonHeld) Tool.ERASER_STROKE else paletteTool
    }

    private fun appendStylusSample(event: MotionEvent, idx: Int) {
        appendLiveSample(
            viewport.screenToWorldX(event.getX(idx)),
            viewport.screenToWorldY(event.getY(idx)),
            event.getPressure(idx),
            event.getAxisValue(MotionEvent.AXIS_TILT, idx),
        )
    }

    private fun handleViewportEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panLastX = event.x
                panLastY = event.y
                gestureMode = GestureMode.PAN
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinchLastDist = pointerDistance(event, 0, 1)
                    pinchLastFocalX = (event.getX(0) + event.getX(1)) * 0.5f
                    pinchLastFocalY = (event.getY(0) + event.getY(1)) * 0.5f
                    gestureMode = GestureMode.PINCH
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.PAN -> {
                        val dx = event.x - panLastX
                        val dy = event.y - panLastY
                        panLastX = event.x
                        panLastY = event.y
                        if (dx != 0f || dy != 0f) viewport.applyPan(dx, dy)
                    }
                    GestureMode.PINCH -> {
                        if (event.pointerCount >= 2) {
                            val newDist = pointerDistance(event, 0, 1)
                            val focalX = (event.getX(0) + event.getX(1)) * 0.5f
                            val focalY = (event.getY(0) + event.getY(1)) * 0.5f
                            if (pinchLastDist > 1f && newDist > 1f) {
                                val factor = newDist / pinchLastDist
                                viewport.applyZoom(focalX, focalY, factor)
                            }
                            // Two fingers translating together = pan. Without
                            // this, finger-drawing mode would have no way to
                            // move around the canvas.
                            val fdx = focalX - pinchLastFocalX
                            val fdy = focalY - pinchLastFocalY
                            if (fdx != 0f || fdy != 0f) viewport.applyPan(fdx, fdy)
                            pinchLastDist = newDist
                            pinchLastFocalX = focalX
                            pinchLastFocalY = focalY
                        }
                    }
                    GestureMode.NONE -> Unit
                }
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Stepping back from pinch to pan: anchor on the remaining pointer.
                val remaining = event.pointerCount - 1
                if (remaining == 1) {
                    val keepIdx = if (event.actionIndex == 0) 1 else 0
                    panLastX = event.getX(keepIdx)
                    panLastY = event.getY(keepIdx)
                    gestureMode = GestureMode.PAN
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureMode = GestureMode.NONE
                true
            }
            else -> false
        }
    }

    private fun pointerDistance(event: MotionEvent, a: Int, b: Int): Float =
        hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))

    private fun onViewportChanged() {
        sceneDirty = true
        invalidate()
    }

    private fun appendLiveSample(x: Float, y: Float, pressure: Float, tilt: Float) {
        ensureLiveCapacity(liveSampleCount + 1)
        val base = liveSampleCount * StrokeCodec.FLOATS_PER_SAMPLE
        liveSamples[base] = x
        liveSamples[base + 1] = y
        liveSamples[base + 2] = pressure
        liveSamples[base + 3] = tilt
        if (recordingStartedAt != 0L) {
            liveSampleTimes[liveSampleCount] =
                (android.os.SystemClock.elapsedRealtime() - recordingStartedAt).toFloat()
        }
        liveSampleCount++
    }

    private fun ensureLiveCapacity(samples: Int) {
        val needed = samples * StrokeCodec.FLOATS_PER_SAMPLE
        if (needed <= liveSamples.size) return
        var newSize = liveSamples.size
        while (newSize < needed) newSize *= 2
        liveSamples = liveSamples.copyOf(newSize)
        // Keep the timestamp buffer in step with the sample capacity.
        if (liveSampleTimes.size < newSize / StrokeCodec.FLOATS_PER_SAMPLE) {
            liveSampleTimes = liveSampleTimes.copyOf(newSize / StrokeCodec.FLOATS_PER_SAMPLE)
        }
    }

    private fun updatePredictedFromPredictor() {
        val predicted = motionPredictor?.predict()
        if (predicted == null) {
            clearPredicted()
            return
        }
        try {
            if (liveSampleCount < 1) {
                clearPredicted()
                return
            }
            // Predicted events arrive in screen coords; convert to world so
            // they line up with the live samples under the viewport transform.
            val anchorIdx = (liveSampleCount - 1) * StrokeCodec.FLOATS_PER_SAMPLE
            val total = 1 + predicted.historySize + 1
            val needed = total * StrokeCodec.FLOATS_PER_SAMPLE
            val buf = predictedSamples?.takeIf { it.size >= needed }
                ?: FloatArray(needed).also { predictedSamples = it }

            buf[0] = liveSamples[anchorIdx]
            buf[1] = liveSamples[anchorIdx + 1]
            buf[2] = liveSamples[anchorIdx + 2]
            buf[3] = liveSamples[anchorIdx + 3]

            var dst = StrokeCodec.FLOATS_PER_SAMPLE
            for (h in 0 until predicted.historySize) {
                buf[dst] = viewport.screenToWorldX(predicted.getHistoricalX(h))
                buf[dst + 1] = viewport.screenToWorldY(predicted.getHistoricalY(h))
                buf[dst + 2] = predicted.getHistoricalPressure(h)
                buf[dst + 3] = predicted.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h)
                dst += StrokeCodec.FLOATS_PER_SAMPLE
            }
            buf[dst] = viewport.screenToWorldX(predicted.x)
            buf[dst + 1] = viewport.screenToWorldY(predicted.y)
            buf[dst + 2] = predicted.pressure
            buf[dst + 3] = predicted.getAxisValue(MotionEvent.AXIS_TILT)

            predictedSampleCount = total
        } finally {
            predicted.recycle()
        }
    }

    private fun clearPredicted() {
        predictedSampleCount = 0
    }

    private fun commitLiveStroke(holdRecognition: Boolean = false) {
        if (liveSampleCount < 1) {
            invalidate()
            return
        }
        // Phase 9.4 — encode in v2 (with `t`) when a recording is active;
        // otherwise stay on the v1 path so non-recording strokes are
        // binary-identical to pre-9.4 commits. Phase I5 — the beautify pass no
        // longer mutates the committed payload in place: the *raw* stroke is
        // committed here and [offerBeautify] surfaces a tap-to-accept ghost, so
        // a single undo always restores exactly what the pen drew.
        val payload = if (recordingStartedAt != 0L) {
            val packedV2 = FloatArray(liveSampleCount * StrokeCodec.FLOATS_PER_SAMPLE_V2)
            var src = 0
            var dst = 0
            var i = 0
            while (i < liveSampleCount) {
                packedV2[dst] = liveSamples[src]
                packedV2[dst + 1] = liveSamples[src + 1]
                packedV2[dst + 2] = liveSamples[src + 2]
                packedV2[dst + 3] = liveSamples[src + 3]
                packedV2[dst + 4] = liveSampleTimes[i]
                src += StrokeCodec.FLOATS_PER_SAMPLE
                dst += StrokeCodec.FLOATS_PER_SAMPLE_V2
                i++
            }
            StrokeCodec.encodeV2(packedV2)
        } else {
            val packed = FloatArray(liveSampleCount * StrokeCodec.FLOATS_PER_SAMPLE)
            System.arraycopy(liveSamples, 0, packed, 0, packed.size)
            StrokeCodec.encode(packed)
        }
        val item = buildStrokeItem(
            payload = payload,
            tool = strokeTool,
            color = strokeColor,
            // Phase: pen-size zoom scaling — store the zoom-resolved world
            // width so the stroke keeps the on-screen thickness it had while
            // being drawn. Equals strokeWidthPx when screen-anchoring is off.
            widthPx = strokeEffectiveWidthPx,
            fixedWidth = strokeFixedWidth,
        )
        committedItems = committedItems + item
        sceneDirty = true
        strokeListener?.invoke(item)
        // 11.3 — recognition runs *after* the normal commit so the raw ink
        // is already an undoable item; the receiver's replacement edit is a
        // second entry and one undo restores the stroke.
        if (holdRecognition) {
            strokeHoldRecognizeListener?.invoke(item)
        } else {
            // I5 — shape recognition takes precedence; otherwise offer the
            // live-beautify ghost on the just-committed raw stroke.
            offerBeautify(item)
        }
        liveSampleCount = 0
        invalidate()
    }

    /**
     * Build the committed stroke [NoteItem] shared by the legacy commit path
     * ([commitLiveStroke]) and the ink-authoring finish path
     * ([onInkStrokesFinished]) so both produce identical items. The VM rewrites
     * [NoteItem.zIndex] with a tool-aware value (highlighter sits in a negative
     * range so it always renders under ink); we append with zIndex 0 here for
     * instant feedback before the next `update()` lands.
     */
    private fun buildStrokeItem(
        payload: ByteArray,
        tool: Tool,
        color: Int,
        widthPx: Float,
        fixedWidth: Boolean,
    ): NoteItem = NoteItem(
        noteId = "",
        zIndex = 0,
        kind = STROKE_KIND,
        tool = tool.id,
        colorArgb = color,
        baseWidthPx = widthPx,
        payload = payload,
        fixedWidth = fixedWidth,
    )

    // ── Live beautify (phase I5) ──────────────────────────────────────────
    //
    // The pen-lift commits the *raw* stroke; if beautify is on and the clean
    // would visibly alter the just-committed ink stroke, we stage a ghost the
    // user can tap to accept. Accepting swaps raw → beautified as one undoable
    // edit (the VM does the swap); declining (a tap anywhere else, or the start
    // of a new stroke) leaves the raw stroke untouched. The beautified payload
    // is a plain canonical [StrokeCodec] payload — the AI pipeline never sees
    // ink (Adoption principle 2), and timestamps survive because we beautify in
    // the committed payload's own stride (v2 keeps its `t` lane).

    /**
     * Stage a beautify ghost for the freshly committed [rawItem] when the tool
     * is an ink tool and beautify is enabled. Computes the candidate in the
     * payload's own stride so v2 timestamps round-trip, and only offers when the
     * clean is visibly different ([InkBeautifier.Candidate.changed]).
     */
    private fun offerBeautify(rawItem: NoteItem) {
        if (!beautifyInk || !strokeTool.isInk) {
            pendingBeautify = null
            return
        }
        val v2 = StrokeCodec.isV2(rawItem.payload)
        val stride = if (v2) StrokeCodec.FLOATS_PER_SAMPLE_V2 else StrokeCodec.FLOATS_PER_SAMPLE
        val packed = if (v2) StrokeCodec.decodeWithT(rawItem.payload) else StrokeCodec.decode(rawItem.payload)
        val candidate = InkBeautifier.candidate(packed, stride)
        if (!candidate.changed) {
            pendingBeautify = null
            return
        }
        val payload = if (v2) StrokeCodec.encodeV2(candidate.samples) else StrokeCodec.encode(candidate.samples)
        val beautified = rawItem.copy(payload = payload)
        // World-space bbox of the candidate for the accept-tap hit-test.
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val n = candidate.samples.size / stride
        for (i in 0 until n) {
            val x = candidate.samples[i * stride]; val y = candidate.samples[i * stride + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
        }
        pendingBeautify = PendingBeautify(rawItem, beautified, minX, minY, maxX, maxY)
        invalidate()
    }

    /**
     * Resolve a pending beautify ghost against an ACTION_DOWN at screen
     * (`sx`,`sy`). Returns true iff the tap *accepted* the candidate (and so
     * should be consumed); a decline returns false and clears the ghost so the
     * caller handles the tap normally. No-op (false) when nothing is pending.
     */
    private fun resolveBeautifyTap(sx: Float, sy: Float): Boolean {
        val pending = pendingBeautify ?: return false
        pendingBeautify = null
        invalidate()
        val wx = viewport.screenToWorldX(sx)
        val wy = viewport.screenToWorldY(sy)
        // Slop scales the on-screen touch target into world units so the ghost
        // is tappable at any zoom.
        val slop = BEAUTIFY_TAP_SLOP_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
        return if (pending.hit(wx, wy, slop)) {
            strokeBeautifyListener?.invoke(pending.rawItem, pending.beautified)
            true
        } else {
            false
        }
    }

    /** Render the staged beautify ghost (I5) — a translucent cleaned outline. */
    private fun drawBeautifyGhost(canvas: Canvas) {
        val pending = pendingBeautify ?: return
        val xy = StrokeCodec.decode(pending.beautified.payload) // [x,y,p,t]*
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val n = xy.size / s
        if (n < 2) return
        scratchPath.rewind()
        scratchPath.moveTo(xy[0], xy[1])
        for (i in 1 until n) scratchPath.lineTo(xy[i * s], xy[i * s + 1])
        beautifyGhostPaint.color = pending.beautified.colorArgb
        beautifyGhostPaint.alpha = BEAUTIFY_GHOST_ALPHA
        // Width is drawn in world units (inside the scaled canvas), floored so a
        // thin stroke stays visible when zoomed out.
        beautifyGhostPaint.strokeWidth = pending.beautified.baseWidthPx
            .coerceAtLeast(ToolDynamics.MIN_SCREEN_WIDTH_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE))
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        canvas.drawPath(scratchPath, beautifyGhostPaint)
        canvas.restore()
    }

    // ── Ink-first authoring (phase I1) ────────────────────────────────────
    //
    // ink owns only the live, in-progress ink layer. On pen-lift the finished
    // `Stroke` is converted back to a `StrokeCodec` payload and committed
    // through the normal `strokeListener` pipeline, after which our scene
    // rasterization (StrokeRenderer) renders it — exactly like a stroke drawn
    // the old way. The two transforms passed to ink keep the stroke in world
    // coordinates (so the committed payload matches every other stored stroke)
    // while ink renders it on screen through the viewport.

    /** Attach the host's overlay [InProgressStrokesView] and start listening. */
    internal fun attachInkAuthoring(view: InProgressStrokesView) {
        if (inkAuthoringView === view) return
        detachInkAuthoring()
        inkAuthoringView = view
        view.addFinishedStrokesListener(inkFinishedListener)
    }

    /** Detach the overlay, abandoning any in-flight ink stroke without loss. */
    internal fun detachInkAuthoring() {
        if (inkStrokeActive || activeInkStrokeId != null) cancelInkStroke()
        inkStrokeActive = false
        inkAuthoringView?.removeFinishedStrokesListener(inkFinishedListener)
        inkAuthoringView = null
        // Drop any unresolved beautify ghost so switching engines mid-preview
        // can't leave a stale candidate pointing at a now-gone stroke.
        if (pendingBeautify != null) {
            pendingBeautify = null
            invalidate()
        }
    }

    /**
     * screen → world: `world = (screen - offset) / scale`. ink stores the
     * stroke in these (world) coordinates via `motionEventToWorldTransform`.
     */
    private fun motionEventToWorldMatrix(): Matrix {
        val s = viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
        return Matrix().apply {
            setScale(1f / s, 1f / s)
            postTranslate(-viewport.offsetX / s, -viewport.offsetY / s)
        }
    }

    private fun startInkStroke(event: MotionEvent, idx: Int) {
        val view = inkAuthoringView ?: run { inkStrokeActive = false; return }
        val brush = InkInterop.brushForTool(strokeTool.id, strokeColor, strokeEffectiveWidthPx)
        val pointerId = event.getPointerId(idx)
        val id = try {
            // strokeToWorldTransform defaults to identity (stroke == world at
            // creation); motionEventToViewTransform stays identity because the
            // overlay exactly covers this surface.
            view.startStroke(event, pointerId, brush, motionEventToWorldMatrix())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ink startStroke failed; falling back (stroke dropped)", e)
            inkStrokeActive = false
            return
        }
        val origin = if (recordingStartedAt != 0L) {
            android.os.SystemClock.elapsedRealtime() - recordingStartedAt
        } else {
            null
        }
        inkPending[id] = InkPendingStroke(
            tool = strokeTool,
            color = strokeColor,
            widthPx = strokeEffectiveWidthPx,
            fixedWidth = strokeFixedWidth,
            recordingOriginMs = origin,
            holdRecognition = false,
        )
        activeInkStrokeId = id
    }

    private fun addInkStroke(event: MotionEvent, idx: Int) {
        val view = inkAuthoringView ?: return
        val id = activeInkStrokeId ?: return
        val pointerId = event.getPointerId(idx)
        motionPredictor?.record(event)
        val predicted = motionPredictor?.predict()
        try {
            view.addToStroke(event, pointerId, id, predicted)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ink addToStroke failed", e)
        } finally {
            predicted?.recycle()
        }
    }

    private fun finishInkStroke(event: MotionEvent, idx: Int, holdRecognition: Boolean) {
        val view = inkAuthoringView ?: return
        val id = activeInkStrokeId ?: return
        inkPending[id]?.let { inkPending[id] = it.copy(holdRecognition = holdRecognition) }
        val pointerId = event.getPointerId(idx)
        try {
            view.finishStroke(event, pointerId, id)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ink finishStroke failed; stroke dropped", e)
            inkPending.remove(id)
        }
        activeInkStrokeId = null
    }

    private fun cancelInkStroke() {
        val view = inkAuthoringView
        val id = activeInkStrokeId
        if (view != null && id != null) {
            try {
                view.cancelStroke(id, null)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ink cancelStroke failed", e)
            }
        }
        id?.let { inkPending.remove(it) }
        activeInkStrokeId = null
    }

    /**
     * ink finished one or more strokes (UI thread). Convert each back to a
     * `StrokeCodec` payload — re-adding the recording-relative origin so the v2
     * audio-sync contract holds — commit it through the normal pipeline, then
     * tell ink to stop rendering it (we now own its pixels via the scene
     * rasterization). Strokes with no pending metadata (e.g. a failed start)
     * are still released so ink doesn't keep drawing them.
     */
    private fun onInkStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        for ((id, stroke) in strokes) {
            val meta = inkPending.remove(id) ?: continue
            val payload = InkInterop.fromStroke(stroke, meta.recordingOriginMs)
            val item = buildStrokeItem(payload, meta.tool, meta.color, meta.widthPx, meta.fixedWidth)
            committedItems = committedItems + item
            sceneDirty = true
            strokeListener?.invoke(item)
            // I5 — same beautify offer as the legacy path. ink's live input
            // smoothing has already shaped the wet stroke on device; the
            // committed inputs are still raw, so the beautify candidate is
            // computed identically here (and is headless-equivalent to the
            // legacy path — see [InkBeautifier] / [StrokeSmoothing]).
            if (meta.holdRecognition) {
                strokeHoldRecognizeListener?.invoke(item)
            } else {
                offerBeautify(item)
            }
        }
        inkAuthoringView?.removeFinishedStrokes(strokes.keys)
        invalidate()
    }

    private fun commitEraseStroke() {
        val matched = pendingErase.toList()
        liveSampleCount = 0
        if (matched.isEmpty()) {
            invalidate()
            return
        }
        // Remove from the local mirror so re-rasterizing skips them. The
        // ViewModel will hand back the authoritative item list shortly, but
        // we don't want a flash of un-erased ink in the meantime.
        val matchedSet = matched.toHashSet()
        committedItems = committedItems.filterNot { it.id in matchedSet }
        matched.forEach { decodedCache.remove(it); meshCache.invalidate(it) }
        pendingErase.clear()
        sceneDirty = true
        eraseListener?.invoke(matched)
        invalidate()
    }

    /**
     * Hit-test the most recently appended sample against every committed
     * stroke; add matches to [pendingErase] and mark the scene dirty so the
     * user sees the strokes vanish under the eraser tip.
     */
    private fun eraseAtLastSample() {
        if (liveSampleCount < 1) return
        val base = (liveSampleCount - 1) * StrokeCodec.FLOATS_PER_SAMPLE
        val px = liveSamples[base]
        val py = liveSamples[base + 1]
        val radius = currentEraserRadiusWorld()
        var changed = false
        for (item in committedItems) {
            if (item.id in pendingErase) continue
            // Layer-locked items are eraser-immune (sub-phase 6.4); hidden
            // items can't be hit because they aren't rendered.
            if (layerLookup.isLocked(item)) continue
            if (!layerLookup.isVisible(item)) continue
            // Per-kind hit decision lives in the pure [EraserHitTest] helper so
            // the I2 gate can lock in that *every* kind (not just ink strokes)
            // keeps erasing through [HitTest] — ink's stroke-only mesh never
            // displaces it. The decoded-stroke cache and connector routing are
            // injected because they depend on this view's live state.
            //
            // Phase I6 — only the *stroke* kind, and only while ink is on, defers
            // to ink's robust mesh hit-test (the eraser tip box vs the stroke's
            // rendered width), with the [HitTest] point-to-segment loop as the
            // fallback. Non-stroke kinds always stay on [EraserHitTest], so ink's
            // stroke-only mesh can never displace shapes / stickies / connectors /
            // paths (the I2 eraser-parity guarantee).
            val hit = if (inkAuthoringEnabled && item.kind == NoteItem.KIND_STROKE) {
                val decoded = decode(item)
                decoded != null && MeshHitTest.eraserHitsStroke(
                    meshCache.meshFor(item.id), px, py, radius,
                ) {
                    HitTest.bboxContainsPoint(decoded.bounds, px, py, radius) &&
                        HitTest.pointWithinStroke(decoded.samples, decoded.count, px, py, radius)
                }
            } else {
                EraserHitTest.hits(
                    item, px, py, radius,
                    decodeStroke = { stroke ->
                        decode(stroke)?.let { EraserHitTest.StrokeGeom(it.samples, it.count, it.bounds) }
                    },
                    connectorPolyline = { connector ->
                        ConnectorRouter.flatten(routeConnector(ConnectorCodec.decode(connector.payload)))
                    },
                )
            }
            if (hit) {
                pendingErase.add(item.id)
                changed = true
            }
        }
        if (changed) sceneDirty = true
    }

    private fun currentEraserRadiusScreenPx(): Float = when (strokeTool) {
        Tool.ERASER_AREA -> areaEraserRadiusPx
        else -> ToolPaletteState.STROKE_ERASER_RADIUS_PX
    }

    /** Eraser radius in world units (input samples are world-coord, so we divide by scale). */
    private fun currentEraserRadiusWorld(): Float =
        currentEraserRadiusScreenPx() / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)

    private fun decode(item: NoteItem): DecodedStroke? {
        val cached = decodedCache[item.id]
        if (cached != null) return cached
        val samples = StrokeCodec.decode(item.payload)
        val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
        if (count < 1) return null
        val bounds = HitTest.boundsOf(samples, count) ?: return null
        val fresh = DecodedStroke(samples, count, bounds)
        decodedCache[item.id] = fresh
        return fresh
    }

    /** Redraw the scene bitmap from [committedItems] under the current viewport. */
    private fun rasterizeScene() {
        val canvas = sceneCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (committedItems.isEmpty()) return
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        for (item in layerLookup.renderOrder(committedItems)) {
            if (item.id in pendingErase) continue
            // Selected items are drawn live in onDraw so a Compose-side
            // transform gesture renders without re-rasterizing every frame.
            if (item.id in selectedIds) continue
            // Currently in-edit text item is hidden — the Compose editor
            // owns the visual real estate.
            if (item.id == editingTextId) continue
            drawItemWithLayerOpacity(canvas, item)
        }
        canvas.restore()
    }

    private fun drawItemWithLayerOpacity(canvas: Canvas, item: NoteItem) {
        val opacity = layerLookup.opacity(item)
        val needsLayer = opacity < 0.999f
        if (needsLayer) {
            val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            canvas.saveLayerAlpha(null, alpha)
        }
        when (item.kind) {
            STROKE_KIND -> {
                val decoded = decode(item) ?: run {
                    if (needsLayer) canvas.restore()
                    return
                }
                StrokeRenderer.configureToolPaint(replayPaint, item.tool, item.colorArgb)
                StrokeRenderer.drawStrokePath(
                    canvas, replayPaint, decoded.samples, decoded.count,
                    item.baseWidthPx, item.tool, scratchPath,
                    viewport.scale, item.fixedWidth, ToolDynamics.MIN_SCREEN_WIDTH_PX,
                )
            }
            TextItemCodec.KIND -> TextItemRenderer.draw(canvas, item, textScratchMatrix)
            Shape.KIND -> ShapeRenderer.draw(canvas, item, replayPaint)
            NoteItem.KIND_IMAGE -> filesDir?.let { ImageRenderer.draw(canvas, item, it) }
            // 11.1 — while the Compose editor owns a sticky's body, the rect
            // still renders (so the board doesn't lose the note) but the text
            // is suppressed to avoid double-rendering under the editor.
            StickyCodec.KIND -> StickyRenderer.draw(
                canvas, item, drawBody = item.id != editingStickyId,
            )
            // 11.2 — bound endpoints re-resolve from the current item set on
            // every rasterize, so dragging a bound item carries the
            // connector along (and 14.2 re-routes it) without ever touching
            // its payload.
            ConnectorCodec.KIND -> {
                val payload = ConnectorCodec.decode(item.payload)
                ConnectorRenderer.draw(
                    canvas, item, payload, routeConnector(payload), replayPaint, scratchPath,
                )
            }
            // 12.1 — bezier paths.
            PathCodec.KIND -> PathRenderer.draw(canvas, item, replayPaint, scratchPath)
        }
        if (needsLayer) canvas.restore()
    }

    /**
     * Draw selected items on top of the scene bitmap with the live selection
     * matrix applied (world-space). When no transform is active the matrix is
     * identity and the items appear at their committed positions, just
     * routed through the live render path so the selection dashed overlay
     * (rendered separately by Compose) lines up.
     */
    private fun drawSelectedItems(canvas: Canvas) {
        if (selectedIds.isEmpty()) return
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        val applyMatrix = !StrokeTransform.isIdentity(selectionMatrix)
        if (applyMatrix) {
            selectionAndroidMatrix.setValues(selectionMatrix)
            canvas.concat(selectionAndroidMatrix)
        }
        for (item in committedItems) {
            if (item.id !in selectedIds) continue
            if (item.id in pendingErase) continue
            if (item.id == editingTextId) continue
            // Hide selected items on hidden layers so toggling visibility off
            // mid-drag actually hides them.
            if (!layerLookup.isVisible(item)) continue
            drawItemWithLayerOpacity(canvas, item)
        }
        canvas.restore()
    }

    /** Draws the live lasso loop as a dashed line in screen space. */
    private fun drawLassoLoop(canvas: Canvas) {
        if (lassoCount < 2) return
        lassoPath.reset()
        val scale = viewport.scale
        val ox = viewport.offsetX
        val oy = viewport.offsetY
        lassoPath.moveTo(
            lassoPoints[0] * scale + ox,
            lassoPoints[1] * scale + oy,
        )
        for (i in 1 until lassoCount) {
            lassoPath.lineTo(
                lassoPoints[i * LassoController.FLOATS_PER_VERTEX] * scale + ox,
                lassoPoints[i * LassoController.FLOATS_PER_VERTEX + 1] * scale + oy,
            )
        }
        canvas.drawPath(lassoPath, lassoPaint)
    }

    // ── Frame tool routing (sub-phase 8.1) ──────────────────────────────
    // A frame is a named rectangle in world space. The tool's gesture is a
    // simple drag — `frameDragListener` fires once on ACTION_UP with the
    // final world bounds. Tap (down+up below slop) routes to
    // `frameTapListener` so the editor can promote the touched frame to
    // "current" for export / navigator highlight. We reuse the shape
    // rubber-band state to render the in-progress rectangle so the user
    // sees what they're creating; on commit the shape state clears without
    // emitting a `Shape.Rect` item.

    private var frameTapStartX: Float = 0f
    private var frameTapStartY: Float = 0f
    private var frameTapActive: Boolean = false

    private fun handleFrameToolEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedIds.isNotEmpty()) selectionShouldClearListener?.invoke()
                frameTapStartX = event.x
                frameTapStartY = event.y
                frameTapActive = true
                val wx = viewport.screenToWorldX(event.x)
                val wy = viewport.screenToWorldY(event.y)
                shapeStartX = wx
                shapeStartY = wy
                shapeEndX = wx
                shapeEndY = wy
                shapeInProgress = true
                // Use the RECT shape's preview path; record strokeTool so the
                // rubber-band uses the rect renderer.
                strokeTool = Tool.RECT
                strokeColor = FRAME_PREVIEW_COLOR
                strokeWidthPx = FRAME_PREVIEW_WIDTH_PX
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shapeInProgress) return true
                val dx = event.x - frameTapStartX
                val dy = event.y - frameTapStartY
                if (frameTapActive && (kotlin.math.abs(dx) > tapSlopPx || kotlin.math.abs(dy) > tapSlopPx)) {
                    frameTapActive = false
                }
                shapeEndX = viewport.screenToWorldX(event.x)
                shapeEndY = viewport.screenToWorldY(event.y)
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                if (frameTapActive) {
                    // Tap (no drag) → select frame under the tap point.
                    val wx = viewport.screenToWorldX(event.x)
                    val wy = viewport.screenToWorldY(event.y)
                    frameTapListener?.invoke(wx, wy)
                } else if (shapeInProgress) {
                    val minX = kotlin.math.min(shapeStartX, shapeEndX)
                    val minY = kotlin.math.min(shapeStartY, shapeEndY)
                    val maxX = kotlin.math.max(shapeStartX, shapeEndX)
                    val maxY = kotlin.math.max(shapeStartY, shapeEndY)
                    if (maxX - minX > FRAME_MIN_SIZE_WORLD && maxY - minY > FRAME_MIN_SIZE_WORLD) {
                        frameDragListener?.invoke(floatArrayOf(minX, minY, maxX, maxY))
                    }
                }
                shapeInProgress = false
                frameTapActive = false
                strokeTool = paletteTool
                invalidate()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                shapeInProgress = false
                frameTapActive = false
                strokeTool = paletteTool
                invalidate()
                true
            }
            else -> false
        }
    }

    // ── Presentation laser (sub-phase 11.5) ──────────────────────────────
    //
    // Transient "laser pointer" ink drawn on the front buffer while
    // presenting: world-coord polylines with a red glow that fade out
    // ~900 ms after the stylus lifts and are never committed (no
    // strokeListener, no undo entry). A stylus barrel-button press advances
    // the presentation instead of drawing.

    /** Presentation mode: stylus = laser, barrel button = advance. */
    var presentationMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            laserStrokes.clear()
            activeLaser = null
            invalidate()
        }

    /** Fired on a stylus barrel-button press while presenting. */
    var presentationAdvanceListener: (() -> Unit)? = null

    private class LaserStroke(
        val points: ArrayList<Float> = ArrayList(64),
        var endedAt: Long = 0L,
    )

    private val laserStrokes = ArrayList<LaserStroke>()
    private var activeLaser: LaserStroke? = null
    private val laserPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val laserPath = Path()

    private fun handleLaserEvent(event: MotionEvent, idx: Int): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if ((event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
                    // Barrel button = next frame; no laser for this contact.
                    activeLaser = null
                    presentationAdvanceListener?.invoke()
                    return true
                }
                val stroke = LaserStroke()
                stroke.points.add(viewport.screenToWorldX(event.getX(idx)))
                stroke.points.add(viewport.screenToWorldY(event.getY(idx)))
                activeLaser = stroke
                laserStrokes.add(stroke)
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = activeLaser ?: return true
                for (h in 0 until event.historySize) {
                    stroke.points.add(viewport.screenToWorldX(event.getHistoricalX(idx, h)))
                    stroke.points.add(viewport.screenToWorldY(event.getHistoricalY(idx, h)))
                }
                stroke.points.add(viewport.screenToWorldX(event.getX(idx)))
                stroke.points.add(viewport.screenToWorldY(event.getY(idx)))
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                activeLaser?.endedAt = android.os.SystemClock.uptimeMillis()
                activeLaser = null
                postInvalidateOnAnimation()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeLaser?.let { laserStrokes.remove(it) }
                activeLaser = null
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun drawLaser(canvas: Canvas) {
        if (laserStrokes.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        laserStrokes.removeAll { it.endedAt != 0L && now - it.endedAt > LASER_FADE_MS }
        if (laserStrokes.isEmpty()) {
            invalidate()
            return
        }
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        val scale = viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
        var anyFading = false
        for (stroke in laserStrokes) {
            if (stroke.points.size < 4) continue
            val alphaFraction = if (stroke.endedAt == 0L) 1f else {
                anyFading = true
                (1f - (now - stroke.endedAt).toFloat() / LASER_FADE_MS).coerceIn(0f, 1f)
            }
            laserPath.reset()
            laserPath.moveTo(stroke.points[0], stroke.points[1])
            var i = 2
            while (i < stroke.points.size) {
                laserPath.lineTo(stroke.points[i], stroke.points[i + 1])
                i += 2
            }
            // Glow pass then core pass — both width-constant in screen px.
            laserPaint.color = LASER_GLOW_COLOR
            laserPaint.alpha = (LASER_GLOW_ALPHA * alphaFraction).toInt()
            laserPaint.strokeWidth = LASER_GLOW_WIDTH_PX / scale
            canvas.drawPath(laserPath, laserPaint)
            laserPaint.color = LASER_CORE_COLOR
            laserPaint.alpha = (255 * alphaFraction).toInt()
            laserPaint.strokeWidth = LASER_CORE_WIDTH_PX / scale
            canvas.drawPath(laserPath, laserPaint)
        }
        canvas.restore()
        if (anyFading) postInvalidateOnAnimation()
    }

    // ── Connector tool routing (sub-phase 11.2) ──────────────────────────
    //
    // Press near a bindable item (shape / sticky / image / text) → the start
    // binds to its nearest edge anchor; drag previews the segment and
    // highlights the hover candidate's anchors; release near another item
    // binds the end. Either endpoint may stay free. The committed payload
    // stores the *resolved* coordinates as fallback so the geometry stays
    // sane if a binding target is later deleted.

    private var connectorInProgress: Boolean = false
    private var connectorStartX: Float = 0f
    private var connectorStartY: Float = 0f
    private var connectorEndX: Float = 0f
    private var connectorEndY: Float = 0f
    private var connectorFromId: String? = null
    private var connectorFromAnchor: Byte = ConnectorCodec.ANCHOR_CENTER
    /** Bound start item's bounds — the 14.2 preview routes against them. */
    private var connectorFromBounds: FloatArray? = null
    /** Hover candidate's bounds — anchor dots render while dragging over it. */
    private var connectorHoverBounds: FloatArray? = null

    private fun handleConnectorToolEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedIds.isNotEmpty()) selectionShouldClearListener?.invoke()
                val wx = viewport.screenToWorldX(event.x)
                val wy = viewport.screenToWorldY(event.y)
                val candidate = findBindableItemAt(wx, wy)
                if (candidate != null) {
                    val bounds = candidate.second
                    connectorFromId = candidate.first.id
                    connectorFromAnchor = ConnectorResolver.nearestAnchor(bounds, wx, wy)
                    connectorFromBounds = bounds
                    val p = ConnectorResolver.anchorPoint(bounds, connectorFromAnchor)
                    connectorStartX = p[0]; connectorStartY = p[1]
                } else {
                    connectorFromId = null
                    connectorFromAnchor = ConnectorCodec.ANCHOR_CENTER
                    connectorFromBounds = null
                    connectorStartX = wx; connectorStartY = wy
                }
                connectorEndX = connectorStartX
                connectorEndY = connectorStartY
                connectorHoverBounds = null
                connectorInProgress = true
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!connectorInProgress) return true
                connectorEndX = viewport.screenToWorldX(event.x)
                connectorEndY = viewport.screenToWorldY(event.y)
                val hover = findBindableItemAt(connectorEndX, connectorEndY)
                connectorHoverBounds =
                    hover?.takeIf { it.first.id != connectorFromId }?.second
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                if (connectorInProgress) commitConnector()
                connectorInProgress = false
                connectorHoverBounds = null
                invalidate()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                connectorInProgress = false
                connectorHoverBounds = null
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun commitConnector() {
        var toId: String? = null
        var toAnchor: Byte = ConnectorCodec.ANCHOR_CENTER
        var endX = connectorEndX
        var endY = connectorEndY
        val target = findBindableItemAt(connectorEndX, connectorEndY)
        if (target != null && target.first.id != connectorFromId) {
            toId = target.first.id
            toAnchor = ConnectorResolver.nearestAnchor(target.second, connectorEndX, connectorEndY)
            val p = ConnectorResolver.anchorPoint(target.second, toAnchor)
            endX = p[0]; endY = p[1]
        }
        // A near-zero-length segment isn't a connector — a stray tap with
        // the tool active shouldn't litter the board.
        val length = hypot(endX - connectorStartX, endY - connectorStartY)
        if (length < MIN_CONNECTOR_LENGTH_WORLD) return
        val payload = ConnectorCodec.ConnectorPayload(
            fromItemId = connectorFromId,
            fromAnchor = connectorFromAnchor,
            toItemId = toId,
            toAnchor = toAnchor,
            x0 = connectorStartX, y0 = connectorStartY,
            x1 = endX, y1 = endY,
            arrowAtEnd = true,
            arrowAtStart = false,
            strokeStyle = ShapeCodec.STROKE_STYLE_SOLID,
            routeStyle = connectorRouteStyle,
        )
        val item = NoteItem(
            noteId = "",
            zIndex = 0,
            kind = ConnectorCodec.KIND,
            tool = Tool.CONNECTOR.id,
            colorArgb = inkColor,
            baseWidthPx = baseWidthPx,
            payload = ConnectorCodec.encode(payload),
        )
        committedItems = committedItems + item
        sceneDirty = true
        strokeListener?.invoke(item)
    }

    /**
     * Topmost bindable item whose bounds (expanded by a scale-aware grab
     * radius) contain the world point. Strokes and connectors are not
     * bindable; locked / hidden layers are inert, mirroring the lasso.
     */
    private fun findBindableItemAt(wx: Float, wy: Float): Pair<NoteItem, FloatArray>? {
        val radius = CONNECTOR_BIND_RADIUS_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
        var best: Pair<NoteItem, FloatArray>? = null
        var bestZ = Int.MIN_VALUE
        for (item in committedItems) {
            if (!isBindableKind(item.kind)) continue
            if (layerLookup.isLocked(item)) continue
            if (!layerLookup.isVisible(item)) continue
            val b = bindableBounds(item) ?: continue
            if (wx < b[0] - radius || wx > b[2] + radius) continue
            if (wy < b[1] - radius || wy > b[3] + radius) continue
            if (item.zIndex >= bestZ) {
                best = item to b
                bestZ = item.zIndex
            }
        }
        return best
    }

    private fun isBindableKind(kind: String): Boolean =
        kind == Shape.KIND || kind == StickyCodec.KIND ||
            kind == TextItemCodec.KIND || kind == NoteItem.KIND_IMAGE

    private fun bindableBounds(item: NoteItem): FloatArray? = try {
        when (item.kind) {
            Shape.KIND -> ShapeCodec.boundsOf(ShapeCodec.decode(item.payload).shape)
            StickyCodec.KIND -> StickyCodec.boundsOf(StickyCodec.decode(item.payload))
            TextItemCodec.KIND -> TextItemRenderer.boundsOf(item)
            NoteItem.KIND_IMAGE -> ImageItemCodec.boundsOf(ImageItemCodec.decode(item.payload))
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Route a committed connector against the local item mirror (14.2). */
    private fun routeConnector(payload: ConnectorCodec.ConnectorPayload): ConnectorRouter.Route =
        ConnectorRouter.route(payload) { id ->
            committedItems.firstOrNull { it.id == id }?.let { bindableBounds(it) }
        }

    private fun drawConnectorPreview(canvas: Canvas) {
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        ShapeRenderer.configurePaint(shapePaint, inkColor, baseWidthPx)
        // 14.2 — preview the actual route so elbows/curves are visible
        // while dragging, not only after commit.
        val hover = connectorHoverBounds
        val route = ConnectorRouter.route(
            payload = ConnectorCodec.ConnectorPayload(
                fromItemId = connectorFromId,
                fromAnchor = connectorFromAnchor,
                toItemId = null,
                toAnchor = hover?.let {
                    ConnectorResolver.nearestAnchor(it, connectorEndX, connectorEndY)
                } ?: ConnectorCodec.ANCHOR_CENTER,
                x0 = connectorStartX, y0 = connectorStartY,
                x1 = connectorEndX, y1 = connectorEndY,
                routeStyle = connectorRouteStyle,
            ),
            endpoints = floatArrayOf(
                connectorStartX, connectorStartY, connectorEndX, connectorEndY,
            ),
            fromBounds = connectorFromBounds,
            toBounds = hover,
        )
        val pts = route.points
        scratchPath.reset()
        scratchPath.moveTo(pts[0], pts[1])
        if (route.curved) {
            scratchPath.cubicTo(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
        } else {
            for (i in 1 until pts.size / 2) {
                scratchPath.lineTo(pts[i * 2], pts[i * 2 + 1])
            }
        }
        canvas.drawPath(scratchPath, shapePaint)
        scratchPath.reset()
        // Anchor dots: the hover candidate's four edge anchors, plus the
        // bound start anchor so the user sees what they latched onto.
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = ANCHOR_DOT_COLOR
        val r = ANCHOR_DOT_RADIUS_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
        connectorHoverBounds?.let { b ->
            for (p in ConnectorResolver.edgeAnchorPoints(b)) {
                canvas.drawCircle(p[0], p[1], r, shapePaint)
            }
        }
        if (connectorFromId != null) {
            canvas.drawCircle(connectorStartX, connectorStartY, r, shapePaint)
        }
        canvas.restore()
    }

    // ── Pen tool routing (sub-phase 12.2) ────────────────────────────────
    //
    // Multi-tap state machine: each tap places a corner anchor; pressing
    // and dragging past the touch slop pulls symmetric handles out of the
    // new anchor; tapping the first anchor (≥ 3 anchors placed) closes the
    // path and commits. Anchors live in world coordinates so pan / pinch
    // between taps (two fingers, same as the text tool) costs nothing.
    // Switching tools commits the in-progress path (see [setToolConfig]).

    /** Anchors committed so far, in placement order. */
    private val penAnchors = ArrayList<PathCodec.Anchor>()

    /** The anchor being placed by the current press, or null between taps. */
    private var penCandidate: PathCodec.Anchor? = null

    /** Whether the current press has dragged past the slop (handles pulled). */
    private var penPulling: Boolean = false

    /** True when the current press started on the first anchor (closing tap). */
    private var penClosingTap: Boolean = false
    private var penDownScreenX: Float = 0f
    private var penDownScreenY: Float = 0f

    private fun handlePathPenEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedIds.isNotEmpty()) selectionShouldClearListener?.invoke()
                gestureMode = GestureMode.NONE
                penDownScreenX = event.x
                penDownScreenY = event.y
                penPulling = false
                val wx = viewport.screenToWorldX(event.x)
                val wy = viewport.screenToWorldY(event.y)
                penClosingTap = penAnchors.size >= MIN_PEN_ANCHORS_TO_CLOSE &&
                    hypot(penAnchors[0].x - wx, penAnchors[0].y - wy) <
                    PEN_CLOSE_RADIUS_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
                // Phase 15.3 — icon pixel grid: pen anchors land on the grid
                // (handles stay free so curves remain expressive).
                penCandidate = if (penClosingTap) null else {
                    val q = pixelQuantize(wx, wy)
                    PathCodec.Anchor(q?.x ?: wx, q?.y ?: wy)
                }
                invalidate()
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger: this press becomes a pinch; the candidate
                // anchor is abandoned but the placed anchors survive.
                penCandidate = null
                penClosingTap = false
                if (event.pointerCount >= 2) {
                    pinchLastDist = pointerDistance(event, 0, 1)
                    pinchLastFocalX = (event.getX(0) + event.getX(1)) * 0.5f
                    pinchLastFocalY = (event.getY(0) + event.getY(1)) * 0.5f
                    gestureMode = GestureMode.PINCH
                }
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == GestureMode.PINCH) return handleViewportEvent(event)
                val candidate = penCandidate ?: return true
                if (!penPulling &&
                    hypot(event.x - penDownScreenX, event.y - penDownScreenY) > tapSlopPx
                ) {
                    penPulling = true
                }
                if (penPulling) {
                    // Pull symmetric handles: out follows the finger, in
                    // mirrors it, so the curve flows through the anchor.
                    val outDx = viewport.screenToWorldX(event.x) - candidate.x
                    val outDy = viewport.screenToWorldY(event.y) - candidate.y
                    penCandidate = candidate.copy(
                        inDx = -outDx, inDy = -outDy,
                        outDx = outDx, outDy = outDy,
                        type = PathCodec.TYPE_SYMMETRIC,
                    )
                    invalidate()
                }
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 == 1) gestureMode = GestureMode.NONE
                true
            }
            MotionEvent.ACTION_UP -> {
                if (gestureMode == GestureMode.PINCH) {
                    gestureMode = GestureMode.NONE
                } else if (penClosingTap && !penPulling) {
                    commitPendingPath(close = true)
                } else {
                    penCandidate?.let { penAnchors.add(it) }
                }
                penCandidate = null
                penClosingTap = false
                penPulling = false
                invalidate()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Drop the in-flight candidate; keep the placed anchors so a
                // stray palm can't eat the path.
                penCandidate = null
                penClosingTap = false
                penPulling = false
                gestureMode = GestureMode.NONE
                invalidate()
                true
            }
            else -> false
        }
    }

    /** Commit the accumulated pen anchors as one `"path"` item. */
    private fun commitPendingPath(close: Boolean) {
        val anchors = penAnchors.toList()
        penAnchors.clear()
        penCandidate = null
        penClosingTap = false
        penPulling = false
        if (anchors.size < 2) {
            invalidate()
            return
        }
        val payload = PathCodec.PathPayload(
            anchors = anchors,
            closed = close,
            fillArgb = shapeFillArgb,
            strokeStyle = shapeStrokeStyle,
            capJoin = pathCapJoin,
        )
        val item = NoteItem(
            noteId = "",
            zIndex = 0,
            kind = PathCodec.KIND,
            tool = Tool.PATH_PEN.id,
            colorArgb = inkColor,
            baseWidthPx = baseWidthPx,
            payload = PathCodec.encode(payload),
        )
        committedItems = committedItems + item
        sceneDirty = true
        strokeListener?.invoke(item)
        invalidate()
    }

    private fun drawPathPenPreview(canvas: Canvas) {
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        val candidate = penCandidate
        val anchors = if (candidate != null) penAnchors + candidate else penAnchors
        if (anchors.size >= 2) {
            PathRenderer.drawPayload(
                canvas,
                PathCodec.PathPayload(
                    anchors = anchors,
                    closed = false,
                    fillArgb = shapeFillArgb,
                    strokeStyle = shapeStrokeStyle,
                    capJoin = pathCapJoin,
                ),
                inkColor, baseWidthPx, shapePaint, scratchPath,
            )
        }
        val scale = viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
        val dotR = ANCHOR_DOT_RADIUS_PX / scale
        // Handle lines + dots for a pulled candidate, so the user sees the
        // tangent they're shaping.
        if (candidate != null && penPulling) {
            shapePaint.style = Paint.Style.STROKE
            shapePaint.color = ANCHOR_DOT_COLOR
            shapePaint.strokeWidth = 1f / scale
            shapePaint.pathEffect = null
            canvas.drawLine(
                candidate.x + candidate.inDx, candidate.y + candidate.inDy,
                candidate.x + candidate.outDx, candidate.y + candidate.outDy,
                shapePaint,
            )
            shapePaint.style = Paint.Style.FILL
            canvas.drawCircle(candidate.x + candidate.inDx, candidate.y + candidate.inDy, dotR * 0.8f, shapePaint)
            canvas.drawCircle(candidate.x + candidate.outDx, candidate.y + candidate.outDy, dotR * 0.8f, shapePaint)
        }
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = ANCHOR_DOT_COLOR
        shapePaint.pathEffect = null
        for (a in anchors) canvas.drawCircle(a.x, a.y, dotR, shapePaint)
        // Ring the first anchor once closing is armed.
        if (penAnchors.size >= MIN_PEN_ANCHORS_TO_CLOSE) {
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeWidth = 1.5f / scale
            canvas.drawCircle(
                penAnchors[0].x, penAnchors[0].y,
                PEN_CLOSE_RADIUS_PX / scale, shapePaint,
            )
        }
        canvas.restore()
    }

    // ── Shape tool routing (Phase 6.2) ───────────────────────────────────

    private fun handleShapeToolEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeTool = paletteTool
                strokeColor = inkColor
                strokeWidthPx = baseWidthPx
                if (selectedIds.isNotEmpty()) selectionShouldClearListener?.invoke()
                var wx = viewport.screenToWorldX(event.x)
                var wy = viewport.screenToWorldY(event.y)
                // Phase 15.3 — icon pixel grid: shape starts land on the grid.
                pixelQuantize(wx, wy)?.let { wx = it.x; wy = it.y }
                shapeStartX = wx
                shapeStartY = wy
                shapeEndX = wx
                shapeEndY = wy
                shapeInProgress = true
                polygonCount = 0
                if (strokeTool == Tool.POLYGON) {
                    appendPolygonVertex(wx, wy)
                }
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shapeInProgress) return true
                val rawX = viewport.screenToWorldX(event.x)
                val rawY = viewport.screenToWorldY(event.y)
                val snapped = snapShapeEndpoint(rawX, rawY)
                shapeEndX = snapped[0]
                shapeEndY = snapped[1]
                if (strokeTool == Tool.POLYGON) {
                    for (h in 0 until event.historySize) {
                        appendPolygonVertex(
                            viewport.screenToWorldX(event.getHistoricalX(h)),
                            viewport.screenToWorldY(event.getHistoricalY(h)),
                        )
                    }
                    appendPolygonVertex(shapeEndX, shapeEndY)
                }
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                if (shapeInProgress) commitShape()
                invalidate()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                shapeInProgress = false
                polygonCount = 0
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun appendPolygonVertex(x: Float, y: Float) {
        val needed = (polygonCount + 1) * 2
        if (needed > polygonPoints.size) {
            var newSize = polygonPoints.size
            while (newSize < needed) newSize *= 2
            polygonPoints = polygonPoints.copyOf(newSize)
        }
        polygonPoints[polygonCount * 2] = x
        polygonPoints[polygonCount * 2 + 1] = y
        polygonCount++
    }

    /**
     * Phase 6.3 — run a candidate world-space endpoint through the snap
     * pipeline if the user has snap enabled. Records the engaged snap target
     * for the magenta dot decay and returns the (snapped or raw) `(x, y)`.
     */
    private fun snapShapeEndpoint(rawX: Float, rawY: Float): FloatArray {
        if (snapMask == 0 && pixelGridSpacingWorld <= 0f) return floatArrayOf(rawX, rawY)
        var x = rawX
        var y = rawY
        var snapped = false
        // Angle snap (line-like tools only)
        if ((snapMask and Snap.MASK_ANGLE) != 0 &&
            (strokeTool == Tool.LINE || strokeTool == Tool.ARROW)
        ) {
            val (sx, sy, ok) = Snap.snapAngleTo(
                shapeStartX, shapeStartY, x, y, Snap.DEFAULT_ANGLE_STEP_RAD,
                Snap.DEFAULT_ANGLE_TOLERANCE_RAD,
            )
            if (ok) { x = sx; y = sy; snapped = true }
        }
        // Grid snap
        if ((snapMask and Snap.MASK_GRID) != 0) {
            val (sx, sy, ok) = Snap.snapToGrid(x, y, Snap.DEFAULT_GRID_SPACING_WORLD,
                Snap.DEFAULT_GRID_TOLERANCE_WORLD)
            if (ok) { x = sx; y = sy; snapped = true }
        }
        // Endpoint snap — to existing stroke ends + shape vertices
        if ((snapMask and Snap.MASK_ENDPOINT) != 0) {
            val radius = Snap.DEFAULT_ENDPOINT_RADIUS_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
            val (sx, sy, ok) = Snap.snapToEndpoints(x, y, endpointCandidates(), radius)
            if (ok) { x = sx; y = sy; snapped = true }
        }
        if (snapped) {
            snapAnchorX = x; snapAnchorY = y
            snapAnchorTimestamp = System.currentTimeMillis()
            postInvalidateOnAnimation()
        }
        // Phase 15.3 — icon pixel grid: unconditional quantize as the final
        // step so committed shape geometry lands exactly on the icon grid.
        // No snap marker — quantization is the steady state, not an event.
        pixelQuantize(x, y)?.let { q -> x = q.x; y = q.y }
        return floatArrayOf(x, y)
    }

    private fun endpointCandidates(): FloatArray {
        // Collect stroke endpoints + shape endpoints + line midpoints lazily.
        // Cheap enough to recompute per-move for sub-100-stroke notes; we'd
        // build a kd-tree if profiling shows this dominating.
        val out = ArrayList<Float>(committedItems.size * 4)
        for (item in committedItems) {
            when (item.kind) {
                STROKE_KIND -> {
                    val decoded = decode(item) ?: continue
                    if (decoded.count < 1) continue
                    out.add(decoded.samples[0])
                    out.add(decoded.samples[1])
                    val last = (decoded.count - 1) * StrokeCodec.FLOATS_PER_SAMPLE
                    out.add(decoded.samples[last])
                    out.add(decoded.samples[last + 1])
                }
                Shape.KIND -> {
                    val shape = ShapeCodec.decode(item.payload).shape
                    when (shape) {
                        is Shape.Line -> {
                            out.add(shape.x0); out.add(shape.y0)
                            out.add(shape.x1); out.add(shape.y1)
                        }
                        is Shape.Arrow -> {
                            out.add(shape.x0); out.add(shape.y0)
                            out.add(shape.x1); out.add(shape.y1)
                        }
                        is Shape.Rect -> {
                            out.add(shape.minX); out.add(shape.minY)
                            out.add(shape.maxX); out.add(shape.minY)
                            out.add(shape.maxX); out.add(shape.maxY)
                            out.add(shape.minX); out.add(shape.maxY)
                        }
                        is Shape.Ellipse -> {
                            out.add(shape.cx - shape.rx); out.add(shape.cy)
                            out.add(shape.cx + shape.rx); out.add(shape.cy)
                            out.add(shape.cx); out.add(shape.cy - shape.ry)
                            out.add(shape.cx); out.add(shape.cy + shape.ry)
                        }
                        is Shape.Polygon -> {
                            for (i in shape.points.indices) out.add(shape.points[i])
                        }
                    }
                }
            }
        }
        return out.toFloatArray()
    }

    private fun commitShape(): Boolean {
        val tool = strokeTool
        val color = strokeColor
        val width = strokeWidthPx
        val shape: Shape? = when (tool) {
            Tool.LINE -> {
                val dx = shapeEndX - shapeStartX
                val dy = shapeEndY - shapeStartY
                if (kotlin.math.hypot(dx, dy) < MIN_SHAPE_LENGTH_WORLD) null
                else Shape.Line(shapeStartX, shapeStartY, shapeEndX, shapeEndY)
            }
            Tool.ARROW -> {
                val dx = shapeEndX - shapeStartX
                val dy = shapeEndY - shapeStartY
                if (kotlin.math.hypot(dx, dy) < MIN_SHAPE_LENGTH_WORLD) null
                else Shape.Arrow(shapeStartX, shapeStartY, shapeEndX, shapeEndY, width * 6f)
            }
            Tool.RECT -> {
                val w = kotlin.math.abs(shapeEndX - shapeStartX)
                val h = kotlin.math.abs(shapeEndY - shapeStartY)
                if (w < MIN_SHAPE_LENGTH_WORLD && h < MIN_SHAPE_LENGTH_WORLD) null
                else Shape.Rect(shapeStartX, shapeStartY, shapeEndX, shapeEndY)
            }
            Tool.ELLIPSE -> {
                val w = kotlin.math.abs(shapeEndX - shapeStartX)
                val h = kotlin.math.abs(shapeEndY - shapeStartY)
                if (w < MIN_SHAPE_LENGTH_WORLD && h < MIN_SHAPE_LENGTH_WORLD) null
                else Shape.Ellipse(
                    cx = (shapeStartX + shapeEndX) * 0.5f,
                    cy = (shapeStartY + shapeEndY) * 0.5f,
                    rx = w * 0.5f,
                    ry = h * 0.5f,
                )
            }
            Tool.POLYGON -> {
                if (polygonCount < 3) null
                else {
                    val pts = polygonPoints.copyOf(polygonCount * 2)
                    // Close if the user ended near the start (within snap radius).
                    val dx = pts[0] - pts[(polygonCount - 1) * 2]
                    val dy = pts[1] - pts[(polygonCount - 1) * 2 + 1]
                    val closed = kotlin.math.hypot(dx, dy) <
                        Snap.DEFAULT_ENDPOINT_RADIUS_PX / viewport.scale.coerceAtLeast(MIN_DIV_SCALE)
                    Shape.Polygon(pts, closed)
                }
            }
            else -> null
        }
        shapeInProgress = false
        polygonCount = 0
        if (shape == null) return false
        val item = NoteItem(
            noteId = "",
            zIndex = 0,
            kind = Shape.KIND,
            tool = tool.id,
            colorArgb = color,
            baseWidthPx = width,
            // Phase 10.2/10.3 — palette fill + stroke style. Guarded on the
            // palette tool so the frame tool's rect rubber-band (which also
            // funnels through the shape state) can never pick up a fill.
            payload = ShapeCodec.encode(
                shape,
                fillArgb = if (paletteTool.isShape) shapeFillArgb else 0,
                strokeStyle = if (paletteTool.isShape) shapeStrokeStyle else ShapeCodec.STROKE_STYLE_SOLID,
            ),
        )
        committedItems = committedItems + item
        sceneDirty = true
        strokeListener?.invoke(item)
        return true
    }

    private fun drawShapePreview(canvas: Canvas) {
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        // Frame previews stay solid + unfilled; shape tools preview with the
        // palette's live fill and stroke style so commit holds no surprises.
        val isShapeTool = paletteTool.isShape
        ShapeRenderer.configurePaint(
            shapePaint, strokeColor, strokeWidthPx,
            if (isShapeTool) shapeStrokeStyle else ShapeCodec.STROKE_STYLE_SOLID,
        )
        val previewShape: Shape? = when (strokeTool) {
            Tool.LINE -> Shape.Line(shapeStartX, shapeStartY, shapeEndX, shapeEndY)
            Tool.ARROW -> Shape.Arrow(shapeStartX, shapeStartY, shapeEndX, shapeEndY, strokeWidthPx * 6f)
            Tool.RECT -> Shape.Rect(shapeStartX, shapeStartY, shapeEndX, shapeEndY)
            Tool.ELLIPSE -> Shape.Ellipse(
                cx = (shapeStartX + shapeEndX) * 0.5f,
                cy = (shapeStartY + shapeEndY) * 0.5f,
                rx = kotlin.math.abs(shapeEndX - shapeStartX) * 0.5f,
                ry = kotlin.math.abs(shapeEndY - shapeStartY) * 0.5f,
            )
            Tool.POLYGON -> if (polygonCount >= 2)
                Shape.Polygon(polygonPoints.copyOf(polygonCount * 2), closed = false)
            else null
            else -> null
        }
        if (previewShape != null) {
            ShapeRenderer.drawShape(
                canvas, previewShape, shapePaint,
                if (isShapeTool) shapeFillArgb else 0,
            )
        }
        canvas.restore()
    }

    private fun drawSnapMarker(canvas: Canvas) {
        if (snapAnchorTimestamp == 0L) return
        val elapsed = System.currentTimeMillis() - snapAnchorTimestamp
        if (elapsed > SNAP_FADE_MS) {
            snapAnchorTimestamp = 0L
            return
        }
        val alpha = ((1f - elapsed.toFloat() / SNAP_FADE_MS) * 255f).toInt().coerceIn(0, 255)
        val sx = viewport.worldToScreenX(snapAnchorX)
        val sy = viewport.worldToScreenY(snapAnchorY)
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = Color.MAGENTA
        shapePaint.alpha = alpha
        canvas.drawCircle(sx, sy, SNAP_MARKER_RADIUS_PX, shapePaint)
        postInvalidateOnAnimation()
    }

    /** Phase 6.3 — bitmask: bit0=angle, bit1=grid, bit2=endpoint. */
    var snapMask: Int = Snap.MASK_ANGLE or Snap.MASK_ENDPOINT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private data class DecodedStroke(
        val samples: FloatArray,
        val count: Int,
        /** `[minX, minY, maxX, maxY]` in world units. */
        val bounds: FloatArray,
    )

    companion object {
        private const val TAG = "DrawingSurface"
        const val DEFAULT_STROKE_WIDTH_PX = 4f
        const val DEFAULT_INK_COLOR = Color.BLACK
        const val STROKE_KIND = "stroke"
        const val STROKE_TOOL_PEN = "pen"
        private const val INITIAL_SAMPLE_CAPACITY = 128
        // 0.4 alpha — predicted tail "fades in" when real samples arrive.
        private const val PREDICTED_ALPHA = 102
        private const val PREDICTED_ALPHA_FRACTION = 0.4f
        private const val HOVER_RADIUS_PX = 4f
        // Translucent grey nib cursor.
        private const val HOVER_COLOR = 0x66000000
        // Outlined-circle preview for the eraser; matches the hit radius.
        private const val ERASER_CURSOR_COLOR = 0x88FF3030.toInt()
        // Outlined-circle preview for ink tools — shows the actual on-screen
        // brush size before the user touches down (Phase: pen-size zoom scaling).
        private const val BRUSH_CURSOR_COLOR = 0x66000000
        // The ring is suppressed below this on-screen radius; the small nib dot
        // already conveys position for hairline brushes.
        private const val BRUSH_CURSOR_MIN_RADIUS_PX = 3f
        private const val MIN_DIV_SCALE = 0.01f

        // ── Live beautify ghost (phase I5) ─────────────────────────────
        /** Alpha for the translucent cleaned-stroke ghost preview. */
        private const val BEAUTIFY_GHOST_ALPHA = 140
        /** Screen-space slop added to the ghost bbox for the accept-tap target. */
        private const val BEAUTIFY_TAP_SLOP_PX = 24f

        // A lasso under three vertices can't enclose anything — drop the gesture silently.
        private const val MIN_LASSO_VERTICES = 3
        private const val LASSO_STROKE_WIDTH_PX = 2f
        private const val LASSO_DASH_PX = 12f
        private const val LASSO_GAP_PX = 8f
        private const val LASSO_COLOR = 0xCC1E88E5.toInt()

        // ── Shape tools (Phase 6.2) ────────────────────────────────────
        /** Minimum world-space extent below which a shape commit is discarded. */
        private const val MIN_SHAPE_LENGTH_WORLD: Float = 2f

        // ── Snapping (Phase 6.3) ───────────────────────────────────────
        private const val SNAP_FADE_MS: Long = 280L
        private const val SNAP_MARKER_RADIUS_PX: Float = 5f

        // ── Icon pixel grid (phase 15.3) ───────────────────────────────
        private const val KEYLINE_WIDTH_PX: Float = 1f
        private const val KEYLINE_COLOR: Int = 0x4D1E88E5  // 30% canvas blue

        // ── Presentation laser (sub-phase 11.5) ───────────────────────
        private const val LASER_FADE_MS: Long = 900L
        private const val LASER_CORE_COLOR: Int = 0xFFFF1744.toInt()
        private const val LASER_GLOW_COLOR: Int = 0xFFFF5252.toInt()
        private const val LASER_GLOW_ALPHA: Int = 90
        private const val LASER_CORE_WIDTH_PX: Float = 4f
        private const val LASER_GLOW_WIDTH_PX: Float = 14f

        // ── Connector tool (sub-phase 11.2) ───────────────────────────
        /** Screen-space grab radius for binding an endpoint to an item. */
        private const val CONNECTOR_BIND_RADIUS_PX: Float = 16f
        /** Free-floating connectors below this length are discarded. */
        private const val MIN_CONNECTOR_LENGTH_WORLD: Float = 8f
        private const val ANCHOR_DOT_RADIUS_PX: Float = 5f
        private const val ANCHOR_DOT_COLOR: Int = 0xCC1E88E5.toInt()

        // ── Pen tool (sub-phase 12.2) ─────────────────────────────────
        /** Screen-space radius of the close-the-path tap target (first anchor). */
        private const val PEN_CLOSE_RADIUS_PX: Float = 16f
        /** Closing needs a real region — at least a triangle. */
        private const val MIN_PEN_ANCHORS_TO_CLOSE: Int = 3

        // ── Frame tool (sub-phase 8.1) ────────────────────────────────
        /** Minimum world-space extent for a created frame (both axes). */
        private const val FRAME_MIN_SIZE_WORLD: Float = 4f
        /** Rubber-band preview colour used while dragging out a frame. */
        private const val FRAME_PREVIEW_COLOR: Int = 0xFF1E88E5.toInt()
        private const val FRAME_PREVIEW_WIDTH_PX: Float = 1.5f
    }
}

@Composable
fun DrawingSurfaceView(
    items: List<NoteItem>,
    backgroundStyle: String,
    paletteState: ToolPaletteState,
    selectedIds: Set<String>,
    selectionMatrix: FloatArray,
    editingTextId: String?,
    onStrokeCommitted: (NoteItem) -> Unit,
    onItemsErased: (List<String>) -> Unit,
    onLassoCompleted: (FloatArray) -> Unit,
    onSelectionShouldClear: () -> Unit,
    onTextTap: (worldX: Float, worldY: Float) -> Unit,
    modifier: Modifier = Modifier,
    // Sub-phase 11.1 — sticky notes: tap dispatch + in-edit body suppression.
    editingStickyId: String? = null,
    onStickyTap: (worldX: Float, worldY: Float) -> Unit = { _, _ -> },
    // Sub-phase 11.3 — hold-to-snap shape recognition.
    onStrokeHoldRecognized: (NoteItem) -> Unit = { },
    // Phase I5 — live beautify: the user tapped the ghost to accept the cleaned
    // candidate; the receiver swaps raw → beautified as one undoable edit.
    onStrokeBeautifyAccepted: (rawItem: NoteItem, beautified: NoteItem) -> Unit = { _, _ -> },
    // Sub-phase 11.5 — presentation mode: stylus draws transient laser ink,
    // barrel button advances the frame stepper.
    presentationMode: Boolean = false,
    onPresentationAdvance: () -> Unit = { },
    sketchMode: Boolean = false,
    // "Draw with finger" user setting — single finger inks, two fingers
    // pan/zoom. Ignored while a stylus pointer is active.
    fingerInkEnabled: Boolean = false,
    snapMask: Int = Snap.MASK_ANGLE or Snap.MASK_ENDPOINT,
    layers: List<NoteLayer> = emptyList(),
    activeTextureId: String? = null,
    onViewportReady: (ViewportController) -> Unit = {},
    onFrameDrawn: (FloatArray) -> Unit = {},
    onFrameTap: (worldX: Float, worldY: Float) -> Unit = { _, _ -> },
    // Phase 9.4 — when non-zero, the surface tags each stroke sample with
    // `t = SystemClock.elapsedRealtime() - recordingStartedAt` and emits
    // v2 stroke payloads. Zero (default) keeps the v1 encoding path.
    recordingStartedAt: Long = 0L,
    // Icon artboard world-rect; when non-null, ink is clipped to it (bounded
    // canvas). Null for notes (infinite canvas).
    artboardClipBounds: FloatArray? = null,
    // Phase 15.3 — icon pixel grid: world units per icon pixel (0 = off).
    pixelGridSpacingWorld: Float = 0f,
    // Phase I1 — ink-first authoring switch (default off, fallback-capable).
    // When true, an [InProgressStrokesView] overlay is attached and live ink
    // strokes are authored by AndroidX Ink; when false (the default, and the
    // current parity-gated behaviour) the custom quad-Bézier path is used and
    // no ink view exists. See `docs/ANDROIDX_INK_MIGRATION_PLAN.md` (I1/I2).
    inkAuthoringEnabled: Boolean = false,
) {
    val currentOnCommit by rememberUpdatedState(onStrokeCommitted)
    val currentOnErase by rememberUpdatedState(onItemsErased)
    val currentOnLasso by rememberUpdatedState(onLassoCompleted)
    val currentOnSelectionClear by rememberUpdatedState(onSelectionShouldClear)
    val currentOnTextTap by rememberUpdatedState(onTextTap)
    val currentOnStickyTap by rememberUpdatedState(onStickyTap)
    val currentOnHoldRecognized by rememberUpdatedState(onStrokeHoldRecognized)
    val currentOnBeautifyAccepted by rememberUpdatedState(onStrokeBeautifyAccepted)
    val currentOnPresentationAdvance by rememberUpdatedState(onPresentationAdvance)
    val currentOnFrameDrawn by rememberUpdatedState(onFrameDrawn)
    val currentOnFrameTap by rememberUpdatedState(onFrameTap)
    val currentOnViewportReady by rememberUpdatedState(onViewportReady)
    // Reading items.size during composition makes this composable observe the
    // SnapshotStateList: erases + future undo/redo refresh the surface
    // without us having to thread a dedicated "version" signal through.
    val itemsSignature = items.size

    AndroidView(
        modifier = modifier,
        // The surface is wrapped in a FrameLayout so the ink-authoring overlay
        // (an InProgressStrokesView) can be added as a sibling on top when the
        // I1 switch is on. With the switch off (default) the container holds
        // only the DrawingSurface and behaves exactly as before.
        factory = { ctx ->
            val surface = DrawingSurface(ctx).apply {
                this.sketchMode = sketchMode
                strokeListener = { item -> currentOnCommit(item) }
                eraseListener = { ids -> currentOnErase(ids) }
                lassoListener = { polygon -> currentOnLasso(polygon) }
                selectionShouldClearListener = { currentOnSelectionClear() }
                textTapListener = { wx, wy -> currentOnTextTap(wx, wy) }
                stickyTapListener = { wx, wy -> currentOnStickyTap(wx, wy) }
                strokeHoldRecognizeListener = { item -> currentOnHoldRecognized(item) }
                strokeBeautifyListener = { raw, beautified -> currentOnBeautifyAccepted(raw, beautified) }
                presentationAdvanceListener = { currentOnPresentationAdvance() }
                frameDragListener = { bounds -> currentOnFrameDrawn(bounds) }
                frameTapListener = { wx, wy -> currentOnFrameTap(wx, wy) }
                setFilesDir(ctx.filesDir)
                currentOnViewportReady(viewport)
            }
            FrameLayout(ctx).apply {
                addView(
                    surface,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { container ->
            val view = container.getChildAt(0) as DrawingSurface
            // Lazily attach / detach the ink overlay so the front-buffer view
            // only exists while the switch is on (zero overhead when off, and
            // off is the default everywhere — sketch mode included).
            if (inkAuthoringEnabled && view.inkAuthoringView == null) {
                val inkView = InProgressStrokesView(container.context)
                container.addView(
                    inkView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                inkView.eagerInit()
                view.attachInkAuthoring(inkView)
            } else if (!inkAuthoringEnabled && view.inkAuthoringView != null) {
                val inkView = view.inkAuthoringView
                view.detachInkAuthoring()
                container.removeView(inkView)
            }
            view.inkAuthoringEnabled = inkAuthoringEnabled
            view.sketchMode = sketchMode
            view.fingerInkEnabled = fingerInkEnabled
            view.recordingStartedAt = recordingStartedAt
            view.strokeListener = { item -> currentOnCommit(item) }
            view.eraseListener = { ids -> currentOnErase(ids) }
            view.lassoListener = { polygon -> currentOnLasso(polygon) }
            view.selectionShouldClearListener = { currentOnSelectionClear() }
            view.textTapListener = { wx, wy -> currentOnTextTap(wx, wy) }
            view.stickyTapListener = { wx, wy -> currentOnStickyTap(wx, wy) }
            view.strokeHoldRecognizeListener = { item -> currentOnHoldRecognized(item) }
            view.strokeBeautifyListener = { raw, beautified -> currentOnBeautifyAccepted(raw, beautified) }
            view.presentationAdvanceListener = { currentOnPresentationAdvance() }
            view.presentationMode = presentationMode
            view.frameDragListener = { bounds -> currentOnFrameDrawn(bounds) }
            view.frameTapListener = { wx, wy -> currentOnFrameTap(wx, wy) }
            view.backgroundStyle = backgroundStyle
            view.artboardClipBounds = artboardClipBounds
            view.pixelGridSpacingWorld = pixelGridSpacingWorld
            view.setToolConfig(
                tool = paletteState.selected,
                colorArgb = paletteState.activeInkColor(),
                widthPx = paletteState.activeInkWidth(),
                areaEraserRadiusPx = paletteState.areaEraserRadiusPx,
                textureId = activeTextureId,
                shapeFillArgb = paletteState.activeShapeFillArgb(),
                shapeStrokeStyle = paletteState.shapeStrokeStyle.toByte(),
                pathCapJoin = paletteState.activePathCapJoin(),
                beautifyInk = paletteState.inkBeautify,
                connectorRouteStyle = paletteState.connectorRouteStyle.toByte(),
                screenAnchoredPenSize = paletteState.screenAnchoredPenSize,
                fixedWidthInk = paletteState.fixedWidthInk,
            )
            view.setSelection(selectedIds, selectionMatrix)
            view.setEditingTextId(editingTextId)
            view.setEditingStickyId(editingStickyId)
            view.setLayers(layers)
            view.snapMask = snapMask
            // Re-rasterize whenever the authoritative item list changes
            // (initial load, commit, erase). [itemsSignature] is read here so
            // Compose treats the lambda as dependent on it.
            @Suppress("UNUSED_EXPRESSION") itemsSignature
            view.replayItems(items)
        },
    )
}
