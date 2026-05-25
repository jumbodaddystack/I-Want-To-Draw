package com.aichat.sandbox.data.notes

import android.util.Log
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ImageAttachment
import com.aichat.sandbox.data.model.ImageMetadata
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.ModelCapabilities
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core AI request pipeline for the notes feature (sub-phase 2.5 of
 * `docs/STYLUS_NOTES_PHASE_2.md`). No UI, no canned-prompt routing — just the
 * vision-vs-OCR branch and a [Flow] of [AiChunk]s.
 *
 * Vision branch: rasterize the selection (or whole note) at `MAX_EDGE_PX`,
 * base64-encode the PNG, and hand it to [ChatStreamer] as a synthetic
 * multimodal user message. The image is the user's input; the
 * `userPrompt` is the user's question about it.
 *
 * Non-vision branch: run [HandwritingOcr] over the relevant strokes (lazy —
 * the selection path always re-runs; the whole-note path prefers
 * `Note.ocrText` if it's already populated) and send a plain text prompt.
 *
 * Each call is one-shot — the side sheet packs prior turns separately
 * (intentional; see 2.6 risks). Cancellation propagates naturally because
 * the downstream stream is a cold [Flow].
 */
@Singleton
class NoteAiService @Inject constructor(
    private val chatStreamer: ChatStreamer,
    private val ocr: HandwritingRecognizer,
    private val imageRenderer: NoteImageRenderer = NoteRasterizerImageRenderer,
) {

    fun ask(request: AskRequest): Flow<AiChunk> = flow {
        val caps = ModelCapabilities.of(request.modelId)
        if (request.mode == AskMode.EDIT) {
            collectEdit(request, caps.supportsVision)
            return@flow
        }
        val upstream = if (caps.supportsVision) {
            buildVisionStream(request)
        } else {
            buildOcrStream(request)
        }
        upstream.collect { event ->
            emit(mapEvent(event))
        }
    }

    /**
     * Sub-phase 7.3 — EDIT-mode dispatcher. Buffers the streamed reply, parses
     * it through [EditOpsParser] on completion, and emits a single
     * [AiChunk.EditPreview] terminal event (plus any deltas the side sheet
     * wants to render as "AI thinking…").
     */
    private suspend fun FlowCollector<AiChunk>.collectEdit(
        request: AskRequest,
        supportsVision: Boolean,
    ) {
        val serialized = VectorCanvasJson.serialize(
            items = request.selection ?: request.allItems,
            bounds = null,
            layers = request.layers,
        )
        val upstream = if (supportsVision) {
            buildEditVisionStream(request, serialized.json)
        } else {
            buildEditOcrStream(request, serialized.json)
        }
        val buffer = StringBuilder()
        var lastUsage: com.aichat.sandbox.data.model.Usage? = null
        var errored = false
        upstream.collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    buffer.append(event.content)
                    // No `Delta` re-emit — EDIT replies are JSON, not prose;
                    // the side sheet renders a fixed "Thinking…" indicator.
                }
                is StreamEvent.Complete -> { lastUsage = event.usage }
                is StreamEvent.Error -> {
                    errored = true
                    emit(AiChunk.Error(event.message))
                }
                is StreamEvent.ToolCallDelta -> { /* impossible in this flow */ }
            }
        }
        if (errored) return
        val parseResult = EditOpsParser.parse(
            raw = buffer.toString(),
            knownIds = serialized.idMap.keys,
            knownLayers = serialized.layerMap.keys,
        )
        parseResult.fold(
            onSuccess = { doc ->
                emit(AiChunk.EditPreview(
                    doc = doc,
                    idMap = serialized.idMap,
                    layerMap = serialized.layerMap,
                    usage = lastUsage,
                ))
            },
            onFailure = { t ->
                Log.w(TAG, "edit-ops parse failed: ${t.message}")
                emit(AiChunk.Error(PARSE_FAILED_MESSAGE))
            },
        )
    }

    private suspend fun buildVisionStream(request: AskRequest): Flow<StreamEvent> {
        val items = request.selection ?: request.allItems
        if (items.isEmpty()) {
            return errorFlow(EMPTY_NOTE_MESSAGE)
        }
        // Bitmap rasterization is CPU-bound; keep it off whatever dispatcher
        // the caller is on (typically `viewModelScope` → Main) so the editor
        // stays responsive while a 1536px PNG is being produced.
        val userMessage = withContext(Dispatchers.Default) {
            val pngBytes = try {
                imageRenderer.renderToPng(
                    items = items,
                    backgroundStyle = request.note.backgroundStyle,
                    maxEdgePx = MAX_EDGE_PX,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to rasterize note ${request.note.id}", t)
                null
            } ?: return@withContext null

            val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
            val metadata = gson.toJson(
                ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
            )
            Message(
                chatId = SYNTHETIC_CHAT_ID,
                role = MessageRole.USER.value,
                content = request.userPrompt,
                contentType = "multimodal",
                metadata = metadata,
            )
        } ?: return errorFlow(RENDER_FAILED_MESSAGE)

        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticChat(request),
            messages = listOf(userMessage),
        )
    }

    private suspend fun buildOcrStream(request: AskRequest): Flow<StreamEvent> {
        val transcribed = resolveOcrText(request)
        val body = buildString {
            if (transcribed.isNotBlank()) {
                append("Transcribed note (may have OCR errors):\n")
                append(transcribed)
                append("\n\n")
            } else {
                append("(The note contains no recognizable handwriting; ")
                append("the user is asking about its non-text contents.)\n\n")
            }
            append("User question:\n")
            append(request.userPrompt)
        }
        val userMessage = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = body,
            contentType = "text",
            metadata = null,
        )
        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticChat(request),
            messages = listOf(userMessage),
        )
    }

    /**
     * Resolve OCR text for the non-vision branch.
     *
     * - Selection scope: always re-run OCR over the selection. We can't cache
     *   per-selection results, and the cached `Note.ocrText` covers the whole
     *   note (would over-share the context).
     * - Whole-note scope: prefer `Note.ocrText` if present so we don't pay
     *   for a recognizer pass on every ask; fall back to a synchronous run
     *   if the field is empty (e.g. note was saved before 2.4 landed, or a
     *   prior OCR pass came back empty).
     */
    private suspend fun resolveOcrText(request: AskRequest): String {
        val selection = request.selection
        if (selection != null) {
            return ocr.recognize(selection.filter { it.kind == STROKE_KIND }).text
        }
        val cached = request.note.ocrText
        if (!cached.isNullOrBlank()) return cached
        val strokes = request.allItems.filter { it.kind == STROKE_KIND }
        if (strokes.isEmpty()) return ""
        return ocr.recognize(strokes).text
    }

    /**
     * Sub-phase 7.3 — vision EDIT branch. Sends the rasterised PNG plus the
     * vector JSON inline in the prompt body, using the Phase 7.2 system
     * message instead of the conversational one.
     */
    private suspend fun buildEditVisionStream(
        request: AskRequest,
        vectorJson: String,
    ): Flow<StreamEvent> {
        val items = request.selection ?: request.allItems
        if (items.isEmpty()) return errorFlow(EMPTY_NOTE_MESSAGE)
        val userMessage = withContext(Dispatchers.Default) {
            val pngBytes = try {
                imageRenderer.renderToPng(
                    items = items,
                    backgroundStyle = request.note.backgroundStyle,
                    maxEdgePx = MAX_EDGE_PX,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to rasterize note ${request.note.id} for EDIT", t)
                null
            } ?: return@withContext null
            val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
            val metadata = gson.toJson(
                ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri)))
            )
            Message(
                chatId = SYNTHETIC_CHAT_ID,
                role = MessageRole.USER.value,
                content = buildEditPromptBody(request.userPrompt, vectorJson, ocrText = null),
                contentType = "multimodal",
                metadata = metadata,
            )
        } ?: return errorFlow(RENDER_FAILED_MESSAGE)

        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticEditChat(request),
            messages = listOf(userMessage),
        )
    }

    /**
     * Sub-phase 7.3 — non-vision EDIT branch. OCR text + vector JSON inline,
     * no image attachment.
     */
    private suspend fun buildEditOcrStream(
        request: AskRequest,
        vectorJson: String,
    ): Flow<StreamEvent> {
        val transcribed = resolveOcrText(request)
        val message = Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = buildEditPromptBody(
                userPrompt = request.userPrompt,
                vectorJson = vectorJson,
                ocrText = transcribed.takeIf { it.isNotBlank() },
            ),
            contentType = "text",
            metadata = null,
        )
        return chatStreamer.sendMessageStream(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            chat = syntheticEditChat(request),
            messages = listOf(message),
        )
    }

    /**
     * Build the prompt body for an EDIT request. Exposed `internal` so the
     * Phase 7.3 unit test can pin the exact wire format.
     */
    internal fun buildEditPromptBody(
        userPrompt: String,
        vectorJson: String,
        ocrText: String?,
    ): String = buildString {
        if (!ocrText.isNullOrBlank()) {
            append("Transcribed note (may have OCR errors):\n")
            append(ocrText)
            append("\n\n")
        }
        append(userPrompt)
        append("\n\n")
        append("Here is the vector JSON of the note. Edit by referencing IDs from `items`:\n")
        append("```json\n")
        append(vectorJson)
        append("\n```")
    }

    private fun syntheticChat(request: AskRequest): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Note AI",
        model = request.modelId,
        systemMessage = SYSTEM_INSTRUCTION,
    )

    private fun syntheticEditChat(request: AskRequest): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Note Edit",
        model = request.modelId,
        systemMessage = if (request.isIcon) EditOpsParser.ICON_SYSTEM_MESSAGE
        else EditOpsParser.SYSTEM_MESSAGE,
    )

    private fun mapEvent(event: StreamEvent): AiChunk = when (event) {
        is StreamEvent.Delta -> AiChunk.Delta(event.content)
        is StreamEvent.Complete -> AiChunk.Complete(event.usage)
        is StreamEvent.Error -> AiChunk.Error(event.message)
        // Tool-call deltas can't appear on this path (we never send tools).
        is StreamEvent.ToolCallDelta -> AiChunk.Delta("")
    }

    private fun errorFlow(message: String): Flow<StreamEvent> = flow {
        emit(StreamEvent.Error(message))
    }

    companion object {
        /**
         * Maximum longest-edge in px when rasterizing the note for a vision
         * call. 1536 keeps inline base64 payloads comfortably under typical
         * provider body limits while still leaving enough resolution for OCR
         * on the model side. Bump down to 1024 if self-hosted backends start
         * rejecting requests; see Phase 2.5 risks.
         */
        const val MAX_EDGE_PX: Int = 1536

        internal const val SYSTEM_INSTRUCTION: String =
            "You are helping the user with a handwritten note. Be concise. " +
                "If the user pasted an image of the note, treat the handwriting as their input; " +
                "transcribe relevant parts when answering."

        private const val SYNTHETIC_CHAT_ID: String = "note-ai-synthetic"
        private const val STROKE_KIND: String = "stroke"
        private const val TAG: String = "NoteAiService"
        private const val EMPTY_NOTE_MESSAGE: String = "Note is empty — nothing to send."
        private const val RENDER_FAILED_MESSAGE: String = "Couldn't render the note for the AI request."
        internal const val PARSE_FAILED_MESSAGE: String =
            "Could not parse AI edit response — try rephrasing."

        private val gson = Gson()
    }
}

/**
 * Tiny indirection over [NoteRasterizer] so [NoteAiService] can be unit
 * tested without depending on `android.graphics.Bitmap` (which isn't
 * available on the host JVM). Production code uses
 * [NoteRasterizerImageRenderer] which delegates straight through.
 */
interface NoteImageRenderer {
    fun renderToPng(
        items: List<NoteItem>,
        backgroundStyle: String,
        maxEdgePx: Int,
    ): ByteArray?
}

object NoteRasterizerImageRenderer : NoteImageRenderer {
    override fun renderToPng(
        items: List<NoteItem>,
        backgroundStyle: String,
        maxEdgePx: Int,
    ): ByteArray? {
        val bounds = NoteRasterizer.computeBounds(items) ?: return null
        val bitmap = NoteRasterizer.render(
            items = items,
            bounds = bounds,
            maxEdgePx = maxEdgePx,
            backgroundStyle = backgroundStyle,
        )
        return try {
            NoteRasterizer.toPng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
