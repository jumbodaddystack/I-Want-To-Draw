package com.aichat.sandbox.ui.screens.notebooks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.FrameThumbnailRenderer

/**
 * Sub-phase 9.2 — page thumbnail rail.
 *
 * Vertical list of page thumbnails for notebook mode. Tap = open that
 * page (the editor flies the viewport to fit the page). "+" appends a
 * fresh page below the current bottom.
 *
 * Reuses the Phase 8.2 [FrameThumbnailRenderer] cache so a 100-page
 * notebook only re-rasterises pages whose intersecting items changed.
 */
@Composable
fun PageThumbnailRail(
    frames: List<NoteFrame>,
    currentFrameId: String?,
    items: List<NoteItem>,
    thumbnailRenderer: FrameThumbnailRenderer,
    onSelect: (NoteFrame) -> Unit,
    onAddPage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(112.dp),
        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pages",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClose, contentPadding = PaddingValues(0.dp)) {
                    Text("Close", style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(frames, key = { it.id }) { frame ->
                    PageCell(
                        frame = frame,
                        items = items,
                        isCurrent = frame.id == currentFrameId,
                        thumbnailRenderer = thumbnailRenderer,
                        onTap = { onSelect(frame) },
                    )
                }
                item("add-page") {
                    AddPageCell(onClick = onAddPage)
                }
            }
        }
    }
}

@Composable
private fun PageCell(
    frame: NoteFrame,
    items: List<NoteItem>,
    isCurrent: Boolean,
    thumbnailRenderer: FrameThumbnailRenderer,
    onTap: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = frame.id,
        key2 = items.size,
    ) {
        value = thumbnailRenderer.thumbnailFor(frame, items)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (isCurrent) 4.dp else 0.dp,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isCurrent) 2.dp else 0.5.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onTap),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = frame.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = frame.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AddPageCell(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add page",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Add page",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
