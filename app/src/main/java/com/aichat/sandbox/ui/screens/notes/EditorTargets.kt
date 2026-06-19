package com.aichat.sandbox.ui.screens.notes

/** Target for the inline text editor overlay. */
sealed interface TextEditTarget {
    val initialBody: String
    val worldX: Float
    val worldY: Float
    val fontSize: Float
    val alignment: Byte

    data class NewAt(
        override val worldX: Float,
        override val worldY: Float,
        override val fontSize: Float,
        override val alignment: Byte,
    ) : TextEditTarget {
        override val initialBody: String = ""
    }

    data class Existing(
        val itemId: String,
        override val initialBody: String,
        override val worldX: Float,
        override val worldY: Float,
        override val fontSize: Float,
        override val alignment: Byte,
    ) : TextEditTarget
}

/** Target for the inline sticky-note editor overlay. */
data class StickyEditTarget(
    val itemId: String,
    val initialBody: String,
    val worldX: Float,
    val worldY: Float,
    val fontSize: Float,
    val maxWidthWorld: Float,
)

/** Top-app-bar OCR progress states. */
enum class OcrIndicator {
    Idle,
    Downloading,
    Running,
}
