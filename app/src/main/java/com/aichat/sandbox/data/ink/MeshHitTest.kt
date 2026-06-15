package com.aichat.sandbox.data.ink

import androidx.ink.geometry.PartitionedMesh

/**
 * Phase **I6 — mesh-backed geometry adoption**: the fallback-aware façade that
 * backs `HitTest` / `LassoController` with ink's robust geometry for the cases
 * that need accuracy, while **keeping the point-to-segment loops as the
 * fallback** exactly as the migration plan requires.
 *
 * Every entry point takes the stroke's derived [PartitionedMesh] (from
 * [StrokeMeshCache]) and a `fallback` lambda wrapping the existing pure
 * [com.aichat.sandbox.ui.components.notes.HitTest] /
 * [com.aichat.sandbox.ui.screens.notes.LassoController] computation. When the
 * mesh is `null` — the stroke wasn't registered, the native build failed, or the
 * caller has the mesh path disabled (ink is **default-off**; this layer doesn't
 * flip that) — the call degrades to the fallback and behaviour is identical to
 * today. When a mesh is present, the accurate ink query is used. So with the mesh
 * path off the live eraser / lasso are byte-for-byte unchanged, which is what
 * keeps I6 from being a trigger to flip the I2 default-on switch.
 *
 * Non-stroke `NoteItem` kinds (shapes, stickies, connectors, paths) are *never*
 * routed here — they keep their own [com.aichat.sandbox.ui.components.notes.HitTest]
 * geometry — so ink's stroke-only mesh can't displace them (the I2 eraser-parity
 * guarantee).
 */
object MeshHitTest {

    /**
     * True if an eraser tip at (`px`, `py`) with world [radius] hits the stroke.
     * The mesh path tests the tip's bounding box against the stroke's *rendered*
     * geometry, so it catches the body of a wide stroke the centerline-only
     * fallback can miss (partial-erase accuracy). Falls back when [mesh] is null.
     */
    fun eraserHitsStroke(
        mesh: PartitionedMesh?,
        px: Float,
        py: Float,
        radius: Float,
        fallback: () -> Boolean,
    ): Boolean {
        if (mesh == null) return fallback()
        return InkGeometry.intersectsBox(mesh, px - radius, py - radius, px + radius, py + radius)
    }

    /**
     * True if the stroke should be selected by a lasso loop, given the loop's
     * [triangles] (`[ax,ay,bx,by,cx,cy, …]` from [LassoTriangulation]). A hit on
     * any triangle selects, which catches strokes the loop *encloses* and strokes
     * whose body merely *crosses* the loop (overlapping-stroke accuracy). Falls
     * back to the sample-in-polygon loop when [mesh] is null or there are no
     * triangles (degenerate loop).
     */
    fun lassoSelectsStroke(
        mesh: PartitionedMesh?,
        triangles: FloatArray,
        fallback: () -> Boolean,
    ): Boolean {
        if (mesh == null || triangles.size < LassoTriangulation.FLOATS_PER_TRIANGLE) return fallback()
        var i = 0
        while (i + LassoTriangulation.FLOATS_PER_TRIANGLE <= triangles.size) {
            if (InkGeometry.intersectsTriangle(
                    mesh,
                    triangles[i], triangles[i + 1],
                    triangles[i + 2], triangles[i + 3],
                    triangles[i + 4], triangles[i + 5],
                )
            ) {
                return true
            }
            i += LassoTriangulation.FLOATS_PER_TRIANGLE
        }
        return false
    }

    /**
     * Deterministic "what's inside this region" query: the stroke ids whose mesh
     * overlaps the axis-aligned box, in stable registration order. Uses the
     * cache's spatial prefilter to cull candidates before building any mesh, so
     * it scales to large notes. This is the mesh-hit → note-item-id mapping the
     * N2 select-similar / snap engine builds on; the caller resolves each id to
     * its layer (e.g. via `NoteItem.layerId`).
     */
    fun strokesInRegion(
        cache: StrokeMeshCache,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): List<String> {
        val out = ArrayList<String>()
        for (id in cache.candidates(minX, minY, maxX, maxY)) {
            val mesh = cache.meshFor(id) ?: continue
            if (InkGeometry.intersectsBox(mesh, minX, minY, maxX, maxY)) out.add(id)
        }
        return out
    }
}
