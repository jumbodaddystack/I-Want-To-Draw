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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.input.motionprediction.MotionEventPredictor
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
     * Sketch-attachment mode (sub-phase 3.4). When enabled:
     *  - every pointer (finger or stylus) is routed through the ink path so
     *    the chat composer's sheet works without an S-Pen,
     *  - viewport pan / zoom is suppressed — the sheet renders at a fixed
     *    size, no infinite canvas, no pinch.
     * The Phase 1 note editor never sets this; defaults to off.
     */
    var sketchMode: Boolean = false

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

    private var hoverX: Float = 0f
    private var hoverY: Float = 0f
    private var hoverVisible: Boolean = false

    // Palette-driven config; set by [DrawingSurfaceView] every recomposition.
    private var paletteTool: Tool = Tool.PEN
    private var inkColor: Int = DEFAULT_INK_COLOR
    private var baseWidthPx: Float = DEFAULT_STROKE_WIDTH_PX
    private var areaEraserRadiusPx: Float = 24f

    /** Tool actually used for the in-flight stroke (palette tool or side-button override). */
    private var strokeTool: Tool = Tool.PEN
    private var strokeColor: Int = DEFAULT_INK_COLOR
    private var strokeWidthPx: Float = DEFAULT_STROKE_WIDTH_PX

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

    // Viewport gesture state — only used for finger input (stylus has its own branch).
    private enum class GestureMode { NONE, PAN, PINCH }
    private var gestureMode: GestureMode = GestureMode.NONE
    private var panLastX: Float = 0f
    private var panLastY: Float = 0f
    private var pinchLastDist: Float = 0f

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
        TextItemRenderer.evictUnused(keep)
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
    ) {
        paletteTool = tool
        inkColor = colorArgb
        baseWidthPx = widthPx
        strokeTextureId = textureId
        this.areaEraserRadiusPx = areaEraserRadiusPx
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
        sceneBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        drawSelectedItems(canvas)

        if (strokeTool.isLasso && lassoCount > 0) {
            drawLassoLoop(canvas)
        }

        if (shapeInProgress) {
            drawShapePreview(canvas)
        }

        drawSnapMarker(canvas)

        if (strokeTool.isInk && (liveSampleCount > 0 || predictedSampleCount > 0)) {
            canvas.save()
            canvas.translate(viewport.offsetX, viewport.offsetY)
            canvas.scale(viewport.scale, viewport.scale)
            if (liveSampleCount > 0) {
                StrokeRenderer.configureToolPaint(
                    livePaint, strokeTool.id, strokeColor, strokeTextureForStroke,
                )
                StrokeRenderer.drawStrokePath(
                    canvas, livePaint, liveSamples, liveSampleCount,
                    strokeWidthPx, strokeTool.id, scratchPath,
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
                    strokeWidthPx, strokeTool.id, scratchPath,
                )
            }
            canvas.restore()
        }

        if (hoverVisible) {
            if (paletteTool.isEraser) {
                // The preview circle uses the palette tool (not strokeTool) so
                // the user sees what they're about to do before touching down.
                val r = if (paletteTool == Tool.ERASER_AREA) areaEraserRadiusPx
                else ToolPaletteState.STROKE_ERASER_RADIUS_PX
                canvas.drawCircle(hoverX, hoverY, r, eraserCursorPaint)
            } else {
                canvas.drawCircle(hoverX, hoverY, HOVER_RADIUS_PX, hoverPaint)
            }
        }
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
        if (sketchMode) {
            // Fixed-size sheet canvas: every pointer is ink, no viewport
            // gestures. Pressure / tilt fall back to finger defaults when
            // not reported.
            return handleStylusEvent(event, 0)
        }
        if (paletteTool.isText) {
            // Text tool: tap (stylus or finger) → create / edit text item.
            // Two-finger pinch still works so the user can zoom while in
            // text mode; pan is disabled because every move-with-one-finger
            // would otherwise either pan or commit a stray tap.
            return handleTextToolEvent(event)
        }
        if (paletteTool.isShape) {
            // Phase 6.2 — shape tools accept any pointer (stylus or finger)
            // and emit a rubber-band preview between ACTION_DOWN / UP.
            return handleShapeToolEvent(event)
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
        return handleViewportEvent(event)
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
                    textTapListener?.invoke(wx, wy)
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
                if (strokeTool.isLasso) {
                    appendLassoVertex(event, idx)
                } else {
                    appendStylusSample(event, idx)
                    motionPredictor?.record(event)
                    if (strokeTool.isEraser) eraseAtLastSample()
                }
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
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
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                clearPredicted()
                when {
                    strokeTool.isLasso -> commitLassoLoop()
                    strokeTool.isEraser -> commitEraseStroke()
                    else -> commitLiveStroke()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                liveSampleCount = 0
                lassoCount = 0
                clearPredicted()
                if (strokeTool.isEraser && pendingErase.isNotEmpty()) {
                    // Undo the visual erase — items return to the scene.
                    pendingErase.clear()
                    sceneDirty = true
                }
                invalidate()
                true
            }
            else -> false
        }
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
                            if (pinchLastDist > 1f && newDist > 1f) {
                                val factor = newDist / pinchLastDist
                                val focalX = (event.getX(0) + event.getX(1)) * 0.5f
                                val focalY = (event.getY(0) + event.getY(1)) * 0.5f
                                viewport.applyZoom(focalX, focalY, factor)
                            }
                            pinchLastDist = newDist
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
        liveSampleCount++
    }

    private fun ensureLiveCapacity(samples: Int) {
        val needed = samples * StrokeCodec.FLOATS_PER_SAMPLE
        if (needed <= liveSamples.size) return
        var newSize = liveSamples.size
        while (newSize < needed) newSize *= 2
        liveSamples = liveSamples.copyOf(newSize)
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

    private fun commitLiveStroke() {
        if (liveSampleCount < 1) {
            invalidate()
            return
        }
        val packed = FloatArray(liveSampleCount * StrokeCodec.FLOATS_PER_SAMPLE)
        System.arraycopy(liveSamples, 0, packed, 0, packed.size)
        val item = NoteItem(
            noteId = "",
            // VM rewrites this with a tool-aware zIndex (highlighter sits in a
            // negative range so it always renders under ink); we still append
            // here for instant feedback before the next update() lands.
            zIndex = 0,
            kind = STROKE_KIND,
            tool = strokeTool.id,
            colorArgb = strokeColor,
            baseWidthPx = strokeWidthPx,
            payload = StrokeCodec.encode(packed),
        )
        committedItems = committedItems + item
        sceneDirty = true
        strokeListener?.invoke(item)
        liveSampleCount = 0
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
        matched.forEach { decodedCache.remove(it) }
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
            val hit = when (item.kind) {
                STROKE_KIND -> {
                    val decoded = decode(item) ?: continue
                    if (!HitTest.bboxContainsPoint(decoded.bounds, px, py, radius)) false
                    else HitTest.pointWithinStroke(decoded.samples, decoded.count, px, py, radius)
                }
                Shape.KIND -> {
                    val shape = ShapeCodec.decode(item.payload).shape
                    val sb = ShapeCodec.boundsOf(shape) ?: continue
                    if (!HitTest.bboxContainsPoint(sb, px, py, radius)) false
                    else HitTest.shapeContainsPoint(shape, px, py, radius)
                }
                else -> false
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
                )
            }
            TextItemCodec.KIND -> TextItemRenderer.draw(canvas, item, textScratchMatrix)
            Shape.KIND -> ShapeRenderer.draw(canvas, item, replayPaint)
            NoteItem.KIND_IMAGE -> filesDir?.let { ImageRenderer.draw(canvas, item, it) }
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

    // ── Shape tool routing (Phase 6.2) ───────────────────────────────────

    private fun handleShapeToolEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeTool = paletteTool
                strokeColor = inkColor
                strokeWidthPx = baseWidthPx
                if (selectedIds.isNotEmpty()) selectionShouldClearListener?.invoke()
                val wx = viewport.screenToWorldX(event.x)
                val wy = viewport.screenToWorldY(event.y)
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
        if (snapMask == 0) return floatArrayOf(rawX, rawY)
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
            payload = ShapeCodec.encode(shape),
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
        ShapeRenderer.configurePaint(shapePaint, strokeColor, strokeWidthPx)
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
        if (previewShape != null) ShapeRenderer.drawShape(canvas, previewShape, shapePaint, 0)
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
        private const val MIN_DIV_SCALE = 0.01f
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
    sketchMode: Boolean = false,
    snapMask: Int = Snap.MASK_ANGLE or Snap.MASK_ENDPOINT,
    layers: List<NoteLayer> = emptyList(),
    activeTextureId: String? = null,
    onViewportReady: (ViewportController) -> Unit = {},
    onFrameDrawn: (FloatArray) -> Unit = {},
    onFrameTap: (worldX: Float, worldY: Float) -> Unit = { _, _ -> },
) {
    val currentOnCommit by rememberUpdatedState(onStrokeCommitted)
    val currentOnErase by rememberUpdatedState(onItemsErased)
    val currentOnLasso by rememberUpdatedState(onLassoCompleted)
    val currentOnSelectionClear by rememberUpdatedState(onSelectionShouldClear)
    val currentOnTextTap by rememberUpdatedState(onTextTap)
    val currentOnFrameDrawn by rememberUpdatedState(onFrameDrawn)
    val currentOnFrameTap by rememberUpdatedState(onFrameTap)
    val currentOnViewportReady by rememberUpdatedState(onViewportReady)
    // Reading items.size during composition makes this composable observe the
    // SnapshotStateList: erases + future undo/redo refresh the surface
    // without us having to thread a dedicated "version" signal through.
    val itemsSignature = items.size

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DrawingSurface(ctx).apply {
                this.sketchMode = sketchMode
                strokeListener = { item -> currentOnCommit(item) }
                eraseListener = { ids -> currentOnErase(ids) }
                lassoListener = { polygon -> currentOnLasso(polygon) }
                selectionShouldClearListener = { currentOnSelectionClear() }
                textTapListener = { wx, wy -> currentOnTextTap(wx, wy) }
                frameDragListener = { bounds -> currentOnFrameDrawn(bounds) }
                frameTapListener = { wx, wy -> currentOnFrameTap(wx, wy) }
                setFilesDir(ctx.filesDir)
                currentOnViewportReady(viewport)
            }
        },
        update = { view ->
            view.sketchMode = sketchMode
            view.strokeListener = { item -> currentOnCommit(item) }
            view.eraseListener = { ids -> currentOnErase(ids) }
            view.lassoListener = { polygon -> currentOnLasso(polygon) }
            view.selectionShouldClearListener = { currentOnSelectionClear() }
            view.textTapListener = { wx, wy -> currentOnTextTap(wx, wy) }
            view.frameDragListener = { bounds -> currentOnFrameDrawn(bounds) }
            view.frameTapListener = { wx, wy -> currentOnFrameTap(wx, wy) }
            view.backgroundStyle = backgroundStyle
            view.setToolConfig(
                tool = paletteState.selected,
                colorArgb = paletteState.activeInkColor(),
                widthPx = paletteState.activeInkWidth(),
                areaEraserRadiusPx = paletteState.areaEraserRadiusPx,
                textureId = activeTextureId,
            )
            view.setSelection(selectedIds, selectionMatrix)
            view.setEditingTextId(editingTextId)
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
