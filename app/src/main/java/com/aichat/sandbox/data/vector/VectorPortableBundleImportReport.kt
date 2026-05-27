package com.aichat.sandbox.data.vector

/**
 * A compact, human-friendly summary of what a bundle import did (Phase 10).
 *
 * Derived purely from a [VectorBundleImportPlan], so it is JVM-testable and
 * carries no storage concerns. The repository uses it to attach a single
 * roll-up warning to the import result; the UI uses the warning count to phrase
 * the "Imported project with N warning(s)" status line.
 */
data class VectorPortableBundleImportReport(
    val importedCount: Int,
    val skippedCount: Int,
    val parentsRepaired: Int,
    val hadIdCollision: Boolean,
    val originalAdjusted: Boolean,
) {

    /**
     * A single roll-up warning summarizing the import, or null when nothing
     * noteworthy happened (a clean import).
     */
    fun summaryWarning(): VectorWarning? {
        if (skippedCount == 0 && parentsRepaired == 0 && !hadIdCollision && !originalAdjusted) {
            return null
        }
        val parts = buildList {
            add("imported $importedCount version(s)")
            if (skippedCount > 0) add("skipped $skippedCount")
            if (parentsRepaired > 0) add("repaired $parentsRepaired parent link(s)")
            if (originalAdjusted) add("adjusted the original version")
            if (hadIdCollision) add("re-keyed duplicate ids")
        }
        return VectorWarning(
            VectorWarning.Codes.BUNDLE_IMPORTED_WITH_WARNINGS,
            "Bundle import: ${parts.joinToString(", ")}.",
        )
    }

    companion object {
        fun from(plan: VectorBundleImportPlan): VectorPortableBundleImportReport {
            val parentsRepaired = plan.warnings.count {
                it.code == VectorWarning.Codes.BUNDLE_PARENT_REPAIRED
            }
            val hadIdCollision = plan.warnings.any {
                it.code == VectorWarning.Codes.BUNDLE_ID_REMAPPED
            }
            val originalAdjusted = plan.warnings.any {
                it.code == VectorWarning.Codes.BUNDLE_VERSION_INVALID
            }
            return VectorPortableBundleImportReport(
                importedCount = plan.versions.size,
                skippedCount = plan.warnings.count {
                    it.code == VectorWarning.Codes.BUNDLE_VERSION_XML_INVALID
                },
                parentsRepaired = parentsRepaired,
                hadIdCollision = hadIdCollision,
                originalAdjusted = originalAdjusted,
            )
        }
    }
}
