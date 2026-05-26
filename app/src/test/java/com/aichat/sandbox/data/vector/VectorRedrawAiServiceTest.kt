package com.aichat.sandbox.data.vector

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.ToolDefinition
import com.aichat.sandbox.data.model.Usage
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [VectorRedrawAiService] end-to-end with a fake [ChatStreamer]: it builds
 * the summary, sends the redraw prompt, parses the reply into a scene, compiles
 * the scene into a candidate, and surfaces friendly errors on stream failure /
 * malformed replies — never letting the model emit XML or mutate state directly.
 */
class VectorRedrawAiServiceTest {

    private val xml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
            <path android:name="frame"
                android:pathData="M16,16 L92,16 L92,92 L16,92 Z"
                android:strokeColor="#2D2D2D" android:strokeWidth="2" />
        </vector>
    """.trimIndent()

    private fun request(): VectorRedrawAiRequest {
        val document = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(document, xml)
        return VectorRedrawAiRequest(
            xml = xml,
            document = document,
            metrics = metrics,
            userPrompt = "Redraw as a clean icon.",
            modelId = "gpt-4o",
            baseUrl = "https://example.invalid/v1/",
            apiKey = "test-key",
        )
    }

    private fun service(vararg events: StreamEvent): Pair<VectorRedrawAiService, RecordingChatStreamer> {
        val streamer = RecordingChatStreamer(flowOf(*events))
        return VectorRedrawAiService(streamer) to streamer
    }

    private val sceneReply = """
        ```vector-scene
        {
          "schema": 1,
          "viewport": { "viewportWidth": 108, "viewportHeight": 108 },
          "styleIntent": "clean icon",
          "objects": [
            { "id": "ring", "type": "ellipse", "cx": 54, "cy": 54, "rx": 30, "ry": 30,
              "stroke": "#109F5C", "fill": "#00000000", "strokeWidth": 2 }
          ]
        }
        ```
    """.trimIndent()

    @Test
    fun redrawBuildsSummaryAndParsesScene() = runTest {
        val (svc, streamer) = service(
            StreamEvent.Delta(sceneReply),
            StreamEvent.Complete(Usage(0, 0, 0)),
        )

        val complete = svc.redraw(request()).toList()
            .filterIsInstance<VectorRedrawAiChunk.Complete>().single()
        assertEquals(1, complete.result.scene.objects.size)
        assertEquals("clean icon", complete.result.scene.styleIntent)
        assertTrue(complete.result.summaryJson.contains("\"format\":\"android_vector_drawable\""))

        // The model received the redraw system message + summary, not XML.
        assertEquals(VectorRedrawPrompts.SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        val body = streamer.lastMessages.single().content
        assertTrue(body.contains("Vector summary JSON:"))
        assertTrue(body.contains("Redraw as a clean icon."))
        assertFalse(body.contains("<vector"))
    }

    @Test
    fun redrawCompilesSceneIntoCandidate() = runTest {
        val (svc, _) = service(
            StreamEvent.Delta(sceneReply),
            StreamEvent.Complete(null),
        )

        val complete = svc.redraw(request()).toList()
            .filterIsInstance<VectorRedrawAiChunk.Complete>().single()
        val compile = complete.result.compileResult
        assertEquals(1, compile.metrics.pathCount)
        assertTrue("candidate XML should carry the scene color", compile.xml.contains("#109F5C"))
        // Compiled XML must parse again as a VectorDrawable.
        val reparsed = AndroidVectorDrawableParser.parse(compile.xml)
        assertFalse(reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML })
    }

    @Test
    fun redrawEmitsErrorOnStreamError() = runTest {
        val (svc, _) = service(StreamEvent.Error("network down"))
        val chunks = svc.redraw(request()).toList()
        val error = chunks.filterIsInstance<VectorRedrawAiChunk.Error>().single()
        assertEquals(VectorRedrawAiService.STREAM_FAILED_MESSAGE, error.message)
        assertTrue(chunks.none { it is VectorRedrawAiChunk.Complete })
    }

    @Test
    fun redrawEmitsErrorOnMalformedSceneReply() = runTest {
        val (svc, _) = service(
            StreamEvent.Delta("I cannot do that, here is some prose with no JSON."),
            StreamEvent.Complete(null),
        )
        val chunks = svc.redraw(request()).toList()
        val error = chunks.filterIsInstance<VectorRedrawAiChunk.Error>().single()
        assertEquals(VectorRedrawAiService.PARSE_FAILED_MESSAGE, error.message)
    }

    @Test
    fun redrawHandlesEmptyScene() = runTest {
        val (svc, _) = service(
            StreamEvent.Delta(
                "```vector-scene\n" +
                    "{ \"schema\": 1, \"styleIntent\": \"unclear\", \"objects\": [] }\n```",
            ),
            StreamEvent.Complete(null),
        )
        val complete = svc.redraw(request()).toList()
            .filterIsInstance<VectorRedrawAiChunk.Complete>().single()
        assertTrue(complete.result.scene.objects.isEmpty())
        assertEquals(0, complete.result.compileResult.metrics.pathCount)
        assertTrue(complete.result.compileResult.warnings.any {
            it.code == VectorWarning.Codes.SCENE_EMPTY
        })
    }

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
