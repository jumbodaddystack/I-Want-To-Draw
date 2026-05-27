package com.aichat.sandbox.data.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import java.util.UUID

/**
 * A validated, ready-to-persist import of a portable bundle (Phase 10).
 *
 * Every version has been re-keyed to a fresh local id, its parent lineage
 * remapped onto those ids (with missing parents repaired to null), its XML
 * canonicalized to Android `VectorDrawable` XML, and its metrics recomputed.
 * The plan touches no storage; the repository turns it into a new project plus
 * rows. An empty [versions] list means nothing was importable (see [warnings]).
 *
 * @property activeVersionOldId/[activeVersionNewId] The active version a bundle
 *   declared, if any. The Phase 9 wire format carries no active pointer, so both
 *   are null today and the repository falls back to the last/original version.
 */
data class VectorBundleImportPlan(
    val projectTitle: String,
    val sourceXml: String,
    val versions: List<PlannedVersion>,
    val activeVersionOldId: String?,
    val activeVersionNewId: String?,
    val warnings: List<VectorWarning>,
) {
    data class PlannedVersion(
        val oldId: String,
        val newId: String,
        val oldParentId: String?,
        val newParentId: String?,
        val label: String,
        val instruction: String,
        val mode: VectorTuneupMode,
        val xml: String,
        val metrics: VectorMetrics,
        val warnings: List<VectorWarning>,
        val reportSummary: String?,
        val createdAt: Long,
    )
}

/**
 * Turns a parsed [VectorPortableBundleData] into a safe [VectorBundleImportPlan].
 *
 * Responsibilities (all done before any storage is touched):
 * - Validate each version's XML by parsing it; Android XML is kept verbatim and
 *   SVG is canonicalized to Android XML via [VectorDocumentImporter] +
 *   [AndroidVectorDrawableWriter]. Totally invalid XML is skipped with a warning.
 * - Re-key every kept version to a fresh UUID and remap parent links onto the
 *   new ids; a parent that points at a missing/skipped version is repaired to
 *   null with a warning.
 * - Guarantee a single root: an existing [VectorTuneupMode.ORIGINAL] (the first,
 *   if several) becomes the root with a null parent; if none was declared, the
 *   first valid version is promoted to original. Either adjustment warns.
 * - Recompute metrics on the canonical XML and preserve the bundle's per-version
 *   warnings alongside any new parse warnings.
 *
 * The local project id is intentionally NOT generated here — the repository owns
 * that so the validator stays pure and JVM-testable.
 */
object VectorPortableBundleValidator {

    private const val IMPORTED_SUFFIX = " (Imported)"

