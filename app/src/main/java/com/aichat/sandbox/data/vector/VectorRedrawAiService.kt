package com.aichat.sandbox.data.vector

import android.util.Log
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inputs for one semantic redraw. [document]/[metrics] feed the compact
 * [VectorSummaryJson] sent to the model; [xml] is the unchanged source the
 * redraw is derived from (kept so callers can show "your original was not
 * changed"). [imagePreviewPng] is an optional hook for a future vision-assisted
 * redraw — Phase 5 does not require or send it.
 */
data class VectorRedrawAiRequest(
    val xml: String,
    val document: VectorDocument,
    val metrics: VectorMetrics,
    val userPrompt: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String,
    val imagePreviewPng: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorRedrawAiRequest) return false
        return xml == other.xml &&
            document == other.document &&
            metrics == other.metrics &&
            userPrompt == other.userPrompt &&
            modelId == other.modelId &&
            baseUrl == other.baseUrl &&
            apiKey == other.apiKey &&
            imagePreviewPng.contentEqualsOrBothNull(other.imagePreviewPng)
    }

    override fun hashCode(): Int {
        var result = xml.hashCode()
        result = 31 * result + document.hashCode()
        result = 31 * result + metrics.hashCode()
        result = 31 * result + userPrompt.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + baseUrl.hashCode()
        result = 31 * result + apiKey.hashCode()
        result = 31 * result + (imagePreviewPng?.contentHashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
        if (this == null) other == null else this.contentEquals(other)
}

/** Streamed output of a redraw request. */
sealed interface VectorRedrawAiChunk {
    data class Delta(val text: String) : VectorRedrawAiChunk
    data class Complete(val result: VectorRedrawAiResult) : VectorRedrawAiChunk
    data class Error(val message: String) : VectorRedrawAiChunk
}

/** Validated, compiled result of a redraw request. */
data class VectorRedrawAiResult(
    val scene: VectorScene,
    val compileResult: VectorSceneCompileResult,
    val summaryJson: String,
)

/**
 * Phase 5 — runs a semantic redraw over the existing multi-provider
 * [ChatStreamer], the same surface [VectorTuneupAiService] uses.
 *
 * The request is one-shot and synthetic — nothing is written to the chat
 * database and no app state is mutated. The model only ever sees the compact
 * [VectorSummaryJson]; its reply is parsed through [VectorSceneParser] and
 * compiled through [VectorSceneCompiler], so it can never directly emit XML or
 * touch app state. Stream errors, unparseable replies, and compile failures all
 * surface as a friendly [VectorRedrawAiChunk.Error]; the original XML is never
 * changed.
 */
@Singleton
class VectorRedrawAiService @Inject constructor(
    private val chatStreamer: ChatStreamer,
) {

    fun redraw(request: VectorRedrawAiRequest): Flow<VectorRedrawAiChunk> = flow {
        val summary = VectorSummaryJson.summarize(request.document, request.metrics)
        val userMessage = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = VectorRedrawPrompts.buildUserPrompt(request.userPrompt, summary.json),
            contentType = "text",
            metadata = null,
        )

        val buffer = StringBuilder()
        var errored = false
        chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticChat(request),
            messages = listOf(userMessage),
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    buffer.append(event.content)
                    emit(VectorRedrawAiChunk.Delta(event.content))
                }
                is StreamEvent.Complete -> Unit
                is StreamEvent.Error -> {
                    errored = true
                    logWarn("redraw stream error: ${event.message}")
                    emit(VectorRedrawAiChunk.Error(STREAM_FAILED_MESSAGE))
                }
                is StreamEvent.ToolCallDelta -> Unit // impossible: we send no tools.
            }
        }
        if (errored) return@flow

        val scene = VectorSceneParser.parse(buffer.toString(), request.document.viewport)
            .getOrElse { t ->
                logWarn("redraw scene parse failed: ${t.message}")
                emit(VectorRedrawAiChunk.Error(PARSE_FAILED_MESSAGE))
                return@flow
            }

        val compileResult = runCatching {
            VectorSceneCompiler.compile(scene)
        }.getOrElse { t ->
            logWarn("redraw scene compile failed", t)
            emit(VectorRedrawAiChunk.Error(COMPILE_FAILED_MESSAGE))
            return@flow
        }

        emit(
            VectorRedrawAiChunk.Complete(
                VectorRedrawAiResult(
                    scene = scene,
                    compileResult = compileResult,
                    summaryJson = summary.json,
                ),
            ),
        )
    }

    /** Logging must never break the streamed flow (and `Log` is unmocked in JVM tests). */
    private fun logWarn(message: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message) }
    }

    private fun syntheticChat(request: VectorRedrawAiRequest): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Vector Redraw",
        model = request.modelId,
        systemMessage = VectorRedrawPrompts.SYSTEM_MESSAGE,
    )

    companion object {
        private const val TAG = "VectorRedrawAiService"
        private const val SYNTHETIC_CHAT_ID = "vector-redraw-synthetic"

        internal const val STREAM_FAILED_MESSAGE =
            "AI Redraw failed, but your original XML was not changed."
        internal const val PARSE_FAILED_MESSAGE =
            "AI Redraw could not parse the model's scene."
        internal const val COMPILE_FAILED_MESSAGE =
            "AI Redraw could not compile the proposed scene."
    }
}
