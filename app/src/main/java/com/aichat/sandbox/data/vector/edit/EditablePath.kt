package com.aichat.sandbox.data.vector.edit

import com.aichat.sandbox.data.vector.VectorStyle

/**
 * Phase 1 (keystone) — the editable view of a [com.aichat.sandbox.data.vector.VectorPath].
 *
 * The immutable [com.aichat.sandbox.data.vector.VectorDocument] /
 * [com.aichat.sandbox.data.vector.PathCommand] types remain the source of truth.
 * This package adds a normalized, **all-cubic, absolute-coordinate** representation
 * that is trivial to hit-test and mutate:
 *
 *  - every curved segment is a cubic Bézier, so a node always has up to two
 *    control handles (incoming + outgoing) — quads and arcs are elevated/flattened
 *    to cubics by [EditablePathFactory];
 *  - all coordinates are absolute (the normalizer resolves relative commands and
 *    smooth shorthands before we ever see them);
 *  - handles are stored as absolute control **points** (not deltas) so they line
 *    up 1:1 with the renderer's output and with on-screen hit-testing.
 *
 * Round-tripping back to path commands is [EditablePathSerializer]'s job; entering
 * edit mode is intentionally lossy at the *token* level (H/V/Q/A/S/T normalize to
 * C/L) but visually faithful — see the round-trip tests.
 */
data class EditablePath(
    val pathId: String,
    val name: String? = null,
    val subpaths: List<EditSubpath>,
    val style: VectorStyle,
)

/** One connected run of [anchors]; [closed] mirrors a trailing `Z`. */
data class EditSubpath(
    val id: String,
    val anchors: List<EditAnchor>,
    val closed: Boolean,
)

/**
 * A single editable node. [x]/[y] is the on-path anchor. [inHandle] is the cubic
 * control point that governs the curve arriving at this anchor; [outHandle] the
 * one governing the curve leaving it. Either is null when that side is a straight
 * line (a "corner with no handle"). Handles are absolute coordinates.
 */
data class EditAnchor(
    val id: String,
    val x: Float,
    val y: Float,
    val inHandle: ControlPoint? = null,
    val outHandle: ControlPoint? = null,
    val type: AnchorType = AnchorType.CORNER,
)

/** An absolute-coordinate cubic control point. */
data class ControlPoint(val x: Float, val y: Float)

/**
 * Cosmetic classification of a node, derived from how its two handles relate.
 * Drives handle-drag behavior in the editor (mirror vs. independent), never the
 * exported geometry.
 *
 *  - [CORNER]    — handles independent (or at least one missing).
 *  - [SMOOTH]    — handles colinear through the anchor (tangent is continuous).
 *  - [SYMMETRIC] — colinear *and* equal length (a true symmetric node).
 */
enum class AnchorType { CORNER, SMOOTH, SYMMETRIC }
