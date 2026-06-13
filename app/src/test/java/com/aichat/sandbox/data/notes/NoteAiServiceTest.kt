package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.ToolDefinition
import com.aichat.sandbox.data.model.Usage
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * JVM coverage of the four behaviours called out in
 * `docs/STYLUS_NOTES_PHASE_2.md` sub-phase 2.5: vision happy-path, OCR
 * fallback prompt construction, error pass-through, and cancellation
 * propagation.
 */
class NoteAiServiceTest {

    @Test
    fun visionStreamPassesThroughChunksUnchanged() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("Hello "),
                StreamEvent.Delta("world"),
                StreamEvent.Complete(Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15)),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("should-not-be-called"),
            imageRenderer = FakeImageRenderer(ByteArray(8) { 1 }),
        )

        val chunks = service.ask(visionRequest(strokes = listOf(strokeItem()))).toList()

        assertEquals(3, chunks.size)
        assertEquals(AiChunk.Delta("Hello "), chunks[0])
        assertEquals(AiChunk.Delta("world"), chunks[1])
        val complete = chunks[2] as AiChunk.Complete
        assertEquals(15, complete.usage?.totalTokens)

        val sent = streamer.lastMessages
        assertEquals(1, sent.size)
        val sentMsg = sent.single()
        assertEquals("user", sentMsg.role)
        assertEquals("multimodal", sentMsg.contentType)
        assertEquals(VISION_PROMPT, sentMsg.content)
        assertNotNull(sentMsg.metadata)
        assertTrue(
            "image metadata should embed a base64 PNG data URI",
            sentMsg.metadata!!.contains("data:image/png;base64,")
        )
    }

    @Test
    fun nonVisionStreamIncludesOcrTextInPrompt() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("ack"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer("hello world"),
            // Image renderer must not be touched on the OCR branch.
            imageRenderer = ExplodingImageRenderer,
        )

        val chunks = service.ask(
            nonVisionRequest(
                strokes = listOf(strokeItem()),
                cachedOcrText = null,
            )
        ).toList()

        assertEquals(2, chunks.size)
        assertEquals(AiChunk.Delta("ack"), chunks[0])
        assertTrue(chunks[1] is AiChunk.Complete)

        val body = streamer.lastMessages.single().content
        assertTrue(
            "OCR text should be inlined in the prompt: $body",
            body.contains("hello world")
        )
        assertTrue(
            "User prompt should be preserved verbatim: $body",
            body.contains(NON_VISION_PROMPT)
        )
        assertEquals(
            "Non-vision branch must send a plain text message, not multimodal.",
            "text",
            streamer.lastMessages.single().contentType,
        )
    }

    @Test
    fun nonVisionStreamUsesCachedOcrTextWhenAvailable() = runTest {
        val streamer = RecordingChatStreamer(flow = flowOf(StreamEvent.Complete(null)))
        val recognizer = FakeRecognizer("should-not-be-called")
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = recognizer,
            imageRenderer = ExplodingImageRenderer,
        )

        service.ask(
            nonVisionRequest(
                strokes = listOf(strokeItem()),
                cachedOcrText = "cached transcription",
            )
        ).toList()

        assertEquals(
            "Recognizer must not run when Note.ocrText is already populated.",
            0,
            recognizer.calls,
        )
        assertTrue(streamer.lastMessages.single().content.contains("cached transcription"))
    }

    @Test
    fun errorFromUpstreamSurfacesAsAiChunkError() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(StreamEvent.Error("boom")),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val chunks = service.ask(visionRequest(strokes = listOf(strokeItem()))).toList()

        assertEquals(1, chunks.size)
        assertEquals(AiChunk.Error("boom"), chunks[0])
    }

    @Test
    fun cancellationPropagatesToUpstreamFlow() = runTest {
        val onCompletion = CompletableDeferred<Throwable?>()
        val firstChunk = CompletableDeferred<AiChunk>()
        // Flow that suspends until cancelled after the first delta — its
        // `onCompletion` is the signal we assert on.
        val neverEnding: Flow<StreamEvent> = flow<StreamEvent> {
            emit(StreamEvent.Delta("partial"))
            awaitCancellation()
        }.onCompletion { cause -> onCompletion.complete(cause) }

        val service = NoteAiService(
            chatStreamer = RecordingChatStreamer(flow = neverEnding),
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val job = launch {
            service.ask(visionRequest(strokes = listOf(strokeItem())))
                .collect { chunk ->
                    if (!firstChunk.isCompleted) firstChunk.complete(chunk)
                }
        }

        val first = firstChunk.await()
        assertTrue("expected a delta first; got $first", first is AiChunk.Delta)

        job.cancelAndJoin()

        val cause = onCompletion.await()
        assertNotNull("upstream flow should observe a cancellation cause", cause)
    }

    @Test
    fun editModeEmitsEditPreviewWithParsedOps() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n"),
                StreamEvent.Delta("{ \"schema\": 1, \"summary\": \"deleted one\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"delete\", \"ids\": [\"s_001\"] } ] }"),
                StreamEvent.Delta("\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val item = strokeItem()
        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(item),
                selection = null,
                userPrompt = "Delete it",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                layers = emptyList(),
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertEquals("deleted one", preview.doc.summary)
        assertEquals(1, preview.doc.ops.size)
        // idMap exposes the on-disk uuid behind the short id the model used.
        assertEquals(item.id, preview.idMap["s_001"])
        // The wire body includes the vector JSON inline as a fenced block.
        val sent = streamer.lastMessages.single().content
        assertTrue("EDIT body should include the vector JSON", sent.contains("\"items\""))
        assertTrue("EDIT body should reference s_001", sent.contains("s_001"))
    }

    @Test
    fun editModeMalformedReplyEmitsError() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("not even close to JSON"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(4)),
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(strokeItem()),
                selection = null,
                userPrompt = "Do something",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
            )
        ).toList()

        // No EditPreview was emitted; an Error chunk was produced instead.
        assertTrue(chunks.none { it is AiChunk.EditPreview })
        assertTrue(chunks.any { it is AiChunk.Error })
    }

    @Test
    fun generateModeEmitsEditPreviewWithAddOpsAndStyleRefsInSystem() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"gear\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": true, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = ExplodingImageRenderer, // generation must not rasterize
        )

        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = emptyList(), // blank artboard
                selection = null,
                userPrompt = "a settings gear",
                modelId = "gpt-4o",
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                styleReferences = listOf("{\"schema\":1,\"items\":[]}"),
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertEquals("gear", preview.doc.summary)
        assertTrue(preview.doc.ops.single() is EditOp.AddPath)
        // No existing-item id space for a from-scratch generation.
        assertTrue(preview.idMap.isEmpty())
        // The style reference rides in the system message, not the user turn.
        val sys = streamer.lastChat!!.systemMessage
        assertTrue("system message should embed the reference", sys.contains("Reference 1:"))
        assertTrue(sys.contains("{\"schema\":1,\"items\":[]}"))
        // The user turn names the artboard, plain text (no image).
        val sent = streamer.lastMessages.single()
        assertEquals("text", sent.contentType)
        assertTrue(sent.content.contains("a settings gear"))
    }

    @Test
    fun refineModeRastersSketchAndUsesRefineSystemMessage() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"clean\", "),
                StreamEvent.Delta("\"ops\": [ { \"op\": \"add_path\", \"closed\": false, "),
                StreamEvent.Delta("\"anchors\": [ [0,0], [10,10] ] } ] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = FakeImageRenderer(ByteArray(8) { 7 }),
        )
        val sketch = strokeItem()
        val chunks = service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(sketch),
                selection = listOf(sketch),
                userPrompt = "make the corners sharper",
                modelId = "gpt-4o", // vision-capable
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                refine = true,
            )
        ).toList()

        val preview = chunks.filterIsInstance<AiChunk.EditPreview>().single()
        assertTrue(preview.doc.ops.single() is EditOp.AddPath)
        assertEquals(EditOpsParser.ICON_REFINE_SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        val sent = streamer.lastMessages.single()
        // Vision refine: the sketch raster rides as a multimodal image.
        assertEquals("multimodal", sent.contentType)
        assertTrue(sent.metadata!!.contains("data:image/png;base64,"))
        assertTrue(sent.content.contains("make the corners sharper"))
    }

    @Test
    fun refineFallsBackToTextWhenModelHasNoVision() = runTest {
        val streamer = RecordingChatStreamer(
            flow = flowOf(
                StreamEvent.Delta("```edit-ops\n{ \"schema\": 1, \"summary\": \"\", \"ops\": [] }\n```"),
                StreamEvent.Complete(null),
            ),
        )
        val service = NoteAiService(
            chatStreamer = streamer,
            ocr = FakeRecognizer(""),
            imageRenderer = ExplodingImageRenderer, // must not rasterize without vision
        )
        val sketch = strokeItem()
        service.ask(
            AskRequest(
                note = sampleNote(),
                allItems = listOf(sketch),
                selection = listOf(sketch),
                userPrompt = "",
                modelId = "gpt-3.5-turbo", // no vision
                baseUrl = "https://example.invalid/v1/",
                apiKey = "test-key",
                mode = AskMode.EDIT,
                isIcon = true,
                generate = true,
                refine = true,
            )
        ).toList()

        val sent = streamer.lastMessages.single()
        assertEquals("text", sent.contentType)
        assertEquals(EditOpsParser.ICON_REFINE_SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
    }

    // ---- helpers ----

    private fun visionRequest(strokes: List<NoteItem>): AskRequest = AskRequest(
        note = sampleNote(),
        allItems = strokes,
        selection = null,
        userPrompt = VISION_PROMPT,
        // 4o is in the registry as supportsVision=true.
        modelId = "gpt-4o",
        baseUrl = "https://example.invalid/v1/",
        apiKey = "test-key",
    )

    private fun nonVisionRequest(
        strokes: List<NoteItem>,
        cachedOcrText: String?,
    ): AskRequest = AskRequest(
        note = sampleNote().copy(ocrText = cachedOcrText),
        allItems = strokes,
        selection = null,
        userPrompt = NON_VISION_PROMPT,
        // gpt-3.5-turbo is in the registry as supportsVision=false.
        modelId = "gpt-3.5-turbo",
        baseUrl = "https://example.invalid/v1/",
        apiKey = "test-key",
    )

    private fun sampleNote() = Note(
        id = UUID.randomUUID().toString(),
        title = "test",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 100f, maxY = 100f,
        thumbnailPath = null,
        ocrText = null,
    )

    private fun strokeItem(): NoteItem = NoteItem(
        noteId = "n",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2f,
        payload = StrokeCodec.encode(floatArrayOf(10f, 10f, 1f, 0f, 20f, 20f, 1f, 0f)),
    )

    companion object {
        private const val VISION_PROMPT = "What does this note say?"
        private const val NON_VISION_PROMPT = "Summarize this."
    }

    // ---- fakes ----

    private class RecordingChatStreamer(
        private val flow: Flow<StreamEvent>,
    ) : ChatStreamer {
        var lastChat: Chat? = null
            private set
        var lastMessages: List<Message> = emptyList()
            private set

        override fun sendMessageStream(
            baseUrl: String,
            apiKey: String,
            chat: Chat,
            messages: List<Message>,
            onRetryAttempt: ((Int) -> Unit)?,
            tools: List<ToolDefinition>?,
            extraImageOnLastUserTurn: ByteArray?,
            extraSystemSuffix: String?,
        ): Flow<StreamEvent> {
            lastChat = chat
            lastMessages = messages
            return flow
        }
    }

    private class FakeRecognizer(private val text: String) : HandwritingRecognizer {
        var calls: Int = 0
            private set

        override suspend fun recognize(strokes: List<NoteItem>, locale: String): OcrResult {
            calls++
            return OcrResult(text = text, confidence = if (text.isEmpty()) 0f else 1f, perWord = emptyList())
        }
    }

    private class FakeImageRenderer(private val bytes: ByteArray) : NoteImageRenderer {
        override fun renderToPng(
            items: List<NoteItem>,
            backgroundStyle: String,
            maxEdgePx: Int,
        ): ByteArray = bytes
    }

    private object ExplodingImageRenderer : NoteImageRenderer {
        override fun renderToPng(
            items: List<NoteItem>,
            backgroundStyle: String,
            maxEdgePx: Int,
        ): ByteArray = error("image renderer must not be called on the non-vision branch")
    }
}
