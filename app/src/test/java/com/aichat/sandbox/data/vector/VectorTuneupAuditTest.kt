package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 11 — verifies project-health snapshots fold metrics through the guard. */
class VectorTuneupAuditTest {

    private fun metrics(
        xmlBytes: Int = 0,
        pathCount: Int = 0,
        commandCount: Int = 0,
        warnings: List<VectorWarning> = emptyList(),
    ) = VectorMetrics(
        xmlBytes = xmlBytes, pathCount = pathCount, groupCount = 0, commandCount = commandCount,
        parsedCommandCount = 0, unsupportedPathCount = 0, estimatedPointCount = 0,
        colorCounts = emptyMap(), strokePathCount = 0, fillPathCount = 0,
        zeroLengthPathCount = 0, tinySegmentEstimate = 0, duplicateCoordinateEstimate = 0,
        bounds = null, warnings = warnings,
    )

    @Test
    fun assessMetricsCarriesCountsAndSeverity() {
        val health = VectorTuneupAudit.assessMetrics(
            metrics(
                xmlBytes = 1234, pathCount = 700, commandCount = 50,
                warnings = listOf(VectorWarning("X", "y")),
            ),
        )
        assertEquals(1234, health.xmlBytes)
        assertEquals(700, health.pathCount)
        assertEquals(50, health.commandCount)
        assertEquals(1, health.warningCount)
        // 700 paths > 500 -> LARGE.
        assertEquals(VectorInputHealth.Severity.LARGE, health.severity)
    }

    @Test
    fun assessProjectPicksHeaviestVersion() {
        val report = VectorTuneupAudit.assessProject(
            listOf(
                metrics(xmlBytes = 100, pathCount = 5),       // OK
                metrics(commandCount = 300_000),              // UNSAFE
                metrics(pathCount = 600),                     // LARGE
            ),
        )
        assertEquals(VectorInputHealth.Severity.UNSAFE, report?.severity)
    }

    @Test
    fun assessProjectIsNullWhenEmpty() {
        assertNull(VectorTuneupAudit.assessProject(emptyList()))
    }
}
