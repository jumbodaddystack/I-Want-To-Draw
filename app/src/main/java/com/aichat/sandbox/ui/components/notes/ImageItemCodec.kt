package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sub-phase 6.7 — image-item binary wire format.
 *
 * Layout (little-endian):
 * ```
 * [version:u8=1]
 * [pathLength:u16] [path:utf8 bytes]
 * [naturalWidth:f] [naturalHeight:f]
 * [cropMinX:f] [cropMinY:f] [cropMaxX:f] [cropMaxY:f]   (world units, the
 *                                                         destination rect)
 * [rotation:f]                                          (radians, around the
 *                                                         destination centre)
 * ```
 *
 * `path` is relative to the app's `filesDir` — typically
 * `note-images/<uuid>.<ext>`. Storing relative keeps backups portable; the
 * absolute path is reconstructed lazily by [com.aichat.sandbox.data.notes.NoteImageStore].
 *
 * `naturalWidth` / `naturalHeight` are the decoded source dimensions in
 * pixels. The destination rect is in world units; the renderer maps
 * `[0..naturalWidth, 0..naturalHeight] → [cropMinX..cropMaxX, cropMinY..cropMaxY]`
 * after applying the [rotation].
 */
object ImageItemCodec {

    const val VERSION: Byte = 1

    data class ImagePayload(
        val relativePath: String,
        val naturalWidth: Float,
        val naturalHeight: Float,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val rotationRad: Float = 0f,
    )

    fun encode(payload: ImagePayload): ByteArray {
        val pathBytes = payload.relativePath.toByteArray(Charsets.UTF_8)
        require(pathBytes.size in 0..65_535) {
            "ImageItemCodec: path too long (${pathBytes.size} bytes)"
        }
        val size = 1 + 2 + pathBytes.size + (4 * 7)
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION)
        buf.putShort(pathBytes.size.toShort())
        buf.put(pathBytes)
        buf.putFloat(payload.naturalWidth)
        buf.putFloat(payload.naturalHeight)
        buf.putFloat(payload.minX)
        buf.putFloat(payload.minY)
        buf.putFloat(payload.maxX)
        buf.putFloat(payload.maxY)
        buf.putFloat(payload.rotationRad)
        return buf.array()
    }

    fun decode(bytes: ByteArray): ImagePayload {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get()
        require(version == VERSION) { "ImageItemCodec: unknown version $version" }
        val pathLen = buf.short.toInt() and 0xFFFF
        val pathBytes = ByteArray(pathLen)
        buf.get(pathBytes)
        return ImagePayload(
            relativePath = String(pathBytes, Charsets.UTF_8),
            naturalWidth = buf.float,
            naturalHeight = buf.float,
            minX = buf.float,
            minY = buf.float,
            maxX = buf.float,
            maxY = buf.float,
            rotationRad = buf.float,
        )
    }

    /**
     * Cheap path-only decode — used by [com.aichat.sandbox.data.repository.NoteRepository.deleteNote]
     * to collect referenced files without paying for full payload decode.
     */
    fun decodeRelativePath(bytes: ByteArray): String? = try {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.get() != VERSION) null
        else {
            val len = buf.short.toInt() and 0xFFFF
            val pathBytes = ByteArray(len)
            buf.get(pathBytes)
            String(pathBytes, Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    /** Apply [matrix] to the destination rect and rotation, returning a new payload. */
    fun transform(payload: ImagePayload, matrix: FloatArray): ImagePayload {
        // Transform the four corners of the dest rect, then reassemble as
        // an axis-aligned bounding rect. Rotation absorbed: extract the
        // matrix rotation component and add it to the existing rotation.
        val corners = floatArrayOf(
            payload.minX, payload.minY,
            payload.maxX, payload.minY,
            payload.maxX, payload.maxY,
            payload.minX, payload.maxY,
        )
        val transformed = StrokeTransform.applyToPoints(matrix, corners)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < transformed.size) {
            if (transformed[i] < minX) minX = transformed[i]
            if (transformed[i + 1] < minY) minY = transformed[i + 1]
            if (transformed[i] > maxX) maxX = transformed[i]
            if (transformed[i + 1] > maxY) maxY = transformed[i + 1]
            i += 2
        }
        // Approximation: matrix rotation = atan2(b, a) where matrix = [a, b, _, c, d, _, …]
        val a = matrix[0]
        val b = matrix[3]
        val rot = kotlin.math.atan2(b, a)
        return payload.copy(
            minX = minX, minY = minY, maxX = maxX, maxY = maxY,
            rotationRad = payload.rotationRad + rot,
        )
    }

    /** Axis-aligned bounds of [payload] in world units. */
    fun boundsOf(payload: ImagePayload): FloatArray =
        floatArrayOf(payload.minX, payload.minY, payload.maxX, payload.maxY)
}
