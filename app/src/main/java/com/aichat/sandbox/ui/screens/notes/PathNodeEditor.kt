package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.PathNodeMath
import com.aichat.sandbox.ui.components.notes.ViewportController
import kotlin.math.hypot

/**
 * Sub-phase 12.3 — node-edit overlay for a single selected path. Phase 17.2
 * makes it multi-subpath: it renders anchors / handles for every subpath of
 * the payload (boolean results with holes, imported icons) and tracks the
 * selection as a `(subpath, anchor)` pair.
 *
 * Renders each subpath's anchors (squares = corner, circles = smooth /
 * symmetric) plus the selected anchor's handles, and supports:
 *
 *  - drag anchor → move it (handles are relative, they ride along);
 *  - drag handle dot → retarget the tangent ([PathNodeMath.moveHandle]
 *    mirrors per anchor type);
 *  - tap anchor → select it (shows its handles);
 *  - double-tap anchor → corner ⇄ smooth;
 *  - tap a bare spot on any contour → insert an anchor there (geometry
 *    preserved via de Casteljau split);
 *  - long-press anchor → delete (a subpath that drops below 2 anchors is
 *    removed; emptying the last subpath deletes the whole item via
 *    [onDeleteItem]).
 *
 * Gesture lifecycle: drags stream [onPreview] payloads (the VM mutates the
 * item directly, no undo entries) and finish with [onGestureEnd] carrying
 * the gesture-start payload — exactly one undo entry per gesture. Tap-like
 * edits go through [onImmediateEdit]. Node editing is modal: the overlay
 * consumes the canvas's touches until **Done**.
 */
