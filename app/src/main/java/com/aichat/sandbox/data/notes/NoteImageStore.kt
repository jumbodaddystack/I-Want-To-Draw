package com.aichat.sandbox.data.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 6.7 — file-system layer for inserted images.
 *
 * Copies bytes from a content URI into the app's private `filesDir/note-images/`
 * directory. Returns the relative path (e.g. `note-images/<uuid>.png`) which
 * is stored verbatim in [com.aichat.sandbox.ui.components.notes.ImageItemCodec]
 * payloads. The directory is private so we don't need a runtime permission
 * to read it back; the file picker hands us a transient read URI and we own
 * the copy from there on.
 *
 * Oversized images (longest edge > [MAX_IMAGE_EDGE_PX]) are downsampled at
 * import to keep storage usage bounded. Aspect ratio preserved.
 */
@Singleton
class NoteImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Copy [sourceUri] into `filesDir/note-images/`, returning
     * `(relativePath, naturalWidth, naturalHeight)` on success or `null` on
     * any I/O / decoding failure.
     */
    suspend fun importFromUri(sourceUri: Uri): ImportResult? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tmp = File.createTempFile("img-import-", ".bin", context.cacheDir)
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: return@withContext null

            val (decoded, width, height) = decodeWithCap(tmp.absolutePath)
                ?: return@withContext null

            val ext = guessExtension(sourceUri, resolver)
            val dir = imagesDir().apply { if (!exists()) mkdirs() }
            val relName = "note-images/${UUID.randomUUID()}.$ext"
            val outFile = File(context.filesDir, relName).apply {
                parentFile?.mkdirs()
            }
            FileOutputStream(outFile).use { os ->
                val format = if (ext == "jpg" || ext == "jpeg") Bitmap.CompressFormat.JPEG
                else Bitmap.CompressFormat.PNG
                decoded.compress(format, 92, os)
            }
            decoded.recycle()
            ImportResult(
                relativePath = relName,
                naturalWidth = width.toFloat(),
                naturalHeight = height.toFloat(),
            )
        } catch (_: Throwable) {
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Sweep `note-images/` for files no longer referenced by any note. Safe
     * to call on app start. Compares file names against [referencedRelativePaths].
     */
    suspend fun sweepOrphans(referencedRelativePaths: Set<String>) = withContext(Dispatchers.IO) {
        val dir = imagesDir()
        if (!dir.exists()) return@withContext
        val keep = referencedRelativePaths.mapTo(HashSet()) { File(context.filesDir, it).name }
        dir.listFiles()?.forEach { f ->
            if (f.name !in keep) f.delete()
        }
    }

    private fun imagesDir(): File = File(context.filesDir, "note-images")

    private fun decodeWithCap(absolutePath: String): Triple<Bitmap, Int, Int>? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sampleSize = 1
        val longest = maxOf(probe.outWidth, probe.outHeight)
        while (longest / sampleSize > MAX_IMAGE_EDGE_PX) sampleSize *= 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bmp = BitmapFactory.decodeFile(absolutePath, opts) ?: return null
        return Triple(bmp, bmp.width, bmp.height)
    }

    private fun guessExtension(uri: Uri, resolver: android.content.ContentResolver): String {
        val mime = resolver.getType(uri)
        return when {
            mime == "image/png" -> "png"
            mime == "image/jpeg" || mime == "image/jpg" -> "jpg"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            else -> uri.lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf { it.length in 1..5 }
                ?.lowercase()
                ?: "png"
        }
    }

    data class ImportResult(
        val relativePath: String,
        val naturalWidth: Float,
        val naturalHeight: Float,
    )

    companion object {
        /** Longest-edge cap applied at import. Storage bound; spec calls 4096. */
        const val MAX_IMAGE_EDGE_PX: Int = 4096
    }
}
