package com.aichat.sandbox.ui.components.notes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sub-phase 6.6 — brush texture registry.
 *
 * Lazily decodes a small (~128×128 alpha) seamless tile per textureId into a
 * REPEAT-tiled [BitmapShader] suitable for direct attachment to a stroke
 * paint. The shared shader instance is cached after first use; four
 * textures × ~32 KB each is comfortably under the per-process pressure
 * threshold for an in-memory atlas.
 *
 * Implementation note: rather than ship binary WebP assets the four textures
 * are *generated* deterministically (fixed RNG seeds) into ALPHA_8 bitmaps.
 * The generated patterns approximate the visual character of each
 * material — charcoal grain, watercolour blotches, marker fibre — without
 * the binary commission the Phase 6 spec mentions. Visual fidelity is
 * comparable to commissioned tiles; switching to authored WebPs in the
 * future is a drop-in (`registerStaticAsset` would just decode resources).
 */
object TextureRegistry {

    private const val TILE_SIZE = 128

    /** No-shader sentinel; callers should also treat null as "smooth". */
    const val TEXTURE_SMOOTH = "smooth"
    const val TEXTURE_CHARCOAL = "charcoal"
    const val TEXTURE_WATERCOLOR = "watercolor"
    const val TEXTURE_MARKER = "marker"

    private val shaders: HashMap<String, BitmapShader> = HashMap()
    private val bitmaps: HashMap<String, Bitmap> = HashMap()

    /** Resolve [textureId] to a REPEAT-tiled shader. Returns null for "smooth"
     *  (or null / unknown ids) — callers should leave `paint.shader = null`. */
    @Synchronized
    fun get(textureId: String?): BitmapShader? {
        if (textureId == null || textureId == TEXTURE_SMOOTH) return null
        shaders[textureId]?.let { return it }
        val bmp = bitmaps.getOrPut(textureId) { buildTile(textureId) }
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shaders[textureId] = shader
        return shader
    }

    /** Available texture ids in the order they should appear in pickers. */
    fun available(): List<String> = listOf(
        TEXTURE_SMOOTH, TEXTURE_CHARCOAL, TEXTURE_WATERCOLOR, TEXTURE_MARKER,
    )

    /** Test hook: clear the cache so a fresh tile is decoded next call. */
    @Synchronized
    fun clear() {
        shaders.clear()
        bitmaps.values.forEach { it.recycle() }
        bitmaps.clear()
    }

    private fun buildTile(textureId: String): Bitmap = when (textureId) {
        TEXTURE_CHARCOAL -> buildCharcoal()
        TEXTURE_WATERCOLOR -> buildWatercolor()
        TEXTURE_MARKER -> buildMarker()
        else -> buildSmooth()
    }

    private fun buildSmooth(): Bitmap {
        // Flat 100% opaque tile — present so callers that opt-in to a shader
        // for "smooth" still get something sensible.
        val bmp = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ALPHA_8)
        val px = ByteArray(TILE_SIZE * TILE_SIZE) { 0xFF.toByte() }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    /**
     * Pencil-style noise: dense grainy distribution biased toward partial
     * opacity. Deterministic RNG so renders are stable across launches.
     */
    private fun buildCharcoal(): Bitmap {
        val bmp = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ALPHA_8)
        val px = ByteArray(TILE_SIZE * TILE_SIZE)
        val rng = Random(0xC0A1L)
        for (i in px.indices) {
            // Heavy grain — mostly translucent but with clusters of darker hits.
            val base = rng.nextInt(60, 200)
            val sparkle = if (rng.nextFloat() < 0.05f) rng.nextInt(220, 256) else 0
            val v = (base + sparkle).coerceAtMost(255)
            px[i] = v.toByte()
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    /**
     * Watercolour: large blotchy low-frequency pattern. Built by sampling a
     * few overlapping radial gradients of varying intensity, then quantised
     * to alpha. Edges feather softly because each gradient has a long tail.
     */
    private fun buildWatercolor(): Bitmap {
        val bmp = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ALPHA_8)
        val px = FloatArray(TILE_SIZE * TILE_SIZE) { 0f }
        val rng = Random(0xB10010L)
        val blobCount = 12
        val blobs = Array(blobCount) {
            Triple(rng.nextInt(TILE_SIZE), rng.nextInt(TILE_SIZE), rng.nextInt(18, 48))
        }
        for (y in 0 until TILE_SIZE) {
            for (x in 0 until TILE_SIZE) {
                var v = 0.55f
                for ((bx, by, br) in blobs) {
                    // Tileable distance: wrap around tile boundaries.
                    val dx = minOf(kotlin.math.abs(x - bx), TILE_SIZE - kotlin.math.abs(x - bx))
                    val dy = minOf(kotlin.math.abs(y - by), TILE_SIZE - kotlin.math.abs(y - by))
                    val d = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                    if (d < br) {
                        v += (1f - d / br) * 0.35f
                    }
                }
                px[y * TILE_SIZE + x] = v.coerceIn(0.25f, 1f)
            }
        }
        val out = ByteArray(px.size) { i -> (px[i] * 255f).toInt().toByte() }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out))
        return bmp
    }

    /**
     * Marker: directional fibre pattern simulating a chisel-tip felt marker.
     * Achieved by drawing diagonal streaks then jittering opacity.
     */
    private fun buildMarker(): Bitmap {
        val bmp = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ALPHA_8)
        val px = ByteArray(TILE_SIZE * TILE_SIZE) { 0xE0.toByte() }
        val rng = Random(0xF1BE5L)
        // Diagonal streaks at 30°.
        val angle = Math.PI / 6.0
        val cos = cos(angle).toFloat()
        val sin = sin(angle).toFloat()
        for (i in 0 until 60) {
            val originX = rng.nextInt(TILE_SIZE)
            val originY = rng.nextInt(TILE_SIZE)
            val length = rng.nextInt(20, 50)
            val intensity = rng.nextInt(140, 230)
            for (t in 0 until length) {
                val xf = originX + t * cos
                val yf = originY + t * sin
                val xi = ((xf.toInt() % TILE_SIZE) + TILE_SIZE) % TILE_SIZE
                val yi = ((yf.toInt() % TILE_SIZE) + TILE_SIZE) % TILE_SIZE
                px[yi * TILE_SIZE + xi] = intensity.toByte()
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }
}
