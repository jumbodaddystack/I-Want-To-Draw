package com.aichat.sandbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilitiesTest {

    @Test
    fun knownOpenAiVisionModelsHitTable() {
        assertTrue(ModelCapabilities.of("gpt-4o").supportsVision)
        assertTrue(ModelCapabilities.of("gpt-4o-mini").supportsVision)
        assertTrue(ModelCapabilities.of("gpt-4.1").supportsVision)
        assertEquals("", ModelCapabilities.of("gpt-4o").notes)
    }

    @Test
    fun knownNonVisionOpenAiModelsHitTable() {
        assertFalse(ModelCapabilities.of("gpt-4").supportsVision)
        assertFalse(ModelCapabilities.of("gpt-3.5-turbo").supportsVision)
        assertFalse(ModelCapabilities.of("o3-mini").supportsVision)
    }

    @Test
    fun knownAnthropicModelsHitTable() {
        assertTrue(ModelCapabilities.of("claude-opus-4-7").supportsVision)
        assertTrue(ModelCapabilities.of("claude-sonnet-4-6").supportsVision)
        assertTrue(ModelCapabilities.of("claude-haiku-4-5-20251001").supportsVision)
    }

    @Test
    fun knownGoogleModelsHitTable() {
        assertTrue(ModelCapabilities.of("gemini-2.5-pro").supportsVision)
        assertTrue(ModelCapabilities.of("gemini-2.0-flash").supportsVision)
    }

    @Test
    fun unknownIdsHitInferencePath() {
        val claudeFuture = ModelCapabilities.of("claude-opus-9-99")
        assertTrue(claudeFuture.supportsVision)
        assertEquals("inferred", claudeFuture.notes)

        val geminiFuture = ModelCapabilities.of("gemini-3.0-ultra")
        assertTrue(geminiFuture.supportsVision)
        assertEquals("inferred", geminiFuture.notes)

        val visionTagged = ModelCapabilities.of("acme-vision-large")
        assertTrue(visionTagged.supportsVision)
        assertEquals("inferred", visionTagged.notes)
    }

    @Test
    fun unknownNonVisionLikeIdsInferAsNonVision() {
        val mystery = ModelCapabilities.of("acme-llama-70b")
        assertFalse(mystery.supportsVision)
        assertEquals("inferred", mystery.notes)
    }

    @Test
    fun blankStringReturnsFalse() {
        assertFalse(ModelCapabilities.of("").supportsVision)
        assertFalse(ModelCapabilities.of("   ").supportsVision)
    }

    @Test
    fun stringExtensionMatchesRegistry() {
        assertTrue("gpt-4o".supportsVision())
        assertFalse("gpt-3.5-turbo".supportsVision())
        assertFalse("".supportsVision())
    }

    @Test
    fun chatExtensionReadsModelField() {
        val visionChat = Chat(model = "claude-opus-4-7")
        val textChat = Chat(model = "gpt-3.5-turbo")
        assertTrue(visionChat.supportsVision())
        assertFalse(textChat.supportsVision())
    }
}
