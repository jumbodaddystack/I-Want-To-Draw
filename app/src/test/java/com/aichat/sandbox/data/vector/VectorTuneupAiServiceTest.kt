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
 * Covers [VectorTuneupAiService] end-to-end with a fake [ChatStreamer]: it
 * builds the summary, sends the constrained prompt, parses the reply into a
 * plan, applies it into a candidate, and surfaces friendly errors on stream
 * failure / malformed replies — never letting the model mutate state directly.
 */
class VectorTuneupAiServiceTest {

    private val xml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
            <path android:name="frame"
                android:pathData="M16,16 L92,16 L92,92 L16,92 Z"
                android:strokeColor="#2D2D2D" android:strokeWidth="2" />
            <path android:name="stem"
                android:pathData="M0,0 L1,1 L2,2 L3,3 L4,4 L5,5"
                android:strokeColor="#109F5C" android:strokeWidth="2" />
        </vector>
    """.trimIndent()

    private fun request(): VectorTuneupAiRequest {
        val document = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(document, xml)
        return VectorTuneupAiRequest(
            xml = xml,
            document = document,
            metrics = metrics,
            userPrompt = "Clean up the green stroke.",
            modelId = "gpt-4o",
            baseUrl = "https://example.invalid/v1/",
            apiKey = "test-key",
        )
    }

    private fun service(vararg events: StreamEvent): Pair<VectorTuneupAiService, RecordingChatStreamer> {
        val streamer = RecordingChatStreamer(flowOf(*events))
        return VectorTuneupAiService(streamer) to streamer
    }

    @Test
    fun tuneUpBuildsSummaryAndParsesPlan() = runTest {
        val (svc, streamer) = service(
            StreamEvent.Delta("```vector-edit-plan\n"),
            StreamEvent.Delta("{ \"schema\": 1, \"mode\": \"tune_up\", \"summary\": \"simplified\", "),
            StreamEvent.Delta("\"operations\": [ { \"op\": \"simplify_paths\", " +
                "\"target\": { \"pathIds\": [\"p_002\"] }, \"tolerance\": 0.5 } ] }"),
            StreamEvent.Delta("\n```"),
            StreamEvent.Complete(Usage(0, 0, 0)),
        )

        val complete = svc.tuneUp(request()).toList()
            .filterIsInstance<VectorTuneupAiChunk.Complete>().single()
        assertEquals(1, complete.result.plan.operations.size)
        assertTrue(complete.result.summaryJson.contains("\"format\":\"android_vector_drawable\""))

        // The model received the constrained system message + summary, not XML.
        assertEquals(VectorTuneupPrompts.SYSTEM_MESSAGE, streamer.lastChat!!.systemMessage)
        val body = streamer.lastMessages.single().content
        assertTrue(body.contains("Vector summary JSON:"))
        assertTrue(body.contains("Clean up the green stroke."))
        assertFalse(body.contains("<vector"))
    }

    @Test
    fun tuneUpAppliesPlanIntoCandidate() = runTest {
        val (svc, _) = service(
            StreamEvent.Delta("```vector-edit-plan\n{ \"operations\": [ " +
                "{ \"op\": \"recolor_paths\", \"target\": { \"pathIds\": [\"p_001\"] }, " +
                "\"strokeColor\": \"#FFFFFF\" } ] }\n```"),
            StreamEvent.Complete(null),
        )

        val complete = svc.tuneUp(request()).toList()
            .filterIsInstance<VectorTuneupAiChunk.Complete>().single()
        val apply = complete.result.applyResult
        assertEquals(1, apply.recoloredPathCount)
        assertTrue("candidate XML should carry the new color", apply.xml.contains("#FFFFFF"))
    }

    @Test
    fun tuneUpEmitsErrorOnStreamError() = runTest {
        val (svc, _) = service(StreamEvent.Error("network down"))
        val chunks = svc.tuneUp(request()).toList()
        val error = chunks.filterIsInstance<VectorTuneupAiChunk.Error>().single()
        assertEquals(VectorTuneupAiService.STREAM_FAILED_MESSAGE, error.message)
        assertTrue(chunks.none { it is VectorTuneupAiChunk.Complete })
    }

    @Test
    fun tuneUpEmitsErrorOnMalformedModelReply() = runTest {
        val (svc, _) = service(
            StreamEvent.Delta("I cannot do that, here is some prose with no JSON."),
            StreamEvent.Complete(null),
        )
        val chunks = svc.tuneUp(request()).toList()
        val error = chunks.filterIsInstance<VectorTuneupAiChunk.Error>().single()
        assertEquals(VectorTuneupAiService.PARSE_FAILED_MESSAGE, error.message)
    }

    @Test
    fun tuneUpHandlesEmptyPlan() = runTest {
        val (svc, _) = service(
            StreamEvent.Delta("```vector-edit-plan\n" +
                "{ \"schema\": 1, \"summary\": \"already clean\", \"operations\": [] }\n```"),
            StreamEvent.Complete(null),
        )
        val complete = svc.tuneUp(request()).toList()
            .filterIsInstance<VectorTuneupAiChunk.Complete>().single()
        assertTrue(complete.result.plan.isEmpty)
        // Empty plan leaves the XML untouched.
        assertEquals(xml, complete.result.applyResult.xml)
        assertTrue(complete.result.applyResult.warnings.any {
            it.code == VectorWarning.Codes.AI_PLAN_EMPTY
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
