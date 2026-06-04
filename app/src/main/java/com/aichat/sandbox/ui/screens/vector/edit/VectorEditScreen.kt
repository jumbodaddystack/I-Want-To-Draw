package com.aichat.sandbox.ui.screens.vector.edit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.ui.components.notes.Snap

/** Navigation route for the standalone node editor. */
const val ROUTE_VECTOR_EDIT = "vector-edit"

/**
 * Phase 1 (step 1e) — the screen + toolbar that hosts [VectorEditCanvas].
 *
 * This is the thin chrome around the editor: it collects [VectorEditViewModel.state]
 * and wires a toolbar to the VM's ergonomic pass-throughs. All edit logic stays in
 * the pure [VectorEditReducer] (the screen only reads state to decide which buttons
 * are enabled and which chips read as "on"); gestures go straight to the canvas →
 * VM contract. Writing back into the document is the explicit Done action
 * ([VectorEditViewModel.applyToDocument]); the host (Tune-Up wiring, step 1f) then
 * reads the updated `state.document`.
 *
 * Toolbar policy notes:
 *  - **Pen / Select** switch the active tool. Picking Pen opens a fresh draft when
 *    none is in flight; **Finish** commits the draft into the path.
 *  - **Corner / Smooth / Symmetric** retype an anchor. [VectorEditAction.SetAnchorType]
 *    acts on a single anchor, so these are enabled only when exactly one is selected
 *    (keeps each retype a single undo step).
 *  - **Close** flips the *active subpath* (the one holding the selection, else the
 *    last subpath); it takes a subpath id, not an anchor id.
 *  - **Snap** chips toggle the `Snap.MASK_*` bits the reducer honours while drawing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VectorEditScreen(
    onNavigateBack: () -> Unit = {},
    onDone: () -> Unit = {},
    viewModel: VectorEditViewModel = hiltViewModel(),
) {
    com.aichat.sandbox.ui.theme.studio.StudioTheme(dark = isSystemInDarkTheme()) {
        VectorEditScreenContent(onNavigateBack = onNavigateBack, onDone = onDone, viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VectorEditScreenContent(
    onNavigateBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: VectorEditViewModel,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Nodes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = { viewModel.applyToDocument(); onDone() },
                        enabled = state.editing != null,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Apply edits")
                    }
                },
            )
        },
        bottomBar = { EditToolbar(state = state, viewModel = viewModel) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            VectorEditCanvas(
                state = state,
                viewport = viewModel.viewport,
                modifier = Modifier.fillMaxSize(),
                onTap = { x, y -> viewModel.onTap(x, y) },
                onDragStart = viewModel::onDragStart,
                onDrag = viewModel::onDrag,
                onDragEnd = viewModel::onDragEnd,
                onPan = viewModel::pan,
                onZoom = viewModel::zoom,
            )
        }
    }
}

/**
 * The editor toolbar. Two horizontally-scrollable rows of chips: tools + node
 * actions on top, snap toggles below. Every control maps to a VM pass-through;
 * enablement is derived from the current selection / draft so the bar only offers
 * what the reducer will actually act on.
 */
@Composable
private fun EditToolbar(state: VectorEditState, viewModel: VectorEditViewModel) {
    val selectedAnchor = singleSelectedAnchor(state)
    val activeSubpath = activeSubpath(state)
    val penDraftReady = (state.pendingPen?.anchors?.size ?: 0) >= 2

    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Row 1 — tools + node actions.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.activeTool == EditTool.PEN,
                    onClick = {
                        if (state.activeTool != EditTool.PEN) viewModel.setTool(EditTool.PEN)
                        if (state.pendingPen == null) viewModel.startPath()
                    },
                    label = { Text("Pen") },
                )
                FilterChip(
                    selected = state.activeTool == EditTool.DIRECT_SELECT,
                    onClick = { viewModel.setTool(EditTool.DIRECT_SELECT) },
                    label = { Text("Select") },
                )
                if (state.activeTool == EditTool.PEN) {
                    AssistChip(
                        onClick = { viewModel.commitPath() },
                        enabled = penDraftReady,
                        label = { Text("Finish") },
                    )
                }
                AssistChip(
                    onClick = { viewModel.deleteSelected() },
                    enabled = !state.selection.isEmpty,
                    label = { Text("Delete") },
                )
                FilterChip(
                    selected = selectedAnchor?.type == AnchorType.CORNER,
                    onClick = { selectedAnchor?.let { viewModel.setAnchorType(it.id, AnchorType.CORNER) } },
                    enabled = selectedAnchor != null,
                    label = { Text("Corner") },
                )
                FilterChip(
                    selected = selectedAnchor?.type == AnchorType.SMOOTH,
                    onClick = { selectedAnchor?.let { viewModel.setAnchorType(it.id, AnchorType.SMOOTH) } },
                    enabled = selectedAnchor != null,
                    label = { Text("Smooth") },
                )
                FilterChip(
                    selected = selectedAnchor?.type == AnchorType.SYMMETRIC,
                    onClick = { selectedAnchor?.let { viewModel.setAnchorType(it.id, AnchorType.SYMMETRIC) } },
                    enabled = selectedAnchor != null,
                    label = { Text("Symmetric") },
                )
                FilterChip(
                    selected = activeSubpath?.closed == true,
                    onClick = { activeSubpath?.let { viewModel.toggleClosed(it.id) } },
                    enabled = activeSubpath != null,
                    label = { Text("Close") },
                )
            }

            // Row 2 — snap toggles.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Snap",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SnapChip("Grid", Snap.MASK_GRID, state, viewModel)
                SnapChip("Angle", Snap.MASK_ANGLE, state, viewModel)
                SnapChip("Endpoint", Snap.MASK_ENDPOINT, state, viewModel)
            }
        }
    }
}

@Composable
private fun SnapChip(label: String, bit: Int, state: VectorEditState, viewModel: VectorEditViewModel) {
    FilterChip(
        selected = state.snapMask and bit != 0,
        onClick = { viewModel.setSnapMask(state.snapMask xor bit) },
        label = { Text(label) },
    )
}

/** The single selected anchor, or null unless exactly one is selected. */
private fun singleSelectedAnchor(state: VectorEditState): EditAnchor? {
    val editing = state.editing ?: return null
    if (state.selection.size != 1) return null
    val id = state.selection.anchorIds.first()
    return editing.subpaths.firstNotNullOfOrNull { sp -> sp.anchors.firstOrNull { it.id == id } }
}

/**
 * The subpath the "Close" action targets: the one holding the current selection,
 * falling back to the last subpath (the one a fresh pen draw appends to).
 */
private fun activeSubpath(state: VectorEditState): EditSubpath? {
    val editing = state.editing ?: return null
    val selId = state.selection.anchorIds.firstOrNull()
    return editing.subpaths.firstOrNull { sp -> sp.anchors.any { it.id == selId } }
        ?: editing.subpaths.lastOrNull()
}
