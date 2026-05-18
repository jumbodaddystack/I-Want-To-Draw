package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SnapTest {

    @Test
    fun nearHorizontalLineSnapsTo0Degrees() {
        // 3° off horizontal — inside default 5° tolerance.
        val angle = (PI / 60.0).toFloat()
        val endX = kotlin.math.cos(angle) * 100f
        val endY = kotlin.math.sin(angle) * 100f
        val r = Snap.snapAngleTo(0f, 0f, endX, endY)
        assertTrue(r.snapped)
        assertEquals(100f, r.x, 1e-3f)
        assertEquals(0f, r.y, 1e-3f)
    }

    @Test
    fun farOff45DoesNotSnap() {
        // 30° — well outside the 5° tolerance for the 15° step.
        val r = Snap.snapAngleTo(0f, 0f, 100f, 30f,
            stepRad = (PI / 4).toFloat(), toleranceRad = (PI / 36).toFloat())
        assertFalse(r.snapped)
    }

    @Test
    fun gridSnapsToNearestIntersection() {
        val r = Snap.snapToGrid(34f, 35f, spacing = 32f, toleranceWorld = 8f)
        assertTrue(r.snapped)
        assertEquals(32f, r.x, 0f)
        assertEquals(32f, r.y, 0f)
    }

    @Test
    fun gridSkipsWhenOutsideTolerance() {
        val r = Snap.snapToGrid(45f, 45f, spacing = 32f, toleranceWorld = 8f)
        assertFalse(r.snapped)
        assertEquals(45f, r.x, 0f)
        assertEquals(45f, r.y, 0f)
    }

    @Test
    fun endpointSnapsToClosestWithinRadius() {
        val candidates = floatArrayOf(0f, 0f, 100f, 100f, 50f, 50f)
        val r = Snap.snapToEndpoints(48f, 49f, candidates, radiusWorld = 8f)
        assertTrue(r.snapped)
        assertEquals(50f, r.x, 0f)
        assertEquals(50f, r.y, 0f)
    }

    @Test
    fun endpointSkipsWhenAllOutsideRadius() {
        val candidates = floatArrayOf(0f, 0f, 100f, 100f)
        val r = Snap.snapToEndpoints(40f, 40f, candidates, radiusWorld = 8f)
        assertFalse(r.snapped)
    }
}
