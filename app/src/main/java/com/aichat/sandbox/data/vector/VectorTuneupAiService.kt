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
 * Inputs for one model-guided vector tune-up. [document]/[metrics] feed the
 * compact [VectorSummaryJson] sent to the model; [xml] is the source the
 * resulting plan is applied to. [imagePreviewPng] is an optional hook for a
 * future vision-assisted summary — Phase 4 does not require or send it.
 */
data class VectorTuneupAiRequest(
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
        if (other !is VectorTuneupAiRequest) return false
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

/** Streamed output of a tune-up request. */
sealed interface VectorTuneupAiChunk {
    data class Delta(val text: String) : VectorTuneupAiChunk
    data class Complete(val result: VectorTuneupAiResult) : VectorTuneupAiChunk
    data class Error(val message: String) : VectorTuneupAiChunk
}

/** Validated, applied result of a tune-up request. */
data class VectorTuneupAiResult(
    val plan: VectorEditPlan,
    val applyResult: VectorEditPlanApplyResult,
    val summaryJson: String,
)

/**
 * Phase 4 — runs a model-guided vector tune-up over the existing multi-provider
 * [ChatStreamer], the same surface `NoteAiService` uses.
 *
 * The request is one-shot and synthetic — nothing is written to the chat
 * database. The model only ever sees the compact [VectorSummaryJson]; its reply
 * is parsed through [VectorEditPlanParser] and applied through
 * [VectorEditPlanApplier], so it can never directly mutate XML or app state.
 * Stream errors, unparseable replies, and apply failures all surface as a
 * friendly [VectorTuneupAiChunk.Error]; the original XML is never changed.
 */
@Singleton
class VectorTuneupAiService @Inject constructor(
    private val chatStreamer: ChatStreamer,
) {

    fun tuneUp(request: VectorTuneupAiRequest): Flow<VectorTuneupAiChunk> = flow {
        val summary = VectorSummaryJson.summarize(request.document, request.metrics)
        val userMessage = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = VectorTuneupPrompts.buildUserPrompt(request.userPrompt, summary.json),
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
                    emit(VectorTuneupAiChunk.Delta(event.content))
                }
                is StreamEvent.Complete -> Unit
                is StreamEvent.Error -> {
                    errored = true
                    logWarn("tune-up stream error: ${event.message}")
                    emit(VectorTuneupAiChunk.Error(STREAM_FAILED_MESSAGE))
                }
                is StreamEvent.ToolCallDelta -> Unit // impossible: we send no tools.
            }
        }
        if (errored) return@flow

        val knownPathIds = summary.includedPathIds.toSet()
        val knownColors = request.metrics.colorCounts.keys
        val plan = VectorEditPlanParser.parse(buffer.toString(), knownPathIds, knownColors)
            .getOrElse { t ->
                logWarn("tune-up plan parse failed: ${t.message}")
                emit(VectorTuneupAiChunk.Error(PARSE_FAILED_MESSAGE))
                return@flow
            }

        val applyResult = runCatching {
            VectorEditPlanApplier.apply(request.document, request.xml, plan)
        }.getOrElse { t ->
            logWarn("tune-up plan apply failed", t)
            emit(VectorTuneupAiChunk.Error(APPLY_FAILED_MESSAGE))
            return@flow
        }

        emit(
            VectorTuneupAiChunk.Complete(
                VectorTuneupAiResult(
                    plan = plan,
                    applyResult = applyResult,
                    summaryJson = summary.json,
                ),
            ),
        )
    }

    /** Logging must never break the streamed flow (and `Log` is unmocked in JVM tests). */
    private fun logWarn(message: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message) }
    }

    private fun syntheticChat(request: VectorTuneupAiRequest): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Vector Tune-Up",
        model = request.modelId,
        systemMessage = VectorTuneupPrompts.SYSTEM_MESSAGE,
    )

    companion object {
        private const val TAG = "VectorTuneupAiService"
        private const val SYNTHETIC_CHAT_ID = "vector-tuneup-synthetic"

        internal const val STREAM_FAILED_MESSAGE =
            "AI Tune-Up failed, but your original XML was not changed."
        internal const val PARSE_FAILED_MESSAGE =
            "AI Tune-Up could not parse the model's edit plan."
        internal const val APPLY_FAILED_MESSAGE =
            "AI Tune-Up failed, but your original XML was not changed."
    }
}
