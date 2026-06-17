package com.aichat.sandbox.ui.screens.notebooks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.model.Notebook

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        containerColor = Color(0xFFFFF8E7),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = "Doodle Pad",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color(0xFF3F2E56),
            )
            Text(
                text = "Pick a notebook and start drawing!",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF7B5E9E),
            )
            Spacer(Modifier.height(18.dp))
            TextButton(
                onClick = { newNotebookOpen = true },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color(0xFFFFD166),
                    contentColor = Color(0xFF3F2E56),
                ),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Add notebook", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(18.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
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
    Card(
        modifier = Modifier
            .height(210.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = cover),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
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
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.85f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.AutoStories,
                            contentDescription = null,
                            tint = Color(0xFF3F2E56),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = card.notebook.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2F2440),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${card.pageCount.coerceAtLeast(1)} sheet${if (card.pageCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2F2440).copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}
