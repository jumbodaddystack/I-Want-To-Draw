package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11 — verifies the deterministic OK/LARGE/EXTREME/UNSAFE classification of
 * both raw input text (by byte size) and parsed metrics (by size + counts).
 */
class VectorLargeInputGuardTest {

    private fun metrics(
        xmlBytes: Int = 0,
        pathCount: Int = 0,
        commandCount: Int = 0,
    ) = VectorMetrics(
        xmlBytes = xmlBytes, pathCount = pathCount, groupCount = 0, commandCount = commandCount,
        parsedCommandCount = 0, unsupportedPathCount = 0, estimatedPointCount = 0,
        colorCounts = emptyMap(), strokePathCount = 0, fillPathCount = 0,
        zeroLengthPathCount = 0, tinySegmentEstimate = 0, duplicateCoordinateEstimate = 0,
        bounds = null, warnings = emptyList(),
    )

    @Test
    fun largeInputGuardClassifiesInputs() {
        assertEquals(VectorInputHealth.Severity.OK, VectorLargeInputGuard.assessInputText("").severity)
        assertEquals(VectorInputHealth.Severity.OK, VectorLargeInputGuard.assessInputText("a".repeat(1000)).severity)

        val large = "a".repeat(600 * 1024)
        assertEquals(VectorInputHealth.Severity.LARGE, VectorLargeInputGuard.assessInputText(large).severity)

        val extreme = "a".repeat((2.5 * 1024 * 1024).toInt())
        assertEquals(VectorInputHealth.Severity.EXTREME, VectorLargeInputGuard.assessInputText(extreme).severity)

        val unsafe = "a".repeat(6 * 1024 * 1024)
        val health = VectorLargeInputGuard.assessInputText(unsafe)
        assertEquals(VectorInputHealth.Severity.UNSAFE, health.severity)
        assertFalse(health.isSafe)
    }

    @Test
    fun largeInputGuardClassifiesMetrics() {
        assertEquals(
            VectorInputHealth.Severity.OK,
            VectorLargeInputGuard.assessMetrics(metrics(xmlBytes = 1000, pathCount = 10, commandCount = 50)).severity,
        )
        assertEquals(
            VectorInputHealth.Severity.LARGE,
            VectorLargeInputGuard.assessMetrics(metrics(pathCount = 600)).severity,
        )
        assertEquals(
            VectorInputHealth.Severity.EXTREME,
            VectorLargeInputGuard.assessMetrics(metrics(commandCount = 150_000)).severity,
        )
        // Worst signal wins: an UNSAFE command count dominates an OK byte size.
        val health = VectorLargeInputGuard.assessMetrics(metrics(xmlBytes = 100, commandCount = 300_000))
        assertEquals(VectorInputHealth.Severity.UNSAFE, health.severity)
        assertFalse(health.isSafe)
    }

    @Test
    fun safeDefaultIsOk() {
        assertTrue(VectorLargeInputGuard.SAFE.isSafe)
        assertEquals(VectorInputHealth.Severity.OK, VectorLargeInputGuard.SAFE.severity)
    }
}