@Composable
fun PathNodeEditor(
    item: NoteItem,
    viewport: ViewportController?,
    onPreview: (PathCodec.PathPayload) -> Unit,
    onGestureEnd: (beforePayload: ByteArray, description: String) -> Unit,
    onImmediateEdit: (PathCodec.PathPayload, String) -> Unit,
    onDeleteItem: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (viewport == null) return
    val payload = remember(item.payload) { PathCodec.decode(item.payload) }
    var selected by remember(item.id) { mutableStateOf(NodeRef(0, 0)) }
    // Clamp the selection after an edit shrank / dropped a subpath.
    if (payload.subpaths.getOrNull(selected.subpath)?.anchors?.getOrNull(selected.anchor) == null) {
        selected = NodeRef(0, 0)
    }

    val itemRef by rememberUpdatedState(item)
    val payloadRef by rememberUpdatedState(payload)
    val selectedRef by rememberUpdatedState(selected)
    val onPreviewRef by rememberUpdatedState(onPreview)
    val onGestureEndRef by rememberUpdatedState(onGestureEnd)
    val onImmediateEditRef by rememberUpdatedState(onImmediateEdit)
    val onDeleteItemRef by rememberUpdatedState(onDeleteItem)

    fun screenOf(x: Float, y: Float) =
        Offset(viewport.worldToScreenX(x), viewport.worldToScreenY(y))

    fun anchorAt(sx: Float, sy: Float): NodeRef? {
        val p = payloadRef
        var best: NodeRef? = null
        var bestDist = ANCHOR_GRAB_PX
        for ((si, sub) in p.subpaths.withIndex()) {
            for ((ai, a) in sub.anchors.withIndex()) {
                val s = screenOf(a.x, a.y)
                val d = hypot(s.x - sx, s.y - sy)
                if (d < bestDist) {
                    best = NodeRef(si, ai)
                    bestDist = d
                }
            }
        }
        return best
    }

    /** Hit-test the selected anchor's handle dots. True = out handle. */
    fun handleAt(sx: Float, sy: Float): Boolean? {
        val p = payloadRef
        val sel = selectedRef
        val a = p.subpaths.getOrNull(sel.subpath)?.anchors?.getOrNull(sel.anchor) ?: return null
        val out = screenOf(a.x + a.outDx, a.y + a.outDy)
        val inn = screenOf(a.x + a.inDx, a.y + a.inDy)
        val dOut = hypot(out.x - sx, out.y - sy)
        val dIn = hypot(inn.x - sx, inn.y - sy)
        return when {
            dOut < ANCHOR_GRAB_PX && dOut <= dIn -> true
            dIn < ANCHOR_GRAB_PX -> false
            else -> null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Flattened spine per subpath so the editable curves stay visible
            // even where they overlap other items.
            for (pts in PathCodec.flattenAll(payload)) {
                var i = 2
                while (i < pts.size) {
                    drawLine(
                        color = SPINE_COLOR,
                        start = screenOf(pts[i - 2], pts[i - 1]),
                        end = screenOf(pts[i], pts[i + 1]),
                        strokeWidth = 1.5f,
                    )
                    i += 2
                }
            }
            // Selected anchor's handles.
            payload.subpaths.getOrNull(selected.subpath)?.anchors?.getOrNull(selected.anchor)
                ?.let { a ->
                    val centre = screenOf(a.x, a.y)
                    val out = screenOf(a.x + a.outDx, a.y + a.outDy)
                    val inn = screenOf(a.x + a.inDx, a.y + a.inDy)
                    drawLine(HANDLE_COLOR, centre, out, strokeWidth = 1.5f)
                    drawLine(HANDLE_COLOR, centre, inn, strokeWidth = 1.5f)
                    drawCircle(HANDLE_COLOR, radius = HANDLE_DOT_PX, center = out)
                    drawCircle(HANDLE_COLOR, radius = HANDLE_DOT_PX, center = inn)
                }
            // Anchor markers: squares for corners, circles for smooth/symmetric.
            for ((si, sub) in payload.subpaths.withIndex()) {
                for ((ai, a) in sub.anchors.withIndex()) {
                    val centre = screenOf(a.x, a.y)
                    val isSelected = si == selected.subpath && ai == selected.anchor
                    val fill = if (isSelected) ANCHOR_SELECTED_COLOR else ANCHOR_COLOR
                    if (a.type == PathCodec.TYPE_CORNER) {
                        drawRect(
                            color = fill,
                            topLeft = Offset(centre.x - ANCHOR_DOT_PX, centre.y - ANCHOR_DOT_PX),
                            size = androidx.compose.ui.geometry.Size(ANCHOR_DOT_PX * 2, ANCHOR_DOT_PX * 2),
                        )
                    } else {
                        drawCircle(fill, radius = ANCHOR_DOT_PX, center = centre)
                        drawCircle(Color.White, radius = ANCHOR_DOT_PX, center = centre, style = Stroke(1.5f))
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.id) {
                    detectTapGestures(
                        onTap = { pos ->
                            val hit = anchorAt(pos.x, pos.y)
                            if (hit != null) {
                                selected = hit
                                return@detectTapGestures
                            }
                            if (handleAt(pos.x, pos.y) != null) return@detectTapGestures
                            // Tap on any contour inserts an anchor there.
                            val wx = viewport.screenToWorldX(pos.x)
                            val wy = viewport.screenToWorldY(pos.y)
                            val near = PathNodeMath.nearestOnPath(payloadRef, wx, wy)
                                ?: return@detectTapGestures
                            val grabWorld = INSERT_GRAB_PX / viewport.scale.coerceAtLeast(0.01f)
                            if (near.distance <= grabWorld) {
                                onImmediateEditRef(
                                    PathNodeMath.insertAnchor(
                                        payloadRef, near.segment, near.t, near.subpath,
                                    ),
                                    "Insert anchor",
                                )
                                selected = NodeRef(near.subpath, near.segment + 1)
                            }
                        },
                        onDoubleTap = { pos ->
                            val hit = anchorAt(pos.x, pos.y) ?: return@detectTapGestures
                            selected = hit
                            onImmediateEditRef(
                                PathNodeMath.toggleType(payloadRef, hit.anchor, hit.subpath),
                                "Toggle anchor type",
                            )
                        },
                        onLongPress = { pos ->
                            val hit = anchorAt(pos.x, pos.y) ?: return@detectTapGestures
                            val next = PathNodeMath.deleteAnchor(payloadRef, hit.anchor, hit.subpath)
                            if (next == null) {
                                // Last drawable anchors gone — drop the item.
                                onDeleteItemRef()
                            } else {
                                onImmediateEditRef(next, "Delete anchor")
                                selected = NodeRef(0, 0)
                            }
                        },
                    )
                }
                .pointerInput(item.id) {
                    // Drag state shared by the long-lived detector lambda:
                    // what we grabbed, the gesture-start payload (the undo
                    // `before`), and the live working copy.
                    var dragAnchor: NodeRef? = null
                    var dragHandleOut: Boolean? = null
                    var beforePayload: ByteArray? = null
                    var working: PathCodec.PathPayload? = null
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragAnchor = null
                            dragHandleOut = handleAt(pos.x, pos.y)
                            if (dragHandleOut == null) {
                                dragAnchor = anchorAt(pos.x, pos.y)
                                dragAnchor?.let { selected = it }
                            }
                            if (dragAnchor != null || dragHandleOut != null) {
                                beforePayload = itemRef.payload.copyOf()
                                working = payloadRef
                            }
                        },
                        onDrag = { change, _ ->
                            val base = working ?: return@detectDragGestures
                            change.consume()
                            val wx = viewport.screenToWorldX(change.position.x)
                            val wy = viewport.screenToWorldY(change.position.y)
                            val sel = selectedRef
                            val next = when {
                                dragHandleOut != null ->
                                    PathNodeMath.moveHandle(
                                        base, sel.anchor, dragHandleOut!!, wx, wy, sel.subpath,
                                    )
                                dragAnchor != null ->
                                    PathNodeMath.moveAnchor(
                                        base, dragAnchor!!.anchor, wx, wy, dragAnchor!!.subpath,
                                    )
                                else -> return@detectDragGestures
                            }
                            working = next
                            onPreviewRef(next)
                        },
                        onDragEnd = {
                            beforePayload?.let { before ->
                                val description =
                                    if (dragHandleOut != null) "Adjust handle" else "Move anchor"
                                onGestureEndRef(before, description)
                            }
                            beforePayload = null
                            working = null
                        },
                        onDragCancel = {
                            beforePayload?.let { before ->
                                onGestureEndRef(before, "Move anchor")
                            }
                            beforePayload = null
                            working = null
                        },
                    )
                },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            onClick = onDone,
        ) {
            Text(
                text = "Editing nodes — tap curve to add, long-press to delete · Done",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Selection in the node editor: which anchor of which subpath (17.2). */
private data class NodeRef(val subpath: Int, val anchor: Int)

private val SPINE_COLOR = Color(0x661E88E5)
private val ANCHOR_COLOR = Color(0xFF1E88E5)
private val ANCHOR_SELECTED_COLOR = Color(0xFFD81B60)
private val HANDLE_COLOR = Color(0xFF8E24AA)

private const val ANCHOR_DOT_PX = 7f
private const val HANDLE_DOT_PX = 5.5f
private const val ANCHOR_GRAB_PX = 28f
private const val INSERT_GRAB_PX = 16f