    fun buildImportPlan(
        bundle: VectorPortableBundleData,
        now: Long = System.currentTimeMillis(),
    ): VectorBundleImportPlan {
        val warnings = ArrayList<VectorWarning>()

        // ---- 1. validate + canonicalize each version, preserving order ----
        data class Valid(
            val source: VectorPortableBundle.VersionInfo,
            val canonicalXml: String,
            val metrics: VectorMetrics,
            val versionWarnings: List<VectorWarning>,
        )

        val valid = ArrayList<Valid>()
        for (v in bundle.versions) {
            val canonical = canonicalizeXml(v.xml)
            if (canonical == null) {
                warnings += VectorWarning(
                    VectorWarning.Codes.BUNDLE_VERSION_XML_INVALID,
                    "Skipped version \"${v.label}\" — its XML could not be read.",
                    v.id,
                )
                continue
            }
            valid += Valid(
                source = v,
                canonicalXml = canonical.xml,
                metrics = canonical.metrics,
                versionWarnings = (v.warnings + canonical.warnings).distinct(),
            )
        }

        if (valid.isEmpty()) {
            warnings += VectorWarning(
                VectorWarning.Codes.BUNDLE_EMPTY,
                "No versions in this bundle could be imported.",
            )
            return VectorBundleImportPlan(
                projectTitle = importedTitle(bundle.project.title),
                sourceXml = "",
                versions = emptyList(),
                activeVersionOldId = null,
                activeVersionNewId = null,
                warnings = warnings,
            )
        }

        // ---- 2. flag duplicate source ids (collision the remap will fix) ----
        val seenIds = HashSet<String>()
        var sawDuplicateId = false
        for (item in valid) {
            if (!seenIds.add(item.source.id)) sawDuplicateId = true
        }
        if (sawDuplicateId) {
            warnings += VectorWarning(
                VectorWarning.Codes.BUNDLE_ID_REMAPPED,
                "This bundle had duplicate version ids; they were re-keyed to keep the history intact.",
            )
        }

        // ---- 3. choose the single root/original ----
        val declaredOriginals = valid.indices.filter {
            parseMode(valid[it].source.mode) == VectorTuneupMode.ORIGINAL
        }
        val rootIndex: Int
        when {
            declaredOriginals.isEmpty() -> {
                rootIndex = 0
                warnings += VectorWarning(
                    VectorWarning.Codes.BUNDLE_VERSION_INVALID,
                    "No original version was found; the first version was imported as the original.",
                    valid[0].source.id,
                )
            }
            declaredOriginals.size > 1 -> {
                rootIndex = declaredOriginals.first()
                warnings += VectorWarning(
                    VectorWarning.Codes.BUNDLE_VERSION_INVALID,
                    "This bundle declared ${declaredOriginals.size} original versions; the first was kept as the original.",
                )
            }
            else -> rootIndex = declaredOriginals.first()
        }

        // ---- 4. remap ids and rebuild parent lineage ----
        // Map the LAST occurrence of each source id to its new id so a remapped
        // parent reference resolves to a kept version (duplicates aside, this is
        // just oldId -> newId).
        val newIds = valid.map { UUID.randomUUID().toString() }
        val oldToNew = HashMap<String, String>()
        for (i in valid.indices) oldToNew[valid[i].source.id] = newIds[i]

        val planned = ArrayList<VectorBundleImportPlan.PlannedVersion>(valid.size)
        for (i in valid.indices) {
            val item = valid[i]
            val isRoot = i == rootIndex
            val declaredMode = parseMode(item.source.mode)
            val mode = if (isRoot) VectorTuneupMode.ORIGINAL else declaredMode

            val oldParent = item.source.parentId
            val newParent: String? = when {
                isRoot -> null
                oldParent == null -> null
                oldParent == item.source.id -> {
                    warnings += VectorWarning(
                        VectorWarning.Codes.BUNDLE_PARENT_REPAIRED,
                        "Version \"${item.source.label}\" referenced itself as its parent; reparented to root.",
                        newIds[i],
                    )
                    null
                }
                oldToNew.containsKey(oldParent) -> oldToNew[oldParent]
                else -> {
                    warnings += VectorWarning(
                        VectorWarning.Codes.BUNDLE_PARENT_REPAIRED,
                        "Version \"${item.source.label}\" pointed at a missing parent; reparented to root.",
                        newIds[i],
                    )
                    null
                }
            }

            planned += VectorBundleImportPlan.PlannedVersion(
                oldId = item.source.id,
                newId = newIds[i],
                oldParentId = oldParent,
                newParentId = newParent,
                label = item.source.label,
                instruction = item.source.instruction,
                mode = mode,
                xml = item.canonicalXml,
                metrics = item.metrics,
                warnings = item.versionWarnings,
                reportSummary = item.source.reportSummary,
                createdAt = now + i,
            )
        }

        return VectorBundleImportPlan(
            projectTitle = importedTitle(bundle.project.title),
            sourceXml = planned[rootIndex].xml,
            versions = planned,
            activeVersionOldId = null,
            activeVersionNewId = null,
            warnings = warnings,
        )
    }

    // ---- helpers ----

    private data class Canonical(
        val xml: String,
        val metrics: VectorMetrics,
        val warnings: List<VectorWarning>,
    )

    /**
     * Validates and canonicalizes one version's XML, returning null when it is
     * not usable Android VectorDrawable or SVG. Android XML is kept verbatim; SVG
     * is converted to Android XML so every downstream path sees one format.
     */
    private fun canonicalizeXml(xml: String): Canonical? {
        if (xml.isBlank()) return null
        return when (VectorImportDetector.detect(xml)) {
            VectorImportFormat.ANDROID_VECTOR -> {
                val doc = AndroidVectorDrawableParser.parse(xml)
                if (doc.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML }) return null
                Canonical(xml, VectorMetricsAnalyzer.analyze(doc, xml), doc.warnings)
            }
            VectorImportFormat.SVG -> {
                val doc = VectorDocumentImporter.import(xml)
                if (doc.warnings.any { it.code == VectorWarning.Codes.SVG_MALFORMED }) return null
                val canonical = AndroidVectorDrawableWriter.write(doc)
                // Re-parse the canonical XML so the metrics match what is stored.
                val reparsed = AndroidVectorDrawableParser.parse(canonical)
                if (reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML }) return null
                Canonical(canonical, VectorMetricsAnalyzer.analyze(reparsed, canonical), doc.warnings)
            }
            // A bundle-shaped or unrecognized version XML is not a drawable.
            VectorImportFormat.PROJECT_BUNDLE, VectorImportFormat.UNKNOWN -> null
        }
    }

    private fun parseMode(raw: String): VectorTuneupMode =
        runCatching { VectorTuneupMode.valueOf(raw) }.getOrDefault(VectorTuneupMode.MANUAL_EDIT)

    private fun importedTitle(title: String): String {
        val base = title.ifBlank { "Vector Tune-Up" }
        return if (base.endsWith(IMPORTED_SUFFIX)) base else base + IMPORTED_SUFFIX
    }
}
