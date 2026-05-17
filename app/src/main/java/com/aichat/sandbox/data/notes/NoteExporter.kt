package com.aichat.sandbox.data.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
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
        pruneOld(dir, keep = MAX_KEEP_FILES, extensions = MANAGED_EXTENSIONS)
        FileProvider.getUriForFile(context, fileProviderAuthority(context), finalFile)
    }

    /**
     * Render [note]'s [items] to a PDF using [layout] on [pageSize] paper and
     * return the `content://` URI granted via [FileProvider] (sub-phase 4.2).
     *
     * [Mode.FIT_ONE_PAGE] produces a single page with the geometry uniformly
     * scaled and centred; [Mode.TILE] emits a `cols × rows` reading-order
     * grid where each page renders only the strokes that intersect its world
     * slice. World units map 1:1 to page points in tile mode so the printed
     * stroke widths stay consistent across the grid.
     *
     * Pages are rendered sequentially through a single [PdfDocument]; only
     * one page's worth of paint state lives in memory at a time. Caller
     * dispatches to IO via [withContext].
     */
    suspend fun exportPdf(
        note: Note,
        items: List<NoteItem>,
        layout: PdfLayout.Mode,
        pageSize: PdfLayout.PageSize,
        marginPt: Float = PdfLayout.DEFAULT_MARGIN_PT,
    ): Uri = withContext(Dispatchers.IO) {
        val bounds = NoteRasterizer.computeBounds(items) ?: defaultPaperBounds()
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val outName = "${sanitizeBaseName(note.title)}-${System.currentTimeMillis()}.pdf"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")

        val doc = PdfDocument()
        try {
            when (layout) {
                PdfLayout.Mode.FIT_ONE_PAGE -> renderFitPage(
                    doc = doc,
                    items = items,
                    bounds = bounds,
                    pageSize = pageSize,
                    backgroundStyle = note.backgroundStyle,
                    marginPt = marginPt,
                )
                PdfLayout.Mode.TILE -> renderTiledPages(
                    doc = doc,
                    items = items,
                    bounds = bounds,
                    pageSize = pageSize,
                    backgroundStyle = note.backgroundStyle,
                    marginPt = marginPt,
                )
            }
            FileOutputStream(tmpFile).use { os -> doc.writeTo(os) }
        } finally {
            doc.close()
        }
        if (finalFile.exists()) finalFile.delete()
        check(tmpFile.renameTo(finalFile)) {
            "NoteExporter: failed to rename ${tmpFile.name} → ${finalFile.name}"
        }
        if (tmpFile.exists()) tmpFile.delete()
        pruneOld(dir, keep = MAX_KEEP_FILES, extensions = MANAGED_EXTENSIONS)
        FileProvider.getUriForFile(context, fileProviderAuthority(context), finalFile)
    }

    private fun renderFitPage(
        doc: PdfDocument,
        items: List<NoteItem>,
        bounds: FloatArray,
        pageSize: PdfLayout.PageSize,
        backgroundStyle: String,
        marginPt: Float,
    ) {
        val pageInfo = PdfDocument.PageInfo.Builder(
            pageSize.widthPt, pageSize.heightPt, 1,
        ).create()
        val page = doc.startPage(pageInfo)
        try {
            val transform = PdfLayout.fitOnePage(bounds, pageSize, marginPt)
            page.canvas.save()
            page.canvas.translate(transform.translateX, transform.translateY)
            page.canvas.scale(transform.scale, transform.scale)
            NoteRasterizer.drawBackgroundInWorld(
                canvas = page.canvas,
                backgroundStyle = backgroundStyle,
                worldBounds = bounds,
                effectiveScale = transform.scale,
            )
            NoteRasterizer.drawItems(page.canvas, items)
            page.canvas.restore()
        } finally {
            doc.finishPage(page)
        }
    }

    private fun renderTiledPages(
        doc: PdfDocument,
        items: List<NoteItem>,
        bounds: FloatArray,
        pageSize: PdfLayout.PageSize,
        backgroundStyle: String,
        marginPt: Float,
    ) {
        val tiles = PdfLayout.tile(bounds, pageSize, marginPt)
        // Decode every item's bounding box once up-front. The per-tile filter
        // then re-uses these so a 4×4 tile export doesn't pay 16× the stroke-
        // decode cost; items with no geometry (e.g. malformed payloads) drop
        // out at this step rather than being rejected per page.
        val itemsWithBounds: List<Pair<NoteItem, FloatArray>> = items.mapNotNull { item ->
            NoteRasterizer.computeBounds(listOf(item))?.let { item to it }
        }
        for (tile in tiles) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                pageSize.widthPt, pageSize.heightPt, tile.pageIndex + 1,
            ).create()
            val page = doc.startPage(pageInfo)
            try {
                val transform = PdfLayout.tileTranslation(tile, marginPt)
                page.canvas.save()
                page.canvas.translate(transform.translateX, transform.translateY)
                // Clip to the tile's world rect so strokes that span multiple
                // tiles don't bleed past their page margins. Done in world
                // coords (scale=1f in tile mode); a `Canvas.clipRect` call
                // here matches the printable area for the page.
                page.canvas.clipRect(
                    tile.worldBounds[0],
                    tile.worldBounds[1],
                    tile.worldBounds[2],
                    tile.worldBounds[3],
                )
                NoteRasterizer.drawBackgroundInWorld(
                    canvas = page.canvas,
                    backgroundStyle = backgroundStyle,
                    worldBounds = tile.worldBounds,
                    effectiveScale = 1f,
                )
                val tileItems = itemsWithBounds
                    .filter { (_, b) -> rectsIntersect(b, tile.worldBounds) }
                    .map { it.first }
                NoteRasterizer.drawItems(page.canvas, tileItems)
                page.canvas.restore()
            } finally {
                doc.finishPage(page)
            }
        }
    }

    private fun rectsIntersect(a: FloatArray, b: FloatArray): Boolean =
        !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3])

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

        /** File extensions written by [NoteExporter] — shared between PNG + PDF pruning. */
        val MANAGED_EXTENSIONS: Set<String> = setOf("png", "pdf")

        /**
         * Keep the most-recently-modified [keep] managed files (matching
         * [extensions]) in [dir] and delete the rest. Best-effort: missing
         * dirs, permission errors and concurrent writers are all swallowed
         * silently because pruning is not on a user-visible code path.
         *
         * Extension matching is case-insensitive and based on the suffix
         * after the final `.` — files with unrelated suffixes (anything we
         * didn't write) are left alone so we don't accidentally clean up
         * something the caller dropped into the same dir.
         */
        fun pruneOld(
            dir: File,
            keep: Int,
            extensions: Set<String> = setOf("png"),
        ) {
            if (keep < 0) return
            val normalized = extensions.map { it.lowercase() }.toHashSet()
            val files = dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in normalized }
                ?: return
            if (files.size <= keep) return
            files.sortedByDescending { it.lastModified() }
                .drop(keep)
                .forEach { runCatching { it.delete() } }
        }
    }
}
