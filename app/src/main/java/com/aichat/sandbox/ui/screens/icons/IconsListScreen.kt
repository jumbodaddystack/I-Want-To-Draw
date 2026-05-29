package com.aichat.sandbox.ui.screens.icons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.ui.components.studio.ArtboardCradle
import com.aichat.sandbox.ui.components.studio.StudioPrimaryAction
import com.aichat.sandbox.ui.components.studio.StudioSectionMarker
import com.aichat.sandbox.ui.theme.studio.StudioText
import com.aichat.sandbox.ui.theme.studio.StudioTheme
import java.io.File

/**
 * Icons destination — redesigned on the Studio Bench identity (a "precise pro
 * tool" for frequent icon makers).
 *
 * Departures from the old stock-Material grid:
 *  - Each icon sits in a measured [ArtboardCradle] (corner ticks + grid),
 *    so the gallery reads as a wall of framed artwork, not flat grey cells.
 *  - A mono readout under each tile shows the icon's artboard size — the
 *    instrument voice that runs through the whole identity.
 *  - The header is flat + hairline with an all-caps live count, and the
 *    primary action is the one accent-colored element on screen.
 *  - Adaptive: width-driven columns (tighter, denser on tablet) via
 *    [BoxWithConstraints] — not a phone layout scaled up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconsListScreen(
    onIconClick: (String) -> Unit,
    onNewIcon: () -> Unit,
    viewModel: IconsListViewModel = hiltViewModel(),
) {
    StudioTheme(dark = isSystemInDarkTheme()) {
        val icons by viewModel.icons.collectAsState()
        var pendingDelete by remember { mutableStateOf<Note?>(null) }
        val colors = StudioTheme.colors
        val spacing = StudioTheme.spacing

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvasBase),
        ) {
            // Adaptive: denser grid + persistent inset on wider surfaces.
            val expanded = maxWidth >= StudioTheme.sizing.expandedBreakpoint
            val cellMin = if (expanded) 132.dp else 108.dp
            val gutter = if (expanded) spacing.l else spacing.m

            Column(modifier = Modifier.fillMaxSize()) {
                IconsHeader(count = icons.size)

                if (icons.isEmpty()) {
                    EmptyState(modifier = Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = cellMin),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = spacing.l,
                            end = spacing.l,
                            top = spacing.s,
                            // Leave room for the floating primary action.
                            bottom = 96.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(gutter),
                        verticalArrangement = Arrangement.spacedBy(spacing.l),
                    ) {
                        items(icons, key = { it.id }) { icon ->
                            IconTile(
                                note = icon,
                                onClick = { onIconClick(icon.id) },
                                onLongClick = { pendingDelete = icon },
                            )
                        }
                    }
                }
            }

            // Primary action — the single accent element, pinned bottom-end.
            StudioPrimaryAction(
                label = "New icon",
                icon = Icons.Filled.Draw,
                onClick = onNewIcon,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(spacing.l),
            )
        }

        val target = pendingDelete
        if (target != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete icon?") },
                text = {
                    Text("\"${target.title.ifBlank { "Untitled" }}\" will be permanently removed.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.delete(target)
                        pendingDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun IconsHeader(count: Int) {
    val colors = StudioTheme.colors
    val spacing = StudioTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvasBase)
            .statusBarsPadding()
            .padding(
                PaddingValues(start = spacing.l, end = spacing.l, top = spacing.l, bottom = spacing.m)
            ),
    ) {
        StudioText(text = "Icons", style = StudioTheme.type.display, color = colors.inkStrong)
        Spacer(Modifier.height(spacing.hair))
        // Live mono readout — instrument voice, not a generic subtitle.
        StudioText(
            text = if (count == 0) "EMPTY BENCH" else "$count ON THE BENCH",
            style = StudioTheme.type.section,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(spacing.m))
        com.aichat.sandbox.ui.components.studio.StudioHairline()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconTile(
    note: Note,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = StudioTheme.colors
    val spacing = StudioTheme.spacing

    Column(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        ArtboardCradle(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            dimensionLabel = artboardLabel(note),
            showGrid = note.thumbnailPath == null,
        ) {
            val path = note.thumbnailPath
            if (path != null) {
                // Composite the (often transparent) icon onto a lit face so
                // it reads clearly; fold updatedAt into the cache key.
                val request = remember(path, note.updatedAt) {
                    val cacheKey = "$path:${note.updatedAt}"
                    ImageRequest.Builder(context)
                        .data(File(path))
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .build()
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.m)
                        .clip(RoundedCornerShape(StudioTheme.radius.s))
                        .background(colors.artboardFace)
                        .padding(spacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = note.title.ifBlank { "Untitled icon" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Draw,
                    contentDescription = null,
                    tint = colors.inkFaint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(spacing.s))
        StudioText(
            text = note.title.ifBlank { "Untitled" },
            style = StudioTheme.type.label,
            color = colors.inkDefault,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = spacing.xs),
        )
    }
}

/** Mono dimension label derived from the icon's stroke bounds. */
private fun artboardLabel(note: Note): String {
    val w = (note.maxX - note.minX)
    val h = (note.maxY - note.minY)
    if (w <= 0f || h <= 0f) return "—"
    return "${w.toInt()} × ${h.toInt()}"
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val colors = StudioTheme.colors
    val spacing = StudioTheme.spacing
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Empty bench: a real (empty) cradle, so the empty state previews
            // the very thing the user is about to make.
            ArtboardCradle(
                modifier = Modifier.size(140.dp),
                dimensionLabel = "108 × 108",
                showGrid = true,
            ) {
                Icon(
                    imageVector = Icons.Filled.Draw,
                    contentDescription = null,
                    tint = colors.inkFaint,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(spacing.l))
            StudioText(
                text = "The bench is clear",
                style = StudioTheme.type.title,
                color = colors.inkDefault,
            )
            Spacer(Modifier.height(spacing.xs))
            StudioText(
                text = "Tap New icon to start a vector on a square artboard.",
                style = StudioTheme.type.body,
                color = colors.inkMuted,
            )
        }
    }
}
