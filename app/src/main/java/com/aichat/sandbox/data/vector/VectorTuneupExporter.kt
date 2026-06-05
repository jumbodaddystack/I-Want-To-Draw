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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes an exported Vector Art Tune-Up version to a shareable `content://` URI.
 *
 * Mirrors [com.aichat.sandbox.data.notes.NoteVectorDrawableExporter]: writes
 * into a dedicated subdirectory of the app cache, uses an atomic `.tmp` →
 * rename so a half-written file is never handed out, prunes old exports, and
 * grants read access via the app's existing [FileProvider] authority. It does
 * not touch persistent storage — exports are disposable cache files.
 *
 * Phase 9 adds portable formats: a version's canonical Android XML can be
 * exported as-is, converted to SVG ([VectorSvgWriter]), or — for a whole
 * project — written as a JSON bundle. The original [exportXml] entry point is
 * kept as a thin compatibility wrapper.
 */
@Singleton
class VectorTuneupExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Backwards-compatible Android-XML export (Phase 3 callers). */
    suspend fun exportXml(
        baseName: String,
        xml: String,
    ): Uri = exportVersion(baseName, xml, VectorExportFormat.ANDROID_VECTOR_XML)

    /**
     * Exports a version's canonical Android [xml] in the requested [format].
     *
     * - [VectorExportFormat.ANDROID_VECTOR_XML]: the XML, unchanged.
     * - [VectorExportFormat.SVG]: parses the XML to a [VectorDocument] and writes
     *   SVG via [VectorSvgWriter].
     * - [VectorExportFormat.PROJECT_BUNDLE]: treats [xml] as pre-built bundle
     *   JSON and writes it verbatim (use [exportBundle] to build it).
     *
     * Runs on the IO dispatcher.
     */
    suspend fun exportVersion(
        baseName: String,
        xml: String,
        format: VectorExportFormat,
    ): Uri {
        val content = when (format) {
            VectorExportFormat.ANDROID_VECTOR_XML -> xml
            VectorExportFormat.SVG -> VectorSvgWriter.write(AndroidVectorDrawableParser.parse(xml))
            VectorExportFormat.PROJECT_BUNDLE -> xml
        }
        return writeFile(baseName, content, format.extension)
    }

    /** Builds a portable project bundle from [bundleJson] and writes it as JSON. */
    suspend fun exportBundle(
        baseName: String,
        bundleJson: String,
    ): Uri = writeFile(baseName, bundleJson, VectorExportFormat.PROJECT_BUNDLE.extension)

    /**
     * Phase 3 — render a multi-size, multi-format icon set ([IconSetExporter.exportSet],
     * lossless) and write every [IconSetExporter.Artifact] into a single shareable
     * `.zip`. Each artifact becomes one zip entry under its generated filename. The
     * geometry math is pure; this only adds the file/URI plumbing (mirrors
     * [exportBundle]'s atomic `.tmp` → rename + prune). Runs on the IO dispatcher.
     */
    suspend fun exportIconSet(
        baseName: String,
        spec: IconSetExporter.Spec,
    ): Uri = withContext(Dispatchers.IO) {
        val artifacts = IconSetExporter.exportSet(spec, baseName = NoteExporter.sanitizeBaseName(baseName))
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val safeBase = NoteExporter.sanitizeBaseName(baseName)
        val outName = "$safeBase-${System.currentTimeMillis()}.zip"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            ZipOutputStream(FileOutputStream(tmpFile)).use { zip ->
                for (artifact in artifacts) {
                    zip.putNextEntry(ZipEntry(artifact.filename))
                    zip.write(artifact.content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
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

    private suspend fun writeFile(
        baseName: String,
        content: String,
        extension: String,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val safeBase = NoteExporter.sanitizeBaseName(baseName)
        val outName = "$safeBase-${System.currentTimeMillis()}.$extension"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            FileOutputStream(tmpFile).use { it.write(content.toByteArray(Charsets.UTF_8)) }
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

        val MANAGED_EXTENSIONS: Set<String> = setOf("xml", "svg", "json", "zip")
    }
}
