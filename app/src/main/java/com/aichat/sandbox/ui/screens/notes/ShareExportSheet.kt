package com.aichat.sandbox.ui.screens.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.notes.NoteVectorDrawableExporter.IconSize
import com.aichat.sandbox.data.notes.PdfLayout

/**
 * V7 fix — one "Share / Export" surface.
 *
 * Export used to be scattered across the overflow `⋮` menu (six share actions
 * intermixed with background styles, grids, templates…) and then *each* of
 * PDF / SVG / Android-XML opened its own separate `AlertDialog`. There was no
 * single place that answered "how do I get this drawing out?".
 *
 * This bottom sheet collects every export target in one list. Targets with no
 * options (PNG, frame PNG) fire on tap; targets with options
 * (PDF, SVG, Android XML, frame SVG) expand their controls *inline* with an
 * Export button — no second dialog. Only one target is expanded at a time so
 * the sheet stays scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareExportSheet(
    boundsForPdfPreview: FloatArray,
    hasActiveFrame: Boolean,
    vectorPreview: ImageBitmap?,
    vectorSkippedCount: Int,
    onSharePng: () -> Unit,
    onExportPdf: (mode: PdfLayout.Mode, pageSize: PdfLayout.PageSize) -> Unit,
    onShareSvg: (preservePressure: Boolean) -> Unit,
    onExportVectorXml: (sizeDp: Int, preservePressure: Boolean) -> Unit,
    onExportFramePng: () -> Unit,
    onExportFrameSvg: (preservePressure: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Which option-bearing target is currently expanded (null = none). An
    // accordion keeps the sheet short on a phone.
    var expanded by remember { mutableStateOf<ExportTarget?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Share / Export",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            // ---- PNG (no options) ----
            ExportRow(
                icon = Icons.Filled.Image,
                title = "Share as PNG",
                subtitle = "A flat image of the whole note.",
                hasOptions = false,
                expanded = false,
                onClick = onSharePng,
            )

            // ---- PDF (layout + page size) ----
            ExportRow(
                icon = Icons.Filled.PictureAsPdf,
                title = "Share as PDF",
                subtitle = "Fit to one page or tile across a grid.",
                hasOptions = true,
                expanded = expanded == ExportTarget.PDF,
                onClick = { expanded = expanded.toggle(ExportTarget.PDF) },
            )
            AnimatedVisibility(visible = expanded == ExportTarget.PDF) {
                PdfOptions(
                    boundsForPreview = boundsForPdfPreview,
                    onExport = onExportPdf,
                )
            }

            // ---- SVG (preserve pressure) ----
            ExportRow(
                icon = Icons.Filled.Polyline,
                title = "Share as SVG",
                subtitle = "Scalable vector — opens in Inkscape / Figma / browsers.",
                hasOptions = true,
                expanded = expanded == ExportTarget.SVG,
                onClick = { expanded = expanded.toggle(ExportTarget.SVG) },
            )
            AnimatedVisibility(visible = expanded == ExportTarget.SVG) {
                SvgOptions(onExport = { onShareSvg(it) })
            }

            // ---- Android VectorDrawable XML (size + preserve pressure) ----
            ExportRow(
                icon = Icons.Filled.Android,
                title = "Export as Android XML",
                subtitle = "VectorDrawable for res/drawable/.",
                hasOptions = true,
                expanded = expanded == ExportTarget.VECTOR_XML,
                onClick = { expanded = expanded.toggle(ExportTarget.VECTOR_XML) },
            )
            AnimatedVisibility(visible = expanded == ExportTarget.VECTOR_XML) {
                VectorXmlOptions(
                    preview = vectorPreview,
                    skippedCount = vectorSkippedCount,
                    onExport = onExportVectorXml,
                )
            }

            if (hasActiveFrame) {
                HorizontalDivider()
                Text(
                    text = "Current frame",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ExportRow(
                    icon = Icons.Filled.CropFree,
                    title = "Export frame as PNG",
                    subtitle = "Just the active frame's region.",
                    hasOptions = false,
                    expanded = false,
                    onClick = onExportFramePng,
                )
                ExportRow(
                    icon = Icons.Filled.CropFree,
                    title = "Export frame as SVG",
                    subtitle = "Vector export of the active frame.",
                    hasOptions = true,
                    expanded = expanded == ExportTarget.FRAME_SVG,
                    onClick = { expanded = expanded.toggle(ExportTarget.FRAME_SVG) },
                )
                AnimatedVisibility(visible = expanded == ExportTarget.FRAME_SVG) {
                    SvgOptions(onExport = { onExportFrameSvg(it) })
                }
            }
        }
    }
}

/** Targets whose options expand inline; immediate-fire targets aren't listed. */
private enum class ExportTarget { PDF, SVG, VECTOR_XML, FRAME_SVG }

