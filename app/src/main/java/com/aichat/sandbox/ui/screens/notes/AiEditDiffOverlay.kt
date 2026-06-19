package com.aichat.sandbox.ui.screens.notes

import androidx.compose.runtime.Composable
import com.aichat.sandbox.ui.components.notes.ViewportController
import java.io.File

internal const val AI_DIFF_ADDED_ARGB: ULong = 0xFF2E7D32u
internal const val AI_DIFF_REMOVED_ARGB: ULong = 0xFFC62828u
internal const val AI_DIFF_MODIFIED_ARGB: ULong = 0xFF6A1B9Au

/** Non-interactive canvas overlay hook for staged AI edits. */
@Composable
internal fun AiEditDiffOverlay(
    simulation: EditPreviewController.Simulation,
    viewport: ViewportController?,
    filesDir: File,
) {
    // The preview banner provides the textual diff summary. Keep this overlay as
    // a safe no-op when the rendering surface cannot provide a viewport yet.
    if (viewport == null || simulation.isEmpty || !filesDir.exists()) return
}
