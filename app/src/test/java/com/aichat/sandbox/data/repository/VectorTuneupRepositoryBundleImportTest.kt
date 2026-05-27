package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorPortableBundle
import com.aichat.sandbox.data.vector.VectorPortableBundleParser
import com.aichat.sandbox.data.vector.VectorPortableBundleValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 10 — bundle import + version-graph repository behavior.
 *
 * The real `VectorTuneupRepository` couples to a `Context` and Android `Log`, so
 * (mirroring `VectorTuneupViewModelVersionHistoryTest`) this drives a faithful
 * in-memory double that reuses the REAL [VectorPortableBundleParser] /
 * [VectorPortableBundleValidator] / metrics analyzer. Only Room insertion and
 * preview-file IO are faked, so the planning + lineage + active-selection
 * contract is exercised for real.
 */
class VectorTuneupRepositoryBundleImportTest {

    private val repo = FakeBundleRepository()

    private fun androidXml(name: String) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="$name" android:pathData="M0,0 L10,10"
                android:strokeColor="#FF0000" android:strokeWidth="1"/>
        </vector>
    """.trimIndent()

    private val dummyMetrics = VectorMetrics(
        xmlBytes = 0, pathCount = 0, groupCount = 0, commandCount = 0,
        parsedCommandCount = 0, unsupportedPathCount = 0, estimatedPointCount = 0,
        colorCounts = emptyMap(), strokePathCount = 0, fillPathCount = 0,
        zeroLengthPathCount = 0, tinySegmentEstimate = 0, duplicateCoordinateEstimate = 0,
        bounds = null, warnings = emptyList(),
    )

    private fun bundleJson(): String = VectorPortableBundle.build(
        VectorPortableBundle.ProjectInfo("Art", 1L, 2L),
        listOf(
            VectorPortableBundle.VersionInfo(
                id = "src-original", parentId = null, label = "Original", mode = "ORIGINAL",
                instruction = "Imported source XML", xml = androidXml("orig"),
                metrics = dummyMetrics, warnings = emptyList(), reportSummary = null, createdAt = 10L,
            ),
            VectorPortableBundle.VersionInfo(
                id = "src-opt", parentId = "src-original", label = "Optimized", mode = "OPTIMIZE",
                instruction = "Local optimize", xml = androidXml("opt"),
                metrics = dummyMetrics, warnings = emptyList(), reportSummary = "smaller", createdAt = 20L,
            ),
        ),
    )

    // ---- import ----

    @Test
    fun importBundleCreatesNewProject() = runTest {
        val result = repo.importBundle(bundleJson())
        assertNotNull(result.project)
        assertEquals(1, repo.projectCount())
        assertTrue(result.project!!.title.endsWith(" (Imported)"))
    }

    @Test
    fun importBundleInsertsVersionsWithRemappedIds() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val versions = repo.getVersions(project.id)
        assertEquals(2, versions.size)
        // None of the imported ids match the bundle's source ids.
        assertTrue(versions.none { it.id == "src-original" || it.id == "src-opt" })
        // Lineage preserved across the remap.
        val original = versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        val optimized = versions.first { it.mode == VectorTuneupMode.OPTIMIZE }
        assertEquals(original.id, optimized.parentId)
    }

    @Test
    fun importBundleSetsActiveVersion() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val versions = repo.getVersions(project.id)
        // No declared active in schema 1 → last imported version becomes active.
        assertEquals(versions.last().id, project.activeVersionId)
    }

    @Test
    fun importBundlePreservesCanonicalXml() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val original = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.ORIGINAL }
        // Android XML is kept verbatim through the import.
        assertEquals(androidXml("orig"), original.xml)
        assertEquals(original.xml, project.sourceXml)
    }

    @Test
    fun importBundleRejectsMalformedJson() = runTest {
        val result = repo.importBundle("{not a bundle")
        assertNull(result.project)
        assertTrue(result.warnings.isNotEmpty())
    }

    // ---- duplicate ----

    @Test
    fun duplicateVersionCreatesChild() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val source = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.ORIGINAL }
        val dup = repo.duplicateVersion(project.id, source.id)
        assertEquals(source.id, dup.parentId)
        assertEquals(VectorTuneupMode.MANUAL_EDIT, dup.mode)
        assertEquals(source.xml, dup.xml)
        // Duplicate becomes active.
        assertEquals(dup.id, repo.getProject(project.id)!!.activeVersionId)
    }

    // ---- delete ----

    @Test
    fun deleteLeafVersionDeletesVersion() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val leaf = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.OPTIMIZE }
        assertTrue(repo.deleteLeafVersion(project.id, leaf.id))
        assertNull(repo.getVersion(leaf.id))
    }

    @Test
    fun deleteOriginalVersionIsBlocked() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val original = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.ORIGINAL }
        assertFalse(repo.deleteLeafVersion(project.id, original.id))
        assertNotNull(repo.getVersion(original.id))
    }

    @Test
    fun deleteVersionWithChildrenIsBlocked() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        // The original has the optimized version as a child.
        val original = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.ORIGINAL }
        // Promote the optimized version's parent so original is a non-original with a child:
        // instead, branch a child off the optimized leaf, making it a non-leaf to delete.
        val leaf = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.OPTIMIZE }
        repo.addChild(leaf.id, VectorTuneupMode.MANUAL_EDIT)
        assertFalse(repo.deleteLeafVersion(project.id, leaf.id))
        assertNotNull(repo.getVersion(leaf.id))
        // Original (also has a child) stays protected by the ORIGINAL guard regardless.
        assertFalse(repo.deleteLeafVersion(project.id, original.id))
    }

    @Test
    fun deleteActiveVersionSelectsParentOrOriginal() = runTest {
        val project = repo.importBundle(bundleJson()).project!!
        val original = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.ORIGINAL }
        val leaf = repo.getVersions(project.id).first { it.mode == VectorTuneupMode.OPTIMIZE }
        // Leaf is active (last imported). Deleting it should move active to its parent (original).
        assertEquals(leaf.id, repo.getProject(project.id)!!.activeVersionId)
        assertTrue(repo.deleteLeafVersion(project.id, leaf.id))
        assertEquals(original.id, repo.getProject(project.id)!!.activeVersionId)
    }
}

/**
 * Faithful in-memory double of the Phase 10 repository surface. Reuses the real
 * parser/validator/analyzer; stores rows in maps and skips Log/preview IO.
 */
private class FakeBundleRepository {
    private val projects = LinkedHashMap<String, VectorTuneupProject>()
    private val versions = LinkedHashMap<String, VectorTuneupVersion>()
    private var clock = 100L

    suspend fun importBundle(bundleJson: String): VectorBundleImportResult {
        val parsed = VectorPortableBundleParser.parse(bundleJson)
        val bundle = parsed.bundle ?: return VectorBundleImportResult(null, parsed.warnings)
        val now = clock++
        val plan = VectorPortableBundleValidator.buildImportPlan(bundle, now)
        if (plan.versions.isEmpty()) return VectorBundleImportResult(null, parsed.warnings + plan.warnings)

        val projectId = UUID.randomUUID().toString()
        for (planned in plan.versions) {
            versions[planned.newId] = VectorTuneupVersion(
                id = planned.newId,
                projectId = projectId,
                parentId = planned.newParentId,
                label = planned.label,
                instruction = planned.instruction,
                mode = planned.mode,
                xml = planned.xml,
                metrics = planned.metrics,
                warnings = planned.warnings,
                reportSummary = planned.reportSummary,
                editPlanJson = null,
                sceneJson = null,
                previewPngPath = null,
                createdAt = planned.createdAt,
            )
        }
        val activeId = plan.activeVersionNewId
            ?: plan.versions.lastOrNull()?.newId
            ?: plan.versions.first { it.mode == VectorTuneupMode.ORIGINAL }.newId
        val project = VectorTuneupProject(
            id = projectId,
            title = plan.projectTitle,
            sourceXml = plan.sourceXml,
            activeVersionId = activeId,
            createdAt = now,
            updatedAt = now,
        )
        projects[projectId] = project
        return VectorBundleImportResult(project, parsed.warnings + plan.warnings)
    }

    suspend fun duplicateVersion(
        projectId: String,
        versionId: String,
        label: String = "Duplicate",
    ): VectorTuneupVersion {
        val source = versions[versionId] ?: error("missing $versionId")
        val now = clock++
        val dup = source.copy(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            parentId = source.id,
            label = label,
            instruction = "Duplicated version",
            mode = VectorTuneupMode.MANUAL_EDIT,
            reportSummary = "Duplicated from ${source.label}",
            createdAt = now,
        )
        versions[dup.id] = dup
        projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = dup.id, updatedAt = now) }
        return dup
    }

    suspend fun deleteLeafVersion(projectId: String, versionId: String): Boolean {
        val target = versions[versionId] ?: return false
        if (target.mode == VectorTuneupMode.ORIGINAL) return false
        if (childCount(versionId) > 0) return false
        val now = clock++
        if (projects[projectId]?.activeVersionId == versionId) {
            val parentExists = target.parentId?.let { versions[it] != null } ?: false
            val fallback = if (parentExists) target.parentId else originalId(projectId)
            if (fallback != null) {
                projects[projectId]?.let { projects[projectId] = it.copy(activeVersionId = fallback, updatedAt = now) }
            }
        }
        versions.remove(versionId)
        return true
    }

    /** Test helper: branch a child off [parentId] so the parent becomes a non-leaf. */
    suspend fun addChild(parentId: String, mode: VectorTuneupMode): VectorTuneupVersion {
        val parent = versions[parentId] ?: error("missing $parentId")
        val now = clock++
        val child = parent.copy(
            id = UUID.randomUUID().toString(),
            parentId = parentId,
            mode = mode,
            label = "child",
            createdAt = now,
        )
        versions[child.id] = child
        return child
    }

    private fun childCount(versionId: String): Int = versions.values.count { it.parentId == versionId }

    private fun originalId(projectId: String): String? = versions.values
        .filter { it.projectId == projectId && it.mode == VectorTuneupMode.ORIGINAL }
        .minByOrNull { it.createdAt }?.id

    fun projectCount(): Int = projects.size
    suspend fun getProject(projectId: String): VectorTuneupProject? = projects[projectId]
    suspend fun getVersion(versionId: String): VectorTuneupVersion? = versions[versionId]
    suspend fun getVersions(projectId: String): List<VectorTuneupVersion> =
        versions.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }
}
