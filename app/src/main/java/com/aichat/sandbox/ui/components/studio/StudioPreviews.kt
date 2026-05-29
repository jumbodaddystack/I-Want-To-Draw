package com.aichat.sandbox.ui.components.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.ui.theme.studio.StudioText
import com.aichat.sandbox.ui.theme.studio.StudioTheme

/**
 * Authoring previews for the Studio Bench primitives. Cover dark (default
 * posture), light parity, the signature artboard cradle, and the empty/filled
 * tile + active/idle tool states so the identity can be eyeballed without a
 * device. These are @Preview only — no runtime cost.
 */

@Composable
private fun StudioGallery() {
    val c = StudioTheme.colors
    val s = StudioTheme.spacing
    Column(
        modifier = Modifier
            .background(c.canvasBase)
            .padding(s.l),
        verticalArrangement = Arrangement.spacedBy(s.m),
    ) {
        StudioText("Vector Studio", style = StudioTheme.type.display, color = c.inkStrong)
        StudioSectionMarker(label = "On the bench")

        // Signature artboard cradle, filled + empty.
        Row(horizontalArrangement = Arrangement.spacedBy(s.m), modifier = Modifier.fillMaxWidth()) {
            ArtboardCradle(
                modifier = Modifier.size(120.dp),
                dimensionLabel = "108 × 108",
            ) {
                Icon(Icons.Filled.Draw, null, tint = c.accentSignature, modifier = Modifier.size(32.dp))
            }
            ArtboardCradle(
                modifier = Modifier.size(120.dp),
                dimensionLabel = "—",
            ) {
                Icon(Icons.Filled.Draw, null, tint = c.inkFaint, modifier = Modifier.size(28.dp))
            }
        }

        StudioSectionMarker(label = "Tools")
        Row(horizontalArrangement = Arrangement.spacedBy(s.s)) {
            StudioToolChip("Pen", Icons.Filled.Create, selected = true, onClick = {})
            StudioToolChip("Shape", Icons.Filled.Draw, selected = false, onClick = {})
            StudioToolChip("Locked", Icons.Filled.Draw, selected = false, enabled = false, onClick = {})
        }

        StudioField {
            StudioText("path_07 · 24 nodes", style = StudioTheme.type.monoReadout, color = c.inkDefault)
        }

        StudioPrimaryAction("New icon", Icons.Filled.Draw, onClick = {})
    }
}

@Preview(name = "Studio · Dark", showBackground = true, widthDp = 360)
@Composable
private fun PreviewStudioDark() {
    StudioTheme(dark = true) { StudioGallery() }
}

@Preview(name = "Studio · Light", showBackground = true, widthDp = 360)
@Composable
private fun PreviewStudioLight() {
    StudioTheme(dark = false) { StudioGallery() }
}
