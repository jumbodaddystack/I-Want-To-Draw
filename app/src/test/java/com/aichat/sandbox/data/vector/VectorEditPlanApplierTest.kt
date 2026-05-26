package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [VectorEditPlanApplier]: each supported operation, the no-op/empty
 * path, unknown-target warnings, viewport/group preservation, and the invariant
 * that output XML always re-parses.
 */
class VectorEditPlanApplierTest {

    // frame=p_001 (stroke #2D2D2D), group g_001 { stem=p_002, stroke #109F5C },
    // dot=p_003 (fill #D62828).
    private val xml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
            <path android:name="frame"
                android:pathData="M16,16 L92,16 L92,92 L16,92 Z"
                android:fillColor="#00000000"
                android:strokeColor="#2D2D2D" android:strokeWidth="2" />
            <group android:name="g1" android:translateX="4">
                <path android:name="stem"
                    android:pathData="M0,0 L1,1 L2,2 L3,3 L4,4 L5,5 L6,6 L7,7 L8,8"
                    android:strokeColor="#109F5C" android:strokeWidth="2" />
            </group>
            <path android:name="dot"
                android:pathData="M54,54 L55,55 Z"
                android:fillColor="#D62828" />
        </vector>
    """.trimIndent()

    private fun doc() = AndroidVectorDrawableParser.parse(xml)

    private fun plan(vararg ops: VectorEditOperation) = VectorEditPlan(
        schema = 1,
        mode = VectorEditPlan.Mode.TUNE_UP,
        summary = "test",
        operations = ops.toList(),
    )

    private fun pathById(result: VectorEditPlanApplyResult, id: String): VectorPath? =
        result.document.allPaths().firstOrNull { it.id == id }

    @Test
    fun applyRecolorPathsById() {
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(VectorEditOperation.RecolorPaths(
                target = VectorPathTarget(pathIds = listOf("p_001")),
                strokeColor = "#FFFFFF",
                fillColor = null,
            )),
        )
        assertEquals(1, result.recoloredPathCount)
        assertEquals("#FFFFFF", pathById(result, "p_001")!!.style.strokeColor)
        // Untouched paths keep their colors.
        assertEquals("#109F5C", pathById(result, "p_002")!!.style.strokeColor)
    }

    @Test
    fun applyRecolorPathsByColor() {
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(VectorEditOperation.RecolorPaths(
                target = VectorPathTarget(colors = listOf("#109f5c")), // case-insensitive
                strokeColor = "#000000",
                fillColor = null,
            )),
        )
        assertEquals(1, result.recoloredPathCount)
        assertEquals("#000000", pathById(result, "p_002")!!.style.strokeColor)
    }

    @Test
    fun applyRestylePaths() {
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(VectorEditOperation.RestylePaths(
                target = VectorPathTarget(pathIds = listOf("p_001")),
                strokeWidth = 1.5f,
                lineCap = "round",
                lineJoin = "round",
            )),
        )
        assertEquals(1, result.restyledPathCount)
        val p = pathById(result, "p_001")!!
        assertEquals(1.5f, p.style.strokeWidth!!, 0.0001f)
        assertEquals("round", p.style.strokeLineCap)
        assertEquals("round", p.style.strokeLineJoin)
    }

    @Test
    fun applyRemovePaths() {
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(VectorEditOperation.RemovePaths(
                target = VectorPathTarget(pathIds = listOf("p_003")),
            )),
        )
        assertEquals(1, result.removedPathCount)
        assertNull("removed path should be gone", pathById(result, "p_003"))
        assertEquals(2, result.document.allPaths().size)
    }

    @Test
    fun applySimplifyPaths() {
        val before = doc().allPaths().first { it.id == "p_002" }.commands!!.size
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(VectorEditOperation.SimplifyPaths(
                target = VectorPathTarget(pathIds = listOf("p_002")),
                tolerance = 0.5f,
                minPathLength = null,
                simplifyFills = false,
            )),
        )
        assertEquals(1, result.simplifiedPathCount)
        val after = pathById(result, "p_002")!!.commands!!.size
        assertTrue("collinear polyline should collapse: $before -> $after", after < before)
    }

    @Test
    fun applyEmptyPlanIsNoOp() {
        val result = VectorEditPlanApplier.apply(doc(), xml, VectorEditPlan.EMPTY)
        assertEquals(xml, result.xml)
        assertEquals(0, result.simplifiedPathCount)
        assertEquals(0, result.removedPathCount)
        assertEquals(0, result.restyledPathCount)
        assertEquals(0, result.recoloredPathCount)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.AI_PLAN_EMPTY })
    }

    @Test
    fun applyUnknownTargetsProduceWarnings() {
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(VectorEditOperation.RemovePaths(
                target = VectorPathTarget(colors = listOf("#ABCDEF")), // no path uses it
            )),
        )
        assertEquals(0, result.removedPathCount)
        assertEquals(1, result.skippedOperationCount)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.AI_PLAN_NO_MATCHING_PATHS })
    }

    @Test
    fun applyResultXmlParsesAgain() {
        val result = VectorEditPlanApplier.apply(
            doc(), xml,
            plan(
                VectorEditOperation.SimplifyPaths(
                    target = VectorPathTarget(pathIds = listOf("p_002")),
                    tolerance = 0.5f, minPathLength = null, simplifyFills = false,
                ),
                VectorEditOperation.RecolorPaths(
                    target = VectorPathTarget(all = true, strokedOnly = true),
                    strokeColor = "#123456", fillColor = null,
                ),
            ),
        )
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        assertFalse(
            "candidate XML must parse cleanly",
            reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML },
        )
        assertEquals(3, reparsed.allPaths().size)
    }

    @Test
    fun applyPreservesViewportAndGroups() {
        val original = doc()
        val result = VectorEditPlanApplier.apply(
            original, xml,
            plan(VectorEditOperation.RecolorPaths(
                target = VectorPathTarget(pathIds = listOf("p_002")),
                strokeColor = "#000000", fillColor = null,
            )),
        )
        assertEquals(original.viewport, result.document.viewport)
        // The group hierarchy survives: g_001 with its translateX is intact.
        val groups = result.document.allGroups()
        assertEquals(1, groups.size)
        assertEquals(4f, groups.single().translateX!!, 0.0001f)
        // The simplified/recolored path is still inside the group, not lifted out.
        assertTrue(pathById(result, "p_002") != null)
    }
}
