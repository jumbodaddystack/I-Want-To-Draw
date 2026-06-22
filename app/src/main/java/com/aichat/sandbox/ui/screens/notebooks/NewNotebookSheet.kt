package com.aichat.sandbox.ui.screens.notebooks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.data.model.NotebookPageSize
import com.aichat.sandbox.ui.components.BigKidButton
import com.aichat.sandbox.ui.theme.kids.KidsTheme
import com.aichat.sandbox.ui.theme.kids.readableInkOn

/**
 * The "make a new notebook" flow, redesigned for a 4–10 audience.
 *
 * Everything is tap-only and picture-led: no radio buttons, no paper-size jargon
 * ("A4 / Letter / half-letter landscape" → friendly "Tall" / "Wide"), no adult
 * cover colours. Every target clears the 48dp floor, and the whole sheet is
 * wrapped in [KidsTheme] so it stops being a stock grey Material dialog dropped
 * into the playful gallery. The caller contract is unchanged.
 */
@Composable
fun NewNotebookSheet(
    onCreate: (
        title: String,
        pageSize: NotebookPageSize,
        pageStyle: String,
        coverColorArgb: Int,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var pageSize by remember { mutableStateOf(NotebookPageSize.A4_PORTRAIT) }
    var pageStyle by remember { mutableStateOf(Notebook.STYLE_LINE) }
    var coverColor by remember { mutableStateOf(COVER_COLORS[0]) }

    KidsTheme {
        val c = KidsTheme.colors
        val sp = KidsTheme.spacing
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = KidsTheme.shapes.card,
                color = c.background,
            ) {
                Column(
                    modifier = Modifier
                        .padding(sp.xl)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.l),
                ) {
                    Text("New notebook", style = KidsTheme.type.title, color = c.inkStrong)

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Name it!") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SectionLabel("Shape")
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.m)) {
                        ShapeTile(
                            label = "Tall",
                            portrait = true,
                            selected = pageSize == NotebookPageSize.A4_PORTRAIT,
                            onClick = { pageSize = NotebookPageSize.A4_PORTRAIT },
                            modifier = Modifier.weight(1f),
                        )
                        ShapeTile(
                            label = "Wide",
                            portrait = false,
                            selected = pageSize == NotebookPageSize.HALF_LETTER_LANDSCAPE,
                            onClick = { pageSize = NotebookPageSize.HALF_LETTER_LANDSCAPE },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    SectionLabel("Pages")
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        PAGE_STYLES.forEach { (key, label) ->
                            StyleChip(
                                label = label,
                                selected = pageStyle == key,
                                onClick = { pageStyle = key },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    SectionLabel("Cover")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        items(COVER_COLORS) { argb ->
                            ColorDot(
                                argb = argb,
                                selected = argb == coverColor,
                                onClick = { coverColor = argb },
                            )
                        }
                    }

                    Spacer(Modifier.size(sp.xs))
                    BigKidButton(
                        text = "Make it!",
                        icon = Icons.Filled.Check,
                        onClick = { onCreate(title, pageSize, pageStyle, coverColor) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .heightIn(min = KidsTheme.sizing.touchTarget),
                    ) {
                        Text("Cancel", style = KidsTheme.type.label, color = c.inkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = KidsTheme.type.heading, color = KidsTheme.colors.inkStrong)
}

@Composable
private fun ShapeTile(
    label: String,
    portrait: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KidsTheme.colors
    Column(
        modifier = modifier
            .clip(KidsTheme.shapes.tile)
            .background(if (selected) c.accentSun else c.surface)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) c.primary else c.hairline,
                shape = KidsTheme.shapes.tile,
            )
            .clickable(onClick = onClick)
            .heightIn(min = 84.dp)
            .padding(KidsTheme.spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KidsTheme.spacing.s, Alignment.CenterVertically),
    ) {
        // A little paper rectangle that is tall or wide.
        Box(
            modifier = Modifier
                .then(if (portrait) Modifier.size(width = 24.dp, height = 32.dp) else Modifier.size(width = 36.dp, height = 24.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(c.inkStrong.copy(alpha = if (selected) 0.85f else 0.45f)),
        )
        Text(label, style = KidsTheme.type.label, color = c.inkStrong)
    }
}

@Composable
private fun StyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KidsTheme.colors
    Box(
        modifier = modifier
            .clip(KidsTheme.shapes.chip)
            .background(if (selected) c.primary else c.surface)
            .border(
                width = 1.dp,
                color = if (selected) c.primary else c.hairline,
                shape = KidsTheme.shapes.chip,
            )
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = KidsTheme.spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = KidsTheme.type.label,
            color = if (selected) c.onPrimary else c.inkDefault,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val c = KidsTheme.colors
    val fill = Color(argb)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
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
                    contentDescription = "Selected",
                    tint = readableInkOn(fill),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private val PAGE_STYLES = listOf(
    Notebook.STYLE_PLAIN to "Blank",
    Notebook.STYLE_DOT to "Dots",
    Notebook.STYLE_LINE to "Lines",
    Notebook.STYLE_GRAPH to "Grid",
)

/** Bright, kid-friendly cover colours (a subset of the shared crayon box). */
private val COVER_COLORS: List<Int> = listOf(
    0xFFFF5252.toInt(), // red
    0xFFFF7043.toInt(), // orange
    0xFFFFC233.toInt(), // sunshine
    0xFF8BC34A.toInt(), // grass
    0xFF2FCBA4.toInt(), // mint
    0xFF46A6FF.toInt(), // blue
    0xFF7048C8.toInt(), // grape
    0xFFFF6FB5.toInt(), // bubblegum
)
