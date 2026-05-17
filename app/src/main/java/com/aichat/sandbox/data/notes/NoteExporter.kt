package com.aichat.sandbox.data.notes

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 4.1 share / export plumbing.
 *
 * Renders a note (or a future PDF — Phase 4.2) into `cacheDir/exports/` and
 * returns a `content://` URI usable with `Intent.ACTION_SEND`. Writes are
 * atomic (`.tmp` → rename) so we never hand a half-written file to the
 * receiving app, and the directory is pruned after every export to bound
 * disk usage on a power user who shares the same note dozens of times.
 *
 * The class itself is just a thin pipeline around [NoteRasterizer] +
 * [FileProvider]; the pure helpers live in the companion so they can be
 * unit-tested on the JVM without an Android device.
 */
@Singleton
class NoteExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Render [note]'s [items] to a PNG in the shared exports directory and
     * return the `content://` URI granted via [FileProvider]. Empty notes
     * still produce a valid PNG so the share sheet never refuses the
     * outgoing intent — geometry-less notes fall back to a paper-sized
     * blank.
     */
    suspend fun exportPng(
        note: Note,
        items: List<NoteItem>,
        marginWorld: Float = NoteRasterizer.MARGIN_WORLD,
    ): Uri = withContext(Dispatchers.IO) {
        val bounds = NoteRasterizer.computeBounds(items) ?: defaultPaperBounds()
        val bitmap = NoteRasterizer.render(
            items = items,
            bounds = bounds,
            maxEdgePx = EXPORT_MAX_EDGE_PX,
            backgroundStyle = note.backgroundStyle,
            marginWorld = marginWorld,
        )
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val outName = "${sanitizeBaseName(note.title)}-${System.currentTimeMillis()}.png"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            FileOutputStream(tmpFile).use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, os)
            }
            if (finalFile.exists()) finalFile.delete()
            check(tmpFile.renameTo(finalFile)) {
                "NoteExporter: failed to rename ${tmpFile.name} → ${finalFile.name}"
            }
        } finally {
            bitmap.recycle()
            // If we crashed mid-write the tmp file is now stale; renameTo'd
            // tmp files don't exist any more so this is a no-op on success.
            if (tmpFile.exists()) tmpFile.delete()
        }
        pruneOld(dir, keep = MAX_KEEP_FILES)
        FileProvider.getUriForFile(context, fileProviderAuthority(context), finalFile)
    }

    private fun exportsDir(): File = File(context.cacheDir, EXPORTS_SUBDIR)

    companion object {

        const val EXPORTS_SUBDIR: String = "exports"

        /** Longest bitmap edge for PNG exports — comfortably above retina-density paper. */
        const val EXPORT_MAX_EDGE_PX: Int = 2048

        /** How many recent export files to retain in the cache dir. */
        const val MAX_KEEP_FILES: Int = 20

        /** PNG ignores quality but the API still requires the parameter. */
        private const val PNG_QUALITY_IGNORED: Int = 100

        /** Fallback bounds for an empty note so the share still produces a valid PNG. */
        private const val DEFAULT_PAPER_WIDTH: Float = 800f
        private const val DEFAULT_PAPER_HEIGHT: Float = 1100f

        private val NON_SAFE_FILENAME_CHARS: Regex = Regex("[^A-Za-z0-9._-]+")

        /** Max characters of a sanitized note title kept in the export filename. */
        private const val MAX_BASE_NAME_LEN: Int = 40

        /** `${packageName}.fileprovider` — matches the manifest authority. */
        fun fileProviderAuthority(context: Context): String =
            "${context.packageName}.fileprovider"

        /** Synthetic bounds used when a note has no geometry yet. */
        fun defaultPaperBounds(): FloatArray =
            floatArrayOf(0f, 0f, DEFAULT_PAPER_WIDTH, DEFAULT_PAPER_HEIGHT)

        /**
         * Map [title] to a filesystem-safe basename. Empty / all-symbol titles
         * collapse to `"note"`. Truncated to [MAX_BASE_NAME_LEN] so a 5KB
         * title doesn't blow past `NAME_MAX`.
         */
        fun sanitizeBaseName(title: String): String {
            val cleaned = title.trim().replace(NON_SAFE_FILENAME_CHARS, "_").trim('_')
            val collapsed = if (cleaned.isBlank()) "note" else cleaned
            return collapsed.take(MAX_BASE_NAME_LEN)
        }

        /**
         * Keep the most-recently-modified [keep] `.png` files in [dir] and
         * delete the rest. Best-effort: missing dirs, permission errors and
         * concurrent writers are all swallowed silently because pruning is
         * not on a user-visible code path.
         */
        fun pruneOld(dir: File, keep: Int) {
            if (keep < 0) return
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }
                ?: return
            if (files.size <= keep) return
            files.sortedByDescending { it.lastModified() }
                .drop(keep)
                .forEach { runCatching { it.delete() } }
        }
    }
}
