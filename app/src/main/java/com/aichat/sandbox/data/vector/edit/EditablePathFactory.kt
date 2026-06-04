package com.aichat.sandbox.data.vector.edit

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.PreviewSegment
import com.aichat.sandbox.data.vector.PreviewSubpath
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorPreviewPathNormalizer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Phase 1 — converts a parsed [VectorPath] into the editable [EditablePath] model.
 *
 * All the hard path math (resolving relative commands, expanding `H`/`V`/`S`/`T`,
 * sampling arcs) is delegated to [VectorPreviewPathNormalizer], which already
 * produces absolute [PreviewSubpath]s of [PreviewSegment.Line]/`Quad`/`Cubic`. We
 * only have to:
 *
 *  1. elevate quads to cubics (a quad and its degree-elevated cubic are the *same*
 *     curve, so this is exact);
 *  2. hang the cubic control points onto the appropriate anchors as in/out handles;
 *  3. fold a curved closing segment that lands back on the start point into the
 *     start anchor so closed shapes have clean node counts.
 *
 * Anchor and subpath ids are deterministic (`"$pathId.s$i.a$j"`) so selection and
 * undo survive across edits.
 */
object EditablePathFactory {

    /** Points closer than this (in viewport units) are treated as coincident. */
    private const val COINCIDENT_EPS = 1e-3f

    fun fromPath(path: VectorPath): EditablePath {
        val commands = path.commands ?: emptyList()
        val subpaths = VectorPreviewPathNormalizer.normalize(commands)
        val editSubpaths = subpaths.mapIndexedNotNull { i, sp ->
            buildSubpath(path.id, i, sp)
        }
        return EditablePath(
            pathId = path.id,
            name = path.name,
            subpaths = editSubpaths,
            style = path.style,
        )
    }

    private fun buildSubpath(pathId: String, index: Int, sp: PreviewSubpath): EditSubpath? {
        val subpathId = "$pathId.s$index"
        // Mutable handle/position accumulators; we assign stable ids at the end.
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val inH = ArrayList<ControlPoint?>()
        val outH = ArrayList<ControlPoint?>()

        // First anchor = subpath start, no incoming handle yet.
        xs += sp.startX; ys += sp.startY; inH += null; outH += null

        for (seg in sp.segments) {
            when (seg) {
                is PreviewSegment.Line -> {
                    xs += seg.endX; ys += seg.endY; inH += null; outH += null
                }
                is PreviewSegment.Cubic -> {
                    outH[outH.lastIndex] = ControlPoint(seg.c1x, seg.c1y)
                    xs += seg.endX; ys += seg.endY
                    inH += ControlPoint(seg.c2x, seg.c2y); outH += null
                }
                is PreviewSegment.Quad -> {
                    val p0x = xs.last(); val p0y = ys.last()
                    // Quadratic → cubic elevation (exact): c1 = p0 + 2/3(ctrl-p0),
                    // c2 = p1 + 2/3(ctrl-p1).
                    val c1x = p0x + 2f / 3f * (seg.cx - p0x)
                    val c1y = p0y + 2f / 3f * (seg.cy - p0y)
                    val c2x = seg.endX + 2f / 3f * (seg.cx - seg.endX)
                    val c2y = seg.endY + 2f / 3f * (seg.cy - seg.endY)
                    outH[outH.lastIndex] = ControlPoint(c1x, c1y)
                    xs += seg.endX; ys += seg.endY
                    inH += ControlPoint(c2x, c2y); outH += null
                }
            }
        }

        if (xs.size < 2) return null // a lone MoveTo carries no editable geometry

        var count = xs.size
        // If the path is closed and its last anchor coincides with the start,
        // fold that closing point back into the start anchor: the closing
        // segment's incoming handle becomes the start anchor's in-handle. This
        // turns "...C <into start> Z" into a clean N-node loop and lets the
        // serializer re-emit the curved closing segment exactly.
        if (sp.closed && count >= 2 &&
            coincident(xs[0], ys[0], xs[count - 1], ys[count - 1])
        ) {
            inH[0] = inH[count - 1]
            count -= 1
        }

        val anchors = ArrayList<EditAnchor>(count)
        for (j in 0 until count) {
            anchors += EditAnchor(
                id = "$subpathId.a$j",
                x = xs[j], y = ys[j],
                inHandle = inH[j], outHandle = outH[j],
                type = classify(xs[j], ys[j], inH[j], outH[j]),
            )
        }
        return EditSubpath(id = subpathId, anchors = anchors, closed = sp.closed)
    }

    private fun coincident(ax: Float, ay: Float, bx: Float, by: Float): Boolean =
        abs(ax - bx) <= COINCIDENT_EPS && abs(ay - by) <= COINCIDENT_EPS

    /**
     * Classify a node from its handles. Smooth when the incoming and outgoing
     * tangents are colinear and point the same way; symmetric additionally when
     * the handle lengths match. Cosmetic only.
     */
    private fun classify(ax: Float, ay: Float, inH: ControlPoint?, outH: ControlPoint?): AnchorType {
        if (inH == null || outH == null) return AnchorType.CORNER
        // Incoming tangent points inHandle → anchor; outgoing points anchor → outHandle.
        val ix = ax - inH.x; val iy = ay - inH.y
        val ox = outH.x - ax; val oy = outH.y - ay
        val li = hypot(ix, iy); val lo = hypot(ox, oy)
        if (li < COINCIDENT_EPS || lo < COINCIDENT_EPS) return AnchorType.CORNER
        val cross = ix * oy - iy * ox
        val dot = ix * ox + iy * oy
        // Colinear (small cross relative to magnitudes) and same direction (dot>0).
        if (abs(cross) > 1e-3f * li * lo || dot <= 0f) return AnchorType.CORNER
        return if (abs(li - lo) <= 1e-2f * max(li, lo)) AnchorType.SYMMETRIC else AnchorType.SMOOTH
    }
}
