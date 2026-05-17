package com.aichat.sandbox.ui.screens.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import com.aichat.sandbox.ui.components.notes.DrawingSurfaceView
import com.aichat.sandbox.ui.components.notes.ToolPalette
import com.aichat.sandbox.ui.components.notes.ViewportController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: NoteEditorViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionWorldBounds by viewModel.selectionWorldBounds.collectAsState()
    val selectionMatrix by viewModel.selectionMatrix.collectAsState()
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var viewportController by remember { mutableStateOf<ViewportController?>(null) }

    fun saveAndExit() {
        scope.launch {
            viewModel.save()
            onNavigateBack()
        }
    }

    BackHandler(onBack = ::saveAndExit)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::saveAndExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    OutlinedTextField(
                        value = note.title,
                        onValueChange = viewModel::setTitle,
                        placeholder = { Text("Untitled") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = canUndo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                        )
                    }
                    IconButton(onClick = viewModel::redo, enabled = canRedo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More",
                            )
                        }
                        BackgroundStyleMenu(
                            expanded = menuExpanded,
                            current = note.backgroundStyle,
                            onDismiss = { menuExpanded = false },
                            onSelect = { style ->
                                viewModel.setBackgroundStyle(style)
                                menuExpanded = false
                            },
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                DrawingSurfaceView(
                    items = viewModel.items,
                    backgroundStyle = note.backgroundStyle,
                    paletteState = viewModel.palette,
                    selectedIds = selection,
                    selectionMatrix = selectionMatrix,
                    onStrokeCommitted = viewModel::addItem,
                    onItemsErased = viewModel::removeItems,
                    onLassoCompleted = viewModel::onLassoCompleted,
                    onSelectionShouldClear = viewModel::clearSelection,
                    modifier = Modifier.fillMaxSize(),
                    onViewportReady = { viewportController = it },
                )
                SelectionOverlay(
                    selection = selection,
                    worldBounds = selectionWorldBounds,
                    viewport = viewportController,
                    liveMatrix = selectionMatrix,
                    onTransformChanged = viewModel::updateSelectionTransform,
                    onTransformCommitted = viewModel::bakeSelectionTransform,
                    onDuplicate = viewModel::duplicateSelection,
                    onDelete = viewModel::deleteSelection,
                    onCut = viewModel::cutSelection,
                    onCopy = viewModel::copySelection,
                    onPaste = viewModel::pasteFromClipboard,
                    canPaste = viewModel.hasClipboardContent(),
                )
            }
            ToolPalette(state = viewModel.palette)
        }
    }
}

@Composable
private fun BackgroundStyleMenu(
    expanded: Boolean,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = "Background",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        backgroundChoices.forEach { (style, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onSelect(style) },
                trailingIcon = {
                    if (style == current) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                        )
                    }
                },
            )
        }
    }
}

private val backgroundChoices = listOf(
    BackgroundLayer.STYLE_PLAIN to "Plain",
    BackgroundLayer.STYLE_DOT to "Dot grid",
    BackgroundLayer.STYLE_LINE to "Lines",
    BackgroundLayer.STYLE_GRAPH to "Graph",
)
