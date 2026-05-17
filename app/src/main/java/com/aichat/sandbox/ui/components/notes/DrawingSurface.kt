package com.aichat.sandbox.ui.components.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.aichat.sandbox.data.model.NoteItem

/**
 * Minimum-viable ink surface (sub-phase 1.3).
 *
 *   - Stylus-only input; finger touches ignored.
 *   - Single tool: pen, black, constant width.
 *   - Committed strokes live on a Bitmap scene layer; the in-progress stroke is drawn on top.
 *   - No front buffer / motion prediction / pan / zoom yet — those come in 1.4 / 1.5.
 */
class DrawingSurface(context: Context) : View(context) {

    private var sceneBitmap: Bitmap? = null
    private var sceneCanvas: Canvas? = null

    private val livePath = Path()
    private val liveSamples = ArrayList<Float>(256)

    private val inkPaint = Paint().apply {
        color = DEFAULT_INK_COLOR
        strokeWidth = DEFAULT_STROKE_WIDTH_PX
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val replayPaint = Paint(inkPaint)

    /** Invoked once per committed stroke. Caller is responsible for noteId / zIndex. */
    var strokeListener: ((NoteItem) -> Unit)? = null

    /**
     * Items queued for replay before the scene canvas exists (e.g. before onSizeChanged).
     * Flushed once the bitmap is allocated.
     */
    private var pendingReplay: List<NoteItem>? = null

    /** Render the given items onto the scene bitmap, replacing any existing scene contents. */
    fun replayItems(items: List<NoteItem>) {
        val canvas = sceneCanvas
        if (canvas == null) {
            pendingReplay = items
            return
        }
        drawItemsTo(canvas, items)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val previous = sceneBitmap
        val next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val nextCanvas = Canvas(next)
        if (previous != null) {
            // Preserve already-drawn strokes across rotation / resize.
            nextCanvas.drawBitmap(previous, 0f, 0f, null)
            previous.recycle()
        }
        sceneBitmap = next
        sceneCanvas = nextCanvas
        pendingReplay?.let {
            drawItemsTo(nextCanvas, it)
            pendingReplay = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        sceneBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (!livePath.isEmpty) {
            canvas.drawPath(livePath, inkPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Palm rejection: only the primary pointer is considered, and only if it's the stylus.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                livePath.reset()
                liveSamples.clear()
                appendSample(event.x, event.y)
                livePath.moveTo(event.x, event.y)
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // S-Pen samples faster than the frame rate; iterating history avoids jagged lines.
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(h)
                    val hy = event.getHistoricalY(h)
                    appendSample(hx, hy)
                    livePath.lineTo(hx, hy)
                }
                appendSample(event.x, event.y)
                livePath.lineTo(event.x, event.y)
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                commitLiveStroke()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                livePath.reset()
                liveSamples.clear()
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun appendSample(x: Float, y: Float) {
        liveSamples.add(x)
        liveSamples.add(y)
        // Pressure / tilt placeholders — real values land in sub-phase 1.4.
        liveSamples.add(1.0f)
        liveSamples.add(0.0f)
    }

    private fun commitLiveStroke() {
        if (liveSamples.size < StrokeCodec.FLOATS_PER_SAMPLE) {
            livePath.reset()
            liveSamples.clear()
            invalidate()
            return
        }
        val floats = FloatArray(liveSamples.size).also { dst ->
            for (i in liveSamples.indices) dst[i] = liveSamples[i]
        }
        val item = NoteItem(
            noteId = "",
            zIndex = 0,
            kind = STROKE_KIND,
            tool = STROKE_TOOL_PEN,
            colorArgb = DEFAULT_INK_COLOR,
            baseWidthPx = DEFAULT_STROKE_WIDTH_PX,
            payload = StrokeCodec.encode(floats),
        )
        sceneCanvas?.drawPath(livePath, inkPaint)
        strokeListener?.invoke(item)
        livePath.reset()
        liveSamples.clear()
        invalidate()
    }

    private fun drawItemsTo(canvas: Canvas, items: List<NoteItem>) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (item in items) {
            if (item.kind != STROKE_KIND) continue
            val samples = StrokeCodec.decode(item.payload)
            if (samples.size < StrokeCodec.FLOATS_PER_SAMPLE) continue
            replayPaint.color = item.colorArgb
            replayPaint.strokeWidth = item.baseWidthPx
            val path = Path().apply {
                moveTo(samples[0], samples[1])
                var i = StrokeCodec.FLOATS_PER_SAMPLE
                while (i < samples.size) {
                    lineTo(samples[i], samples[i + 1])
                    i += StrokeCodec.FLOATS_PER_SAMPLE
                }
            }
            canvas.drawPath(path, replayPaint)
        }
    }

    companion object {
        const val DEFAULT_STROKE_WIDTH_PX = 4f
        const val DEFAULT_INK_COLOR = Color.BLACK
        const val STROKE_KIND = "stroke"
        const val STROKE_TOOL_PEN = "pen"
    }
}

@Composable
fun DrawingSurfaceView(
    items: List<NoteItem>,
    onStrokeCommitted: (NoteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnCommit by rememberUpdatedState(onStrokeCommitted)
    var replayed by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DrawingSurface(ctx).apply {
                strokeListener = { item -> currentOnCommit(item) }
            }
        },
        update = { view ->
            view.strokeListener = { item -> currentOnCommit(item) }
            if (!replayed && items.isNotEmpty()) {
                view.replayItems(items.toList())
                replayed = true
            }
        },
    )
}
