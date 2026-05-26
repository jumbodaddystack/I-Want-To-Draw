package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Phase 2 — flattening path commands into absolute-coordinate polylines. */
class VectorPathSamplerTest {

    private fun pt(x: Float, y: Float) = VectorPoint(x, y)

    private fun assertPointEquals(expected: VectorPoint, actual: VectorPoint, eps: Float = 1e-3f) {
        assertTrue(
            "expected $expected but was $actual",
            abs(expected.x - actual.x) <= eps && abs(expected.y - actual.y) <= eps,
        )
    }

    @Test
    fun samplesLineCommandsAsAbsolutePoints() {
        val commands = PathDataParser.parse("M0 0 L10 0 L10 10").commands
        val sampled = VectorPathSampler.sample(commands)
        assertEquals(
            listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f)),
            sampled.points,
        )
        assertFalse(sampled.closed)
        assertFalse(sampled.sourceHadCurves)
    }

    @Test
    fun samplesRelativeCommandsAsAbsolutePoints() {
        val commands = PathDataParser.parse("m10 10 l5 0 l0 5").commands
        val sampled = VectorPathSampler.sample(commands)
        assertEquals(
            listOf(pt(10f, 10f), pt(15f, 10f), pt(15f, 15f)),
            sampled.points,
        )
    }

    @Test
    fun samplesHorizontalAndVerticalCommands() {
        val commands = PathDataParser.parse("M0 0 H10 V20").commands
        val sampled = VectorPathSampler.sample(commands)
        assertEquals(
            listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 20f)),
            sampled.points,
        )
    }

    @Test
    fun samplesQuadraticCurveWithEndpoints() {
        val commands = PathDataParser.parse("M0 0 Q5 10 10 0").commands
        val sampled = VectorPathSampler.sample(commands, curveSteps = 8)
        assertEquals(9, sampled.points.size) // 1 move + 8 curve samples
        assertPointEquals(pt(0f, 0f), sampled.points.first())
        assertPointEquals(pt(10f, 0f), sampled.points.last())
        assertTrue(sampled.sourceHadCurves)
        // The curve bulges downward toward the control point.
        assertTrue(sampled.points.any { it.y > 1f })
    }

    @Test
    fun samplesCubicCurveWithEndpoints() {
        val commands = PathDataParser.parse("M0 0 C0 10 10 10 10 0").commands
        val sampled = VectorPathSampler.sample(commands, curveSteps = 8)
        assertEquals(9, sampled.points.size)
        assertPointEquals(pt(0f, 0f), sampled.points.first())
        assertPointEquals(pt(10f, 0f), sampled.points.last())
        assertTrue(sampled.sourceHadCurves)
    }

    @Test
    fun samplesArcAsEndpointAndFlagsCurves() {
        val commands = PathDataParser.parse("M0 0 A5 5 0 0 1 10 10").commands
        val sampled = VectorPathSampler.sample(commands)
        assertEquals(listOf(pt(0f, 0f), pt(10f, 10f)), sampled.points)
        assertTrue(sampled.sourceHadCurves)
    }

    @Test
    fun preservesClosedPathFlag() {
        val open = VectorPathSampler.sample(PathDataParser.parse("M0 0 L10 0 L10 10").commands)
        val closed = VectorPathSampler.sample(PathDataParser.parse("M0 0 L10 0 L10 10 Z").commands)
        assertFalse(open.closed)
        assertTrue(closed.closed)
    }
}
