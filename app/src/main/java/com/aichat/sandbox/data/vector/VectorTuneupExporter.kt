package com.aichat.sandbox.data.vector

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aichat.sandbox.data.notes.NoteExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes optimized VectorDrawable XML to a shareable `content://` URI for the
 * Vector Art Tune-Up workspace (Phase 3).
 *
 * Mirrors [com.aichat.sandbox.data.notes.NoteVectorDrawableExporter]: writes
 * into a dedicated subdirectory of the app cache, uses an atomic `.tmp` →
 * rename so a half-written file is never handed out, prunes old exports, and
 * grants read access via the app's existing [FileProvider] authority. It does
 * not touch persistent storage — exports are disposable cache files.
 */
@Singleton
class VectorTuneupExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Save [xml] under a sanitized [baseName] and return a `content://` URI
     * suitable for `Intent.ACTION_SEND`. Runs on the IO dispatcher.
     */
    suspend fun exportXml(
        baseName: String,
        xml: String,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val safeBase = NoteExporter.sanitizeBaseName(baseName)
        val outName = "$safeBase-${System.currentTimeMillis()}.xml"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            FileOutputStream(tmpFile).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            if (finalFile.exists()) finalFile.delete()
            check(tmpFile.renameTo(finalFile)) {
                "VectorTuneupExporter: rename ${tmpFile.name} → ${finalFile.name} failed"
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        NoteExporter.pruneOld(dir, keep = MAX_KEEP_FILES, extensions = MANAGED_EXTENSIONS)
        FileProvider.getUriForFile(context, NoteExporter.fileProviderAuthority(context), finalFile)
    }

    private fun exportsDir(): File =
        File(File(context.cacheDir, NoteExporter.EXPORTS_SUBDIR), VECTOR_SUBDIR)

    companion object {
        /** Vector-specific subdirectory under the shared exports cache dir. */
        const val VECTOR_SUBDIR: String = "vector"

        /** How many recent vector exports to retain. */
        const val MAX_KEEP_FILES: Int = 20

        val MANAGED_EXTENSIONS: Set<String> = setOf("xml")
    }
}
