package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.model.VectorTuneupMode
import com.aichat.sandbox.data.model.VectorTuneupProjectEntity
import com.aichat.sandbox.data.model.VectorTuneupVersionEntity
import com.aichat.sandbox.data.vector.VectorBounds
import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the Phase 6 persistence helpers: the Gson round-trips
 * in [VectorTuneupPersistenceJson] and the entity↔domain mappers in
 * `VectorTuneupRepository.kt`. Room DAO behavior needs instrumentation and is
 * left to androidTest; these tests pin the mapping/serialization logic that the
 * repository composes.
 */
class VectorTuneupRepositoryTest {

    private val sampleMetrics = VectorMetrics(
        xmlBytes = 120,
        pathCount = 3,
        groupCount = 1,
        commandCount = 10,
        parsedCommandCount = 9,
        unsupportedPathCount = 1,
        estimatedPointCount = 12,
        colorCounts = mapOf("#FF0000" to 2, "#00FF00" to 1),
        strokePathCount = 2,
        fillPathCount = 1,
        zeroLengthPathCount = 0,
        tinySegmentEstimate = 1,
        duplicateCoordinateEstimate = 0,
        bounds = VectorBounds(0f, 0f, 24f, 24f),
        warnings = listOf(VectorWarning("CODE_A", "first")),
    )

    private val sampleWarnings = listOf(
        VectorWarning("CODE_A", "first", "p1"),
        VectorWarning("CODE_B", "second", null),
    )

    // ---- persistence JSON ----

    @Test
    fun metricsRoundTrip() {
        val json = VectorTuneupPersistenceJson.metricsToJson(sampleMetrics)
        assertEquals(sampleMetrics, VectorTuneupPersistenceJson.metricsFromJson(json))
    }

    @Test
    fun warningsRoundTrip() {
        val json = VectorTuneupPersistenceJson.warningsToJson(sampleWarnings)
        assertEquals(sampleWarnings, VectorTuneupPersistenceJson.warningsFromJson(json))
    }

    @Test
    fun emptyWarningsRoundTrip() {
        val json = VectorTuneupPersistenceJson.warningsToJson(emptyList())
        assertTrue(VectorTuneupPersistenceJson.warningsFromJson(json).isEmpty())
    }

    @Test
    fun malformedJsonReturnsFallback() {
        assertEquals(
            VectorTuneupPersistenceJson.EMPTY_METRICS,
            VectorTuneupPersistenceJson.metricsFromJson("{not valid json"),
        )
        assertTrue(VectorTuneupPersistenceJson.warningsFromJson("@@@").isEmpty())
        // Empty string is also a corrupt blob and must not crash.
        assertEquals(
            VectorTuneupPersistenceJson.EMPTY_METRICS,
            VectorTuneupPersistenceJson.metricsFromJson(""),
        )
        assertTrue(VectorTuneupPersistenceJson.warningsFromJson("").isEmpty())
    }

    // ---- entity <-> domain mappers ----

    @Test
    fun projectEntityMapsToDomain() {
        val entity = VectorTuneupProjectEntity(
            id = "proj-1",
            title = "My Vector",
            sourceXml = "<vector/>",
            activeVersionId = "v-1",
            createdAt = 100L,
            updatedAt = 200L,
        )
        val domain = entity.toDomain()
        assertEquals("proj-1", domain.id)
        assertEquals("My Vector", domain.title)
        assertEquals("<vector/>", domain.sourceXml)
        assertEquals("v-1", domain.activeVersionId)
        assertEquals(100L, domain.createdAt)
        assertEquals(200L, domain.updatedAt)
        // round-trips back to an equivalent entity
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun versionEntityMapsToDomain() {
        val entity = VectorTuneupVersionEntity(
            id = "v-1",
            projectId = "proj-1",
            parentId = "v-0",
            label = "Optimized",
            instruction = "Local optimize",
            mode = "OPTIMIZE",
            xml = "<vector/>",
            metricsJson = VectorTuneupPersistenceJson.metricsToJson(sampleMetrics),
            warningsJson = VectorTuneupPersistenceJson.warningsToJson(sampleWarnings),
            reportSummary = "smaller",
            editPlanJson = "{\"schema\":1}",
            sceneJson = null,
            previewPngPath = null,
            createdAt = 300L,
        )
        val domain = entity.toDomain()
        assertEquals(VectorTuneupMode.OPTIMIZE, domain.mode)
        assertEquals("v-0", domain.parentId)
        assertEquals(sampleMetrics, domain.metrics)
        assertEquals(sampleWarnings, domain.warnings)
        assertEquals("{\"schema\":1}", domain.editPlanJson)
    }

    @Test
    fun unknownModeFallsBackToManualEdit() {
        val entity = VectorTuneupVersionEntity(
            id = "v-2",
            projectId = "proj-1",
            parentId = null,
            label = "Mystery",
            instruction = "",
            mode = "SOMETHING_NEW",
            xml = "<vector/>",
            metricsJson = "{}",
            warningsJson = "[]",
            reportSummary = null,
            editPlanJson = null,
            sceneJson = null,
            previewPngPath = null,
            createdAt = 0L,
        )
        assertEquals(VectorTuneupMode.MANUAL_EDIT, entity.toDomain().mode)
    }

    @Test
    fun versionDomainMapsToEntity() {
        val domain = VectorTuneupVersion(
            id = "v-3",
            projectId = "proj-1",
            parentId = "v-0",
            label = "AI Redraw",
            instruction = "make it cleaner",
            mode = VectorTuneupMode.AI_REDRAW,
            xml = "<vector/>",
            metrics = sampleMetrics,
            warnings = sampleWarnings,
            reportSummary = "clean icon",
            editPlanJson = null,
            sceneJson = "{\"schema\":1}",
            previewPngPath = null,
            createdAt = 400L,
        )
        val entity = domain.toEntity()
        assertEquals("AI_REDRAW", entity.mode)
        assertEquals("{\"schema\":1}", entity.sceneJson)
        // entity round-trips back to an equivalent domain object
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun createOriginalVersionBuildsExpectedFields() {
        val original = buildOriginalVersion(
            projectId = "proj-1",
            xml = "<vector/>",
            metrics = sampleMetrics,
            warnings = sampleWarnings,
            createdAt = 500L,
        )
        assertEquals("proj-1", original.projectId)
        assertEquals("Original", original.label)
        assertEquals("Imported source XML", original.instruction)
        assertEquals(VectorTuneupMode.ORIGINAL, original.mode)
        assertNull(original.parentId)
        assertNull(original.previewPngPath)
        assertEquals(500L, original.createdAt)
    }
}
