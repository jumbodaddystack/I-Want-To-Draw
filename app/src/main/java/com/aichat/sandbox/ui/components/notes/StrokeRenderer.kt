package com.aichat.sandbox.ui.components.notes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.random.Random

/**
 * Shared stroke draw routine (sub-phase 1.4, extended in 1.6 and 5.1).
 *
 * Per-segment width and alpha now come from [ToolDynamics] so the curves
 * are unit-testable on the JVM and the renderer stays free of pure-math
 * arithmetic. Anything Android-specific (paint configuration, shader caching,
 * Canvas operations) lives here; the dynamics math lives in [ToolDynamics].
 *
 * [drawStrokePath] is shared by live, predicted, and replay rendering so a
 * stroke looks identical on commit as it did while being drawn.
 */
object StrokeRenderer {

    const val TOOL_PEN = "pen"
    const val TOOL_HIGHLIGHTER = "highlighter"
    const val TOOL_PENCIL = "pencil"

    private const val PENCIL_GRAIN_ALPHA = 200    // grain shader handles the rest

    /** Lazily-built 64x64 tileable noise bitmap shared by every pencil paint. */
    @Volatile private var pencilShader: BitmapShader? = null

    /**
     * Configure [paint] for a freshly issued stroke of [tool] with [colorArgb].
     * Sets cap, color, base alpha, blend, and (for pencil) the shared grain
     * shader. Per-sample width/alpha are written later by [drawStrokePath]
     * via [ToolDynamics], so callers don't need to set them.
     */
    fun configureToolPaint(paint: Paint, tool: String?, colorArgb: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.isAntiAlias = true
        paint.color = colorArgb
        when (tool) {
            TOOL_HIGHLIGHTER -> {
                paint.strokeCap = Paint.Cap.SQUARE
                paint.shader = null
                // Base alpha set per-segment by [ToolDynamics.highlighter].
                paint.alpha = Color.alpha(colorArgb)
            }
            TOOL_PENCIL -> {
                paint.shader = obtainPencilShader()
                // Per-segment alpha multiplied in via [ToolDynamics.pencil];
                // the grain shader provides the rest of the visual noise.
                paint.alpha = PENCIL_GRAIN_ALPHA
            }
            else -> {
                paint.shader = null
                paint.alpha = Color.alpha(colorArgb)
            }
        }
    }

    private fun obtainPencilShader(): BitmapShader {
        pencilShader?.let { return it }
        synchronized(this) {
            pencilShader?.let { return it }
            val bmp = buildPencilNoiseBitmap()
            val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            pencilShader = shader
            return shader
        }
    }

    /**
     * Deterministic 64×64 noise tile. A fixed seed keeps every pencil stroke
     * grain-stable across launches without committing a binary asset to res/.
     */
    private fun buildPencilNoiseBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = ByteArray(size * size)
        val rng = Random(0xCAFEBABEL)
        for (i in pixels.indices) {
            // Bias toward "ink leaves grain" — most cells partially mask the stroke.
            val v = rng.nextInt(120, 256)
            pixels[i] = v.toByte()
        }
        val buffer = java.nio.ByteBuffer.wrap(pixels)
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    /**
     * Draws a stroke with per-segment variable width and quadratic-Bezier
     * smoothing between sample midpoints.
     *
     * `samples` is the packed `[x,y,p,t, x,y,p,t, …]` layout used everywhere
     * else in the notes module; only the first `sampleCount` samples are
     * read. The caller controls paint color / cap / base alpha; this function
     * sets `strokeWidth` and `alpha` per segment via [ToolDynamics].
     */
    fun drawStrokePath(
        canvas: Canvas,
        paint: Paint,
        samples: FloatArray,
        sampleCount: Int,
        baseWidthPx: Float,
        tool: String?,
        scratchPath: Path = Path(),
    ) {
        if (sampleCount < 1) return
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        // Save and restore alpha so per-segment modulation doesn't leak
        // between strokes that share a Paint instance.
        val baseAlpha = paint.alpha
        try {
            if (sampleCount == 1) {
                applyStyle(paint, baseAlpha, samples[2], samples[3], baseWidthPx, tool)
                canvas.drawPoint(samples[0], samples[1], paint)
                return
            }
            if (sampleCount == 2) {
                applyStyle(paint, baseAlpha, samples[s + 2], samples[s + 3], baseWidthPx, tool)
                canvas.drawLine(samples[0], samples[1], samples[s], samples[s + 1], paint)
                return
            }

            // Opening leg: s0 → mid(s0, s1), width from s1.
            scratchPath.reset()
            scratchPath.moveTo(samples[0], samples[1])
            val mid01x = (samples[0] + samples[s]) * 0.5f
            val mid01y = (samples[1] + samples[s + 1]) * 0.5f
            scratchPath.lineTo(mid01x, mid01y)
            applyStyle(paint, baseAlpha, samples[s + 2], samples[s + 3], baseWidthPx, tool)
            canvas.drawPath(scratchPath, paint)

            // Middle segments: mid(s_{i-1}, s_i) → quadTo(s_i) → mid(s_i, s_{i+1}).
            for (i in 1 until sampleCount - 1) {
                val pi = (i - 1) * s
                val ci = i * s
                val ni = (i + 1) * s
                val startX = (samples[pi] + samples[ci]) * 0.5f
                val startY = (samples[pi + 1] + samples[ci + 1]) * 0.5f
                val endX = (samples[ci] + samples[ni]) * 0.5f
                val endY = (samples[ci + 1] + samples[ni + 1]) * 0.5f
                scratchPath.reset()
                scratchPath.moveTo(startX, startY)
                scratchPath.quadTo(samples[ci], samples[ci + 1], endX, endY)
                applyStyle(paint, baseAlpha, samples[ci + 2], samples[ci + 3], baseWidthPx, tool)
                canvas.drawPath(scratchPath, paint)
            }

            // Closing leg: mid(s_{n-2}, s_{n-1}) → s_{n-1}, width from s_{n-1}.
            val lastI = (sampleCount - 1) * s
            val prevI = (sampleCount - 2) * s
            scratchPath.reset()
            scratchPath.moveTo(
                (samples[prevI] + samples[lastI]) * 0.5f,
                (samples[prevI + 1] + samples[lastI + 1]) * 0.5f,
            )
            scratchPath.lineTo(samples[lastI], samples[lastI + 1])
            applyStyle(paint, baseAlpha, samples[lastI + 2], samples[lastI + 3], baseWidthPx, tool)
            canvas.drawPath(scratchPath, paint)
        } finally {
            paint.alpha = baseAlpha
        }
    }

    /**
     * Resolve the per-segment dynamics for [tool] and write [paint]'s width
     * and alpha. [baseAlpha] is the paint's pre-modulation alpha — preserved
     * so an opaque pen colour with explicit per-pixel alpha rounds-trips, and
     * so the highlighter's translucent base alpha still reads as translucent.
     */
    private fun applyStyle(
        paint: Paint,
        baseAlpha: Int,
        pressure: Float,
        tilt: Float,
        baseWidthPx: Float,
        tool: String?,
    ) {
        val style = ToolDynamics.forTool(tool, baseWidthPx, pressure, tilt)
        paint.strokeWidth = style.widthPx
        paint.alpha = (baseAlpha * style.alpha).toInt().coerceIn(0, 255)
    }
}
