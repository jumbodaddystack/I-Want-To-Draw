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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.ui.components.BigKidButton
import com.aichat.sandbox.ui.components.KidsScaffold
import com.aichat.sandbox.ui.theme.kids.KidsTheme
import com.aichat.sandbox.ui.theme.kids.readableInkOn

@Composable
fun KidsHomeScreen(
    onOpenNotebook: (noteId: String) -> Unit,
    viewModel: NotebooksListViewModel = hiltViewModel(),
) {
    val notebooks by viewModel.notebooks.collectAsState()
    val pendingNav by viewModel.pendingNavigation.collectAsState()
    var newNotebookOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureDefaultNotebook()
    }

    LaunchedEffect(pendingNav) {
        val target = pendingNav ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onOpenNotebook(target)
    }

    KidsTheme {
        KidsScaffold(
            title = "Doodle Pad",
            subtitle = "Pick a notebook and start drawing!",
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                BigKidButton(
                    text = "Add notebook",
                    icon = Icons.Filled.Add,
                    onClick = { newNotebookOpen = true },
                )
                Spacer(Modifier.size(KidsTheme.spacing.l))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = KidsTheme.spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(KidsTheme.spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(KidsTheme.spacing.l),
                ) {
                    items(notebooks, key = { it.notebook.id }) { card ->
                        KidsNotebookCard(
                            card = card,
                            onClick = { card.noteId?.let(onOpenNotebook) },
                        )
                    }
                }
            }
        }
    }

    if (newNotebookOpen) {
        NewNotebookSheet(
            onCreate = { title, pageSize, pageStyle, coverColor ->
                viewModel.createNotebook(title, pageSize, pageStyle, coverColor)
                newNotebookOpen = false
            },
            onDismiss = { newNotebookOpen = false },
        )
    }
}

@Composable
private fun KidsNotebookCard(
    card: NotebooksListViewModel.NotebookCard,
    onClick: () -> Unit,
) {
    val cover = Color(card.notebook.coverColorArgb)
    val onCover = readableInkOn(cover)
    Card(
        modifier = Modifier
            .heightIn(min = 200.dp)
            .clickable(onClick = onClick),
        shape = KidsTheme.shapes.card,
        colors = CardDefaults.cardColors(containerColor = cover),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Playful translucent bubbles in the corner add personality without
            // depending on the cover colour.
            repeat(5) { index ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (16 + index * 16).dp, end = 16.dp)
                        .size((20 + index * 5).dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(KidsTheme.spacing.l),
                verticalArrangement = Arrangement.spacedBy(KidsTheme.spacing.s, Alignment.Bottom),
            ) {
                val badgeBg = if (onCover == Color.White) {
                    Color.White.copy(alpha = 0.88f)
                } else {
                    KidsTheme.colors.inkStrong.copy(alpha = 0.12f)
                }
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = badgeBg,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.AutoStories,
                            contentDescription = null,
                            tint = onCover,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = card.notebook.title,
                    style = KidsTheme.type.heading,
                    color = onCover,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${card.pageCount.coerceAtLeast(1)} sheet${if (card.pageCount == 1) "" else "s"}",
                    style = KidsTheme.type.label,
                    color = onCover.copy(alpha = 0.85f),
                )
            }
        }
    }
}
