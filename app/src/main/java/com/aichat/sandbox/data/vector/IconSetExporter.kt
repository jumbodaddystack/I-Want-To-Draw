package com.aichat.sandbox.data.vector

/**
 * Phase 3 — pure document → string icon-set exporter (no Android I/O).
 *
 * Derives each [IconTarget] from a master via [IconSizeSet], optionally
 * grid-quantizes it ([VectorQuantizer.quantize]) for pixel-perfect output, and
 * serializes through the **existing** writers ([AndroidVectorDrawableWriter] /
 * [VectorSvgWriter]) — the geometry is already vector, so this is lossless (no
 * flatten/resample, unlike the freehand `NoteVectorDrawableExporter`). One
 * [Artifact] is emitted per `target × format`; [exportBatch] runs many masters in
 * one pass. Everything is pure and re-parseable, so the wire format is pinned by
 * unit tests without a device. The Android file/URI wrapper lives in
 * [VectorTuneupExporter.exportIconSet].
 */
object IconSetExporter {

    enum class Format(val extension: String) {
        ANDROID_VECTOR_DRAWABLE("xml"),
        SVG("svg"),
    }

    data class Spec(
        val sizes: IconSizeSet,
        val formats: Set<Format>,
        val quantize: Boolean = true,
    )

    data class Artifact(
        val target: IconTarget,
        val format: Format,
        val filename: String,
        val content: String,
    )

    /**
     * Derive each target in [spec], quantize when [Spec.quantize] is set, and
     * serialize into one [Artifact] per requested format. [baseName] prefixes the
     * generated filenames (default `"icon"`). Output order is stable: targets in
     * [IconSizeSet.targets] order, formats in declaration order.
     */
    fun exportSet(spec: Spec, baseName: String = "icon"): List<Artifact> {
        val out = ArrayList<Artifact>(spec.sizes.targets.size * spec.formats.size)
        val derived = spec.sizes.deriveAll()
        for (target in spec.sizes.targets) {
            val base = derived[target] ?: continue
            val doc = if (spec.quantize) VectorQuantizer.quantize(base) else base
            for (format in Format.entries) {
                if (format !in spec.formats) continue
                out += Artifact(
                    target = target,
                    format = format,
                    filename = "${baseName}_${target.dp}.${format.extension}",
                    content = serialize(doc, format),
                )
            }
        }
        return out
    }

    /** Batch many masters (`name → spec`) into one flat artifact list. */
    fun exportBatch(specs: List<Pair<String, Spec>>): List<Artifact> =
        specs.flatMap { (name, spec) -> exportSet(spec, baseName = name) }

    private fun serialize(doc: VectorDocument, format: Format): String = when (format) {
        Format.ANDROID_VECTOR_DRAWABLE -> AndroidVectorDrawableWriter.write(doc)
        Format.SVG -> VectorSvgWriter.write(doc)
    }
}
