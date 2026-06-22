package com.aichat.sandbox.ui.screens.notebooks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.ui.components.BigKidButton
import com.aichat.sandbox.ui.components.KidsScaffold
import com.aichat.sandbox.ui.theme.kids.KidsTheme

@Composable
fun KidsNotebookScreen(
    onBack: () -> Unit,
    onOpenSheet: (noteId: String, frameId: String) -> Unit,
    viewModel: KidsNotebookViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pendingOpen by viewModel.pendingOpenEditor.collectAsState()

    LaunchedEffect(pendingOpen) {
        val target = pendingOpen ?: return@LaunchedEffect
        viewModel.consumePendingOpen()
        onOpenSheet(target.noteId, target.frameId)
    }

    KidsTheme {
        KidsScaffold(
            title = state.notebookTitle,
            subtitle = "Choose a sheet to draw on",
            onBack = onBack,
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                BigKidButton(
                    text = "New sheet",
                    icon = Icons.Filled.Add,
                    onClick = viewModel::addSheet,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(KidsTheme.spacing.l))
                if (state.frames.isEmpty()) {
                    EmptySheets()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 142.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = KidsTheme.spacing.xxl),
                        verticalArrangement = Arrangement.spacedBy(KidsTheme.spacing.l),
                        horizontalArrangement = Arrangement.spacedBy(KidsTheme.spacing.l),
                    ) {
                        items(state.frames, key = { it.id }) { frame ->
                            SheetCard(
                                frame = frame,
                                onClick = { state.noteId?.let { onOpenSheet(it, frame.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySheets() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = KidsTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KidsTheme.spacing.m),
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = KidsTheme.colors.accentSun,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = "No sheets yet!",
            style = KidsTheme.type.heading,
            color = KidsTheme.colors.inkStrong,
        )
        Text(
            text = "Tap “New sheet” to start drawing.",
            style = KidsTheme.type.body,
            color = KidsTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SheetCard(
    frame: NoteFrame,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .heightIn(min = 176.dp)
            .clickable(onClick = onClick),
        shape = KidsTheme.shapes.card,
        colors = CardDefaults.cardColors(containerColor = KidsTheme.colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(KidsTheme.spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KidsTheme.spacing.s),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 96.dp)
                    .clip(KidsTheme.shapes.tile)
                    .background(KidsTheme.colors.surfaceSun),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = KidsTheme.colors.accentSun,
                    modifier = Modifier.size(54.dp),
                )
            }
            Text(
                text = frame.name.ifBlank { "Sheet ${frame.ordinal + 1}" },
                style = KidsTheme.type.label,
                color = KidsTheme.colors.inkStrong,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
