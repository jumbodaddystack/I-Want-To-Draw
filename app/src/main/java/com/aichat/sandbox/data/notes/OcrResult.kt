package com.aichat.sandbox.data.notes

/**
 * Output of [HandwritingOcr.recognize]. The `text` is the recognizer's best
 * candidate; [perWord] surfaces lower-scoring alternates so a future UI can
 * offer "did you mean…" suggestions without re-running the model.
 *
 * On any failure — model download, recognition crash, empty input — callers
 * receive [EMPTY] rather than an exception. See `HandwritingOcr.recognize`.
 */
data class OcrResult(
    val text: String,
    val confidence: Float,
    val perWord: List<WordCandidate>,
) {
    companion object {
        val EMPTY: OcrResult = OcrResult(text = "", confidence = 0f, perWord = emptyList())
    }
}

data class WordCandidate(
    val text: String,
    val score: Float?,
)
