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

    private var sceneBitmap: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private var sceneDirty = true

    /** Committed strokes, kept on the view so we can re-rasterize on viewport changes. */
    private var committedItems: List<NoteItem> = emptyList()

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

    /** Invoked once per committed stroke. Caller assigns noteId / zIndex. */
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
     * Replace the committed-item set and re-rasterize the scene. Items are
     * sorted by `zIndex` so highlighter strokes (negative range) render
     * under pen / pencil strokes (positive range) regardless of the order
     * the caller hands them over.
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
    ) {
        paletteTool = tool
        inkColor = colorArgb
        baseWidthPx = widthPx
        this.areaEraserRadiusPx = areaEraserRadiusPx
        invalidate()
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

        if (strokeTool.isInk && (liveSampleCount > 0 || predictedSampleCount > 0)) {
            canvas.save()
            canvas.translate(viewport.offsetX, viewport.offsetY)
            canvas.scale(viewport.scale, viewport.scale)
            if (liveSampleCount > 0) {
                StrokeRenderer.configureToolPaint(livePaint, strokeTool.id, strokeColor)
                StrokeRenderer.drawStrokePath(
                    canvas, livePaint, liveSamples, liveSampleCount,
                    strokeWidthPx, strokeTool.id, scratchPath,
                )
            }
            val predicted = predictedSamples
            if (predicted != null && predictedSampleCount > 0) {
                StrokeRenderer.configureToolPaint(predictedPaint, strokeTool.id, strokeColor)
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
        if (paletteTool.isText) {
            // Text tool: tap (stylus or finger) → create / edit text item.
            // Two-finger pinch still works so the user can zoom while in
            // text mode; pan is disabled because every move-with-one-finger
            // would otherwise either pan or commit a stray tap.
            return handleTextToolEvent(event)
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
            if (item.kind != STROKE_KIND) continue
            if (item.id in pendingErase) continue
            val decoded = decode(item) ?: continue
            if (!HitTest.bboxContainsPoint(decoded.bounds, px, py, radius)) continue
            if (HitTest.pointWithinStroke(decoded.samples, decoded.count, px, py, radius)) {
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
        for (item in committedItems) {
            if (item.id in pendingErase) continue
            // Selected items are drawn live in onDraw so a Compose-side
            // transform gesture renders without re-rasterizing every frame.
            if (item.id in selectedIds) continue
            // Currently in-edit text item is hidden — the Compose editor
            // owns the visual real estate.
            if (item.id == editingTextId) continue
            when (item.kind) {
                STROKE_KIND -> {
                    val decoded = decode(item) ?: continue
                    StrokeRenderer.configureToolPaint(replayPaint, item.tool, item.colorArgb)
                    StrokeRenderer.drawStrokePath(
                        canvas, replayPaint, decoded.samples, decoded.count,
                        item.baseWidthPx, item.tool, scratchPath,
                    )
                }
                TextItemCodec.KIND -> {
                    TextItemRenderer.draw(canvas, item, textScratchMatrix)
                }
            }
        }
        canvas.restore()
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
            when (item.kind) {
                STROKE_KIND -> {
                    val decoded = decode(item) ?: continue
                    StrokeRenderer.configureToolPaint(replayPaint, item.tool, item.colorArgb)
                    StrokeRenderer.drawStrokePath(
                        canvas, replayPaint, decoded.samples, decoded.count,
                        item.baseWidthPx, item.tool, scratchPath,
                    )
                }
                TextItemCodec.KIND -> {
                    TextItemRenderer.draw(canvas, item, textScratchMatrix)
                }
            }
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
    onViewportReady: (ViewportController) -> Unit = {},
) {
    val currentOnCommit by rememberUpdatedState(onStrokeCommitted)
    val currentOnErase by rememberUpdatedState(onItemsErased)
    val currentOnLasso by rememberUpdatedState(onLassoCompleted)
    val currentOnSelectionClear by rememberUpdatedState(onSelectionShouldClear)
    val currentOnTextTap by rememberUpdatedState(onTextTap)
    val currentOnViewportReady by rememberUpdatedState(onViewportReady)
    // Reading items.size during composition makes this composable observe the
    // SnapshotStateList: erases + future undo/redo refresh the surface
    // without us having to thread a dedicated "version" signal through.
    val itemsSignature = items.size

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DrawingSurface(ctx).apply {
                strokeListener = { item -> currentOnCommit(item) }
                eraseListener = { ids -> currentOnErase(ids) }
                lassoListener = { polygon -> currentOnLasso(polygon) }
                selectionShouldClearListener = { currentOnSelectionClear() }
                textTapListener = { wx, wy -> currentOnTextTap(wx, wy) }
                currentOnViewportReady(viewport)
            }
        },
        update = { view ->
            view.strokeListener = { item -> currentOnCommit(item) }
            view.eraseListener = { ids -> currentOnErase(ids) }
            view.lassoListener = { polygon -> currentOnLasso(polygon) }
            view.selectionShouldClearListener = { currentOnSelectionClear() }
            view.textTapListener = { wx, wy -> currentOnTextTap(wx, wy) }
            view.backgroundStyle = backgroundStyle
            view.setToolConfig(
                tool = paletteState.selected,
                colorArgb = paletteState.activeInkColor(),
                widthPx = paletteState.activeInkWidth(),
                areaEraserRadiusPx = paletteState.areaEraserRadiusPx,
            )
            view.setSelection(selectedIds, selectionMatrix)
            view.setEditingTextId(editingTextId)
            // Re-rasterize whenever the authoritative item list changes
            // (initial load, commit, erase). [itemsSignature] is read here so
            // Compose treats the lambda as dependent on it.
            @Suppress("UNUSED_EXPRESSION") itemsSignature
            view.replayItems(items)
        },
    )
}
