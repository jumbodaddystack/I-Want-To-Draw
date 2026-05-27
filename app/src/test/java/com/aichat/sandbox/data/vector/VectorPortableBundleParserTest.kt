package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 10 — parsing/validating portable project bundle JSON for import. */
class VectorPortableBundleParserTest {

    private val metrics = VectorMetrics(
        xmlBytes = 100,
        pathCount = 1,
        groupCount = 0,
        commandCount = 2,
        parsedCommandCount = 2,
        unsupportedPathCount = 0,
        estimatedPointCount = 2,
        colorCounts = mapOf("#FF0000" to 1),
        strokePathCount = 1,
        fillPathCount = 0,
        zeroLengthPathCount = 0,
        tinySegmentEstimate = 0,
        duplicateCoordinateEstimate = 0,
        bounds = VectorBounds(0f, 0f, 10f, 10f),
        warnings = emptyList(),
    )

    private fun version(id: String, parentId: String?, mode: String = "ORIGINAL") =
        VectorPortableBundle.VersionInfo(
            id = id,
            parentId = parentId,
            label = "Label $id",
            mode = mode,
            instruction = "instruction",
            xml = "<vector/>",
            metrics = metrics,
            warnings = emptyList(),
            reportSummary = null,
            createdAt = 123L,
        )

    private fun bundleJson(vararg versions: VectorPortableBundle.VersionInfo): String =
        VectorPortableBundle.build(
            VectorPortableBundle.ProjectInfo("My Vector", 1L, 2L),
            versions.toList(),
        )

    @Test
    fun parseValidBundle() {
        val result = VectorPortableBundleParser.parse(
            bundleJson(version("v0", null), version("v1", "v0", "OPTIMIZE")),
        )
        val bundle = result.bundle
        assertNotNull(bundle)
        assertEquals(VectorPortableBundle.SCHEMA, bundle!!.schema)
        assertEquals(VectorPortableBundle.KIND, bundle.kind)
        assertEquals("My Vector", bundle.project.title)
        assertEquals(2, bundle.versions.size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parseRejectsMalformedJson() {
        val result = VectorPortableBundleParser.parse("{not valid json")
        assertNull(result.bundle)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.BUNDLE_PARSE_FAILED })
    }

    @Test
    fun parseRejectsWrongKind() {
        val json = """
            { "schema": 1, "kind": "something_else",
              "project": { "title": "x", "createdAt": 0, "updatedAt": 0 },
              "versions": [ { "id": "v0", "parentId": null, "xml": "<vector/>" } ] }
        """.trimIndent()
        val result = VectorPortableBundleParser.parse(json)
        assertNull(result.bundle)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.BUNDLE_WRONG_KIND })
    }

    @Test
    fun parseRejectsUnsupportedSchema() {
        val json = """
            { "schema": 2, "kind": "vector_tuneup_project",
              "project": { "title": "x", "createdAt": 0, "updatedAt": 0 },
              "versions": [ { "id": "v0", "parentId": null, "xml": "<vector/>" } ] }
        """.trimIndent()
        val result = VectorPortableBundleParser.parse(json)
        assertNull(result.bundle)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.BUNDLE_UNSUPPORTED_SCHEMA })
    }

    @Test
    fun parseRejectsEmptyVersions() {
        val result = VectorPortableBundleParser.parse(bundleJson())
        assertNull(result.bundle)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.BUNDLE_EMPTY })
    }

    @Test
    fun parsePreservesVersionOrder() {
        val result = VectorPortableBundleParser.parse(
            bundleJson(
                version("first", null),
                version("second", "first", "OPTIMIZE"),
                version("third", "second", "MANUAL_EDIT"),
            ),
        )
        val ids = result.bundle!!.versions.map { it.id }
        assertEquals(listOf("first", "second", "third"), ids)
    }

    @Test
    fun parseNeverThrowsOnGarbage() {
        val garbage = listOf(
            "", "   ", "{", "}", "[]", "null", "12345", "\"a string\"",
            "{ \"schema\": \"oops\" }", "{ \"kind\": 42 }", "<vector/>",
            "{ \"schema\": 1, \"kind\": \"vector_tuneup_project\" }",
        )
        for (g in garbage) {
            // Must return a value, never throw.
            VectorPortableBundleParser.parse(g)
        }
    }
}
