package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.ui.theme.kids.KidsCrayons
import com.aichat.sandbox.ui.theme.kids.KidsTheme
import com.aichat.sandbox.ui.theme.kids.readableInkOn

/**
 * The kid-friendly colour picker — a big "crayon box" grid.
 *
 * Replaces the pro [ColorPickerSheet] (hue ring + SV square + alpha slider + hex
 * `#AARRGGBB` input) for the 4–10 audience: one tap on a crayon picks it and
 * closes the sheet. No hex, no channels, no drag math. The caller contract
 * mirrors the old sheet (initial colour, recents, onConfirm(argb), onDismiss) so
 * it slots straight into the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KidsColorSheet(
    initialColorArgb: Int,
    recents: List<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    KidsTheme {
        val c = KidsTheme.colors
        val sp = KidsTheme.spacing
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = c.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.xl)
                    .padding(bottom = sp.xxl),
                verticalArrangement = Arrangement.spacedBy(sp.m),
            ) {
                Text("Pick a colour", style = KidsTheme.type.title, color = c.inkStrong)

                if (recents.isNotEmpty()) {
                    Text("Just used", style = KidsTheme.type.label, color = c.inkMuted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        items(recents) { argb ->
                            Crayon(
                                argb = argb,
                                selected = argb == initialColorArgb,
                                onClick = { onConfirm(argb) },
                            )
                        }
                    }
                }

                Text("Crayons", style = KidsTheme.type.label, color = c.inkMuted)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(sp.s),
                    verticalArrangement = Arrangement.spacedBy(sp.s),
                    contentPadding = PaddingValues(vertical = sp.xs),
                ) {
                    items(KidsCrayons) { argb ->
                        Crayon(
                            argb = argb,
                            selected = argb == initialColorArgb,
                            onClick = { onConfirm(argb) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Crayon(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val c = KidsTheme.colors
    val fill = Color(argb)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = if (selected) 4.dp else 1.dp,
                    color = if (selected) c.primary else c.hairline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Picked",
                    tint = readableInkOn(fill),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}