private fun ExportTarget?.toggle(target: ExportTarget): ExportTarget? =
    if (this == target) null else target

@Composable
private fun ExportRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    hasOptions: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasOptions) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Hide options" else "Show options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PdfOptions(
    boundsForPreview: FloatArray,
    onExport: (PdfLayout.Mode, PdfLayout.PageSize) -> Unit,
) {
    var mode by remember { mutableStateOf(PdfLayout.Mode.TILE) }
    var pageSize by remember { mutableStateOf(PdfLayout.defaultPageSize()) }
    val pageCount = remember(mode, pageSize, boundsForPreview) {
        PdfLayout.pageCount(boundsForPreview, pageSize, mode)
    }
    OptionsContainer {
        LayoutModeRow(
            label = "Fit to one page",
            description = "Scale the whole note to fit a single page.",
            selected = mode == PdfLayout.Mode.FIT_ONE_PAGE,
            onSelect = { mode = PdfLayout.Mode.FIT_ONE_PAGE },
        )
        LayoutModeRow(
            label = "Tile across pages",
            description = "Keep size; split across a grid of pages.",
            selected = mode == PdfLayout.Mode.TILE,
            onSelect = { mode = PdfLayout.Mode.TILE },
        )
        Text(
            text = "Paper size",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        PageSizeDropdown(selected = pageSize, onSelect = { pageSize = it })
        Text(
            text = if (pageCount == 1) "This will produce 1 page."
            else "This will produce $pageCount pages.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        ExportButton(onClick = { onExport(mode, pageSize) })
    }
}

@Composable
private fun SvgOptions(onExport: (preservePressure: Boolean) -> Unit) {
    var preservePressure by remember { mutableStateOf(false) }
    OptionsContainer {
        PreservePressureRow(
            checked = preservePressure,
            onCheckedChange = { preservePressure = it },
        )
        ExportButton(onClick = { onExport(preservePressure) })
    }
}

@Composable
private fun VectorXmlOptions(
    preview: ImageBitmap?,
    skippedCount: Int,
    onExport: (sizeDp: Int, preservePressure: Boolean) -> Unit,
) {
    var size by remember { mutableStateOf(IconSize.MEDIUM_48) }
    var preservePressure by remember { mutableStateOf(false) }
    OptionsContainer {
        IconPreview(preview)
        Text(
            text = "Icon size",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        IconSize.entries.forEach { option ->
            SizeRow(
                label = option.label,
                selected = size == option,
                onSelect = { size = option },
            )
        }
        PreservePressureRow(
            checked = preservePressure,
            onCheckedChange = { preservePressure = it },
        )
        if (skippedCount > 0) {
            Text(
                text = "$skippedCount text/image item(s) will be skipped — Android " +
                    "vector drawables only support paths and shapes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ExportButton(onClick = { onExport(size.dp, preservePressure) })
    }
}

@Composable
private fun OptionsContainer(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun ExportButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(onClick = onClick) { Text("Export") }
    }
}
