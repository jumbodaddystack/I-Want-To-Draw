package com.aichat.sandbox.data.vector

import com.aichat.sandbox.data.model.VectorTuneupMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 10 — bundle import planning: id remapping, graph repair, canonicalization. */
class VectorPortableBundleValidatorTest {

    private val dummyMetrics = VectorMetrics(
        xmlBytes = 0, pathCount = 0, groupCount = 0, commandCount = 0,
        parsedCommandCount = 0, unsupportedPathCount = 0, estimatedPointCount = 0,
        colorCounts = emptyMap(), strokePathCount = 0, fillPathCount = 0,
        zeroLengthPathCount = 0, tinySegmentEstimate = 0, duplicateCoordinateEstimate = 0,
        bounds = null, warnings = emptyList(),
    )

    private fun androidXml(name: String) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="24" android:viewportHeight="24">
            <path android:name="$name" android:pathData="M0,0 L10,10"
                android:strokeColor="#FF0000" android:strokeWidth="1"/>
        </vector>
    """.trimIndent()

    private val svgXml = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
            <path id="a" d="M0,0 L10,10" fill="#FF0000"/>
        </svg>
    """.trimIndent()

    private fun version(
        id: String,
        parentId: String?,
        mode: String = "ORIGINAL",
        xml: String = androidXml(id),
    ) = VectorPortableBundle.VersionInfo(
        id = id,
        parentId = parentId,
        label = "Label $id",
        mode = mode,
        instruction = "instruction",
        xml = xml,
        metrics = dummyMetrics,
        warnings = emptyList(),
        reportSummary = null,
        createdAt = 0L,
    )

    private fun bundle(vararg versions: VectorPortableBundle.VersionInfo) =
        VectorPortableBundleData(
            schema = 1,
            kind = "vector_tuneup_project",
            project = VectorPortableBundle.ProjectInfo("Art", 0L, 0L),
            versions = versions.toList(),
        )

    @Test
    fun buildImportPlanRemapsIds() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(version("v0", null), version("v1", "v0", "OPTIMIZE")),
            now = 1000L,
        )
        assertEquals(2, plan.versions.size)
        plan.versions.forEach { assertNotEquals(it.oldId, it.newId) }
        val newIds = plan.versions.map { it.newId }.toSet()
        assertEquals(2, newIds.size)
    }

    @Test
    fun buildImportPlanPreservesParentLineage() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(version("v0", null), version("v1", "v0", "OPTIMIZE")),
        )
        val root = plan.versions.first { it.mode == VectorTuneupMode.ORIGINAL }
        val child = plan.versions.first { it.oldId == "v1" }
        assertNull(root.newParentId)
        assertEquals(root.newId, child.newParentId)
    }

    @Test
    fun missingParentIsRepairedWithWarning() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(version("v0", null), version("v1", "ghost", "OPTIMIZE")),
        )
        val child = plan.versions.first { it.oldId == "v1" }
        assertNull(child.newParentId)
        assertTrue(plan.warnings.any { it.code == VectorWarning.Codes.BUNDLE_PARENT_REPAIRED })
    }

    @Test
    fun invalidVersionXmlIsSkipped() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(
                version("v0", null),
                version("v1", "v0", "OPTIMIZE", xml = "this is not xml at all"),
            ),
        )
        assertEquals(1, plan.versions.size)
        assertEquals("v0", plan.versions.single().oldId)
        assertTrue(plan.warnings.any { it.code == VectorWarning.Codes.BUNDLE_VERSION_XML_INVALID })
    }

    @Test
    fun svgVersionXmlIsCanonicalizedToAndroidVector() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(version("v0", null, xml = svgXml)),
        )
        val root = plan.versions.single()
        assertTrue(root.xml.contains("<vector"))
        assertTrue(root.xml.contains("android:pathData"))
        assertTrue(plan.sourceXml.contains("<vector"))
    }

    @Test
    fun firstVersionBecomesOriginalWhenNoOriginalExists() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(version("v0", null, "OPTIMIZE"), version("v1", "v0", "OPTIMIZE")),
        )
        assertEquals(VectorTuneupMode.ORIGINAL, plan.versions.first().mode)
        assertTrue(plan.warnings.any { it.code == VectorWarning.Codes.BUNDLE_VERSION_INVALID })
    }

    @Test
    fun multipleOriginalVersionsWarn() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(version("v0", null, "ORIGINAL"), version("v1", null, "ORIGINAL")),
        )
        val root = plan.versions.first()
        assertEquals(VectorTuneupMode.ORIGINAL, root.mode)
        assertNull(root.newParentId)
        assertTrue(plan.warnings.any { it.code == VectorWarning.Codes.BUNDLE_VERSION_INVALID })
    }

    @Test
    fun emptyValidVersionsProducesFatalWarning() {
        val plan = VectorPortableBundleValidator.buildImportPlan(
            bundle(
                version("v0", null, xml = "garbage one"),
                version("v1", "v0", "OPTIMIZE", xml = "garbage two"),
            ),
        )
        assertTrue(plan.versions.isEmpty())
        assertTrue(plan.warnings.any { it.code == VectorWarning.Codes.BUNDLE_EMPTY })
    }

    @Test
    fun importedTitleGetsSuffix() {
        val plan = VectorPortableBundleValidator.buildImportPlan(bundle(version("v0", null)))
        assertTrue(plan.projectTitle.endsWith(" (Imported)"))
        // Idempotent: an already-suffixed title is not doubled.
        val again = VectorPortableBundleValidator.buildImportPlan(
            VectorPortableBundleData(
                schema = 1, kind = "vector_tuneup_project",
                project = VectorPortableBundle.ProjectInfo("Art (Imported)", 0L, 0L),
                versions = listOf(version("v0", null)),
            ),
        )
        assertEquals("Art (Imported)", again.projectTitle)
    }
}
