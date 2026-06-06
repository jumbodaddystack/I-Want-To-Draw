package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.ToolDefinition
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.data.vector.VectorWarning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (sub-feature 5b) — the semantic AI tracer, end-to-end with a fake
 * [ChatStreamer] + fake [BitmapPngEncoder] (pure JVM, no Robolectric). Proves the
 * happy path (vision model → scene → compiled paths) and that every failure mode
 * falls back to the deterministic [LocalBitmapTracer] with a
 * [VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL] note.
 */
class AiBitmapTracerTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A 16×16 bitmap with a solid black square 3..12 (so the local fallback finds one region). */
    private fun squareBitmap(): IntArray {
        val w = 16; val h = 16
        val px = IntArray(w * h) { white }
        for (y in 3..12) for (x in 3..12) px[y * w + x] = black
        return px
    }

    private val sceneReply = """
        ```vector-scene
        {
          "schema": 1,
          "viewport": { "viewportWidth": 16, "viewportHeight": 16 },
          "styleIntent": "single box",
          "objects": [
            { "id": "box", "type": "rect", "x": 3, "y": 3, "width": 9, "height": 9,
              "fill": "#109F5C" }
          ]
        }
        ```
    """.trimIndent()

    private fun tracer(
        modelId: String = "gpt-4o",
        encoder: BitmapPngEncoder = okEncoder,
        vararg events: StreamEvent,
    ): Pair<AiBitmapTracer, RecordingChatStreamer> {
        val streamer = RecordingChatStreamer(flowOf(*events))
        val t = AiBitmapTracer(
            streamer = streamer,
            config = AiTraceConfig(modelId = modelId, baseUrl = "https://example.invalid/v1/", apiKey = "k"),
            pngEncoder = encoder,
        )
        return t to streamer
    }

    @Test
    fun visionModel_compilesSceneIntoPaths() = runTest {
        val (t, streamer) = tracer(
            events = arrayOf(StreamEvent.Delta(sceneReply), StreamEvent.Complete(null)),
        )
        val result = t.trace(squareBitmap(), 16, 16, TraceOptions())

        assertEquals(1, result.paths.size)
        // The compiler round-trips through the VD writer/parser, which preserves
        // the object id as the path `name` (and reissues `id`).
        assertEquals("box", result.paths.single().name)
        assertEquals("#109F5C", result.paths.single().style.fillColor)
        assertEquals(16f, result.viewport.viewportWidth, 0f)
        // No fallback happened.
        assertTrue(result.warnings.none { it.code == VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL })

        // The model got an image (multimodal) + the trace system message, never XML.
        assertEquals(AiTracePrompts.SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        val message = streamer.lastMessages.single()
        assertEquals("multimodal", message.contentType)
        assertTrue(message.metadata!!.contains("data:image/png;base64,"))
        assertFalse(message.content.contains("<vector"))
    }

    @Test
    fun nonVisionModel_fallsBackToLocalWithoutCallingTheModel() = runTest {
        val (t, streamer) = tracer(modelId = "text-only-model")
        val result = t.trace(squareBitmap(), 16, 16, TraceOptions())

        // Local tracer found the square; the model was never contacted.
        assertTrue(result.paths.isNotEmpty())
        assertNull(streamer.lastChat)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL })
    }

    @Test
    fun streamError_fallsBackToLocal() = runTest {
        val (t, _) = tracer(events = arrayOf(StreamEvent.Error("network down")))
        val result = t.trace(squareBitmap(), 16, 16, TraceOptions())

        assertTrue(result.paths.isNotEmpty())
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL })
    }

    @Test
    fun unparseableReply_fallsBackToLocal() = runTest {
        val (t, _) = tracer(
            events = arrayOf(StreamEvent.Delta("Sorry, I can't, here is prose."), StreamEvent.Complete(null)),
        )
        val result = t.trace(squareBitmap(), 16, 16, TraceOptions())

        assertTrue(result.paths.isNotEmpty())
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL })
    }

    @Test
    fun encoderFailure_fallsBackToLocalWithoutCallingTheModel() = runTest {
        val (t, streamer) = tracer(
            encoder = BitmapPngEncoder { _, _, _ -> null },
            events = arrayOf(StreamEvent.Delta(sceneReply), StreamEvent.Complete(null)),
        )
        val result = t.trace(squareBitmap(), 16, 16, TraceOptions())

        assertNull(streamer.lastChat)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL })
    }

    @Test
    fun emptyScene_fallsBackToLocal() = runTest {
        val (t, _) = tracer(
            events = arrayOf(
                StreamEvent.Delta(
                    "```vector-scene\n{ \"schema\": 1, \"viewport\": " +
                        "{ \"viewportWidth\": 16, \"viewportHeight\": 16 }, \"objects\": [] }\n```",
                ),
                StreamEvent.Complete(null),
            ),
        )
        val result = t.trace(squareBitmap(), 16, 16, TraceOptions())

        // The compiled scene had no drawable objects, so we fell back to local.
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL })
        assertTrue(result.paths.isNotEmpty())
    }

    private val okEncoder = BitmapPngEncoder { _, _, _ -> byteArrayOf(1, 2, 3, 4) }

    private class RecordingChatStreamer(private val flow: Flow<StreamEvent>) : ChatStreamer {
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
}
