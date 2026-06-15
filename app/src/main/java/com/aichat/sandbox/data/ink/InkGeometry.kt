package com.aichat.sandbox.data.ink

import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.geometry.PartitionedMesh

/**
 * Phase **I6 — mesh-backed geometry adoption**: thin, allocation-light wrappers
 * over `ink-geometry`'s robust intersection / coverage / bounding-box routines
 * for a single stroke's derived [PartitionedMesh].
 *
 * These are the accurate queries the migration plan (capability area B) wants the
 * point-to-segment loops in `HitTest` / `LassoController` to defer to for the
 * cases that need true geometry: an eraser tip overlapping a *wide* stroke's
 * body (not just its centerline), a lasso loop crossing overlapping strokes, and
 * "what's inside this region". The mesh is always built in **world coordinates**
 * (the authoring path commits world-space strokes — see [InkInterop]), and every
 * query here is likewise world-space, so the transform is always
 * [AffineTransform.IDENTITY].
 *
 * Only the *stable* `ink-geometry` API is used, and every method takes a mesh the
 * caller already owns, so this composes cleanly with [StrokeMeshCache] and stays
 * on the `ink-*-jvm` headless test classpath.
 */
object InkGeometry {

    private val IDENTITY = AffineTransform.IDENTITY

    /** Mesh AABB as `[minX, minY, maxX, maxY]`, or `null` for an empty mesh. */
    fun boundingBox(mesh: PartitionedMesh): FloatArray? {
        val b = mesh.computeBoundingBox() ?: return null
        return floatArrayOf(b.xMin, b.yMin, b.xMax, b.yMax)
    }

    /** True if the point lies on the mesh (zero-radius hit). */
    fun containsPoint(mesh: PartitionedMesh, px: Float, py: Float): Boolean =
        mesh.intersects(px, py, IDENTITY)

    /**
     * True if the axis-aligned box overlaps the mesh. The eraser uses this with
     * the tip's bounding box so it hits the stroke's *rendered width*, not just
     * its centerline — the partial-erase accuracy win over [HitTest].
     */
    fun intersectsBox(
        mesh: PartitionedMesh, minX: Float, minY: Float, maxX: Float, maxY: Float,
    ): Boolean =
        mesh.intersects(
            ImmutableBox.fromTwoPoints(ImmutableVec(minX, minY), ImmutableVec(maxX, maxY)),
            IDENTITY,
        )

    /** True if the triangle overlaps the mesh (the lasso → triangle path). */
    fun intersectsTriangle(
        mesh: PartitionedMesh,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
    ): Boolean =
        mesh.intersects(
            ImmutableTriangle(ImmutableVec(ax, ay), ImmutableVec(bx, by), ImmutableVec(cx, cy)),
            IDENTITY,
        )

    /**
     * Fraction `[0, 1]` of the box's area the mesh covers — the coverage signal
     * the N2 select-similar / snap engine builds on. Exposed here so the cache
     * layer and its tests can read it without re-deriving the transform.
     */
    fun coverageOfBox(
        mesh: PartitionedMesh, minX: Float, minY: Float, maxX: Float, maxY: Float,
    ): Float =
        mesh.computeCoverage(ImmutableBox.fromTwoPoints(
            ImmutableVec(minX, minY), ImmutableVec(maxX, maxY),
        ), IDENTITY)
}
