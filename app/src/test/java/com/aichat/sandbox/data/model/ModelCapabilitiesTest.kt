package com.aichat.sandbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilitiesTest {

    @Test
    fun knownOpenAiVisionModelsHitTable() {
        assertTrue(ModelCapabilities.of("gpt-5.5").supportsVision)
        assertTrue(ModelCapabilities.of("gpt-5.4").supportsVision)
        assertTrue(ModelCapabilities.of("gpt-5.4-pro").supportsVision)
        assertTrue(ModelCapabilities.of("gpt-5.4-mini").supportsVision)
        assertTrue(ModelCapabilities.of("gpt-5.4-nano").supportsVision)
        assertEquals("", ModelCapabilities.of("gpt-5.4").notes)
    }

    @Test
    fun knownAnthropicModelsHitTable() {
        assertTrue(ModelCapabilities.of("claude-opus-4-7").supportsVision)
        assertTrue(ModelCapabilities.of("claude-sonnet-4-6").supportsVision)
        assertTrue(ModelCapabilities.of("claude-haiku-4-5").supportsVision)
    }

    @Test
    fun knownGoogleModelsHitTable() {
        assertTrue(ModelCapabilities.of("gemini-3.1-pro-preview").supportsVision)
        assertTrue(ModelCapabilities.of("gemini-3.1-flash-lite").supportsVision)
        assertTrue(ModelCapabilities.of("gemini-3-pro-preview").supportsVision)
        assertTrue(ModelCapabilities.of("gemini-2.5-flash").supportsVision)
    }

    @Test
    fun unknownIdsHitInferencePath() {
        val claudeFuture = ModelCapabilities.of("claude-opus-9-99")
        assertTrue(claudeFuture.supportsVision)
        assertEquals("inferred", claudeFuture.notes)

        val geminiFuture = ModelCapabilities.of("gemini-4.0-ultra")
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
        assertTrue("gpt-5.4".supportsVision())
        assertFalse("".supportsVision())
    }

    @Test
    fun chatExtensionReadsModelField() {
        val visionChat = Chat(model = "claude-opus-4-7")
        assertTrue(visionChat.supportsVision())
    }

    // Substring matcher in ModelPricing must resolve longer IDs before
    // shorter prefixes — "gpt-5.4-pro" must NOT collapse onto "gpt-5.4"
    // or "gpt-5" pricing.
    @Test
    fun pricingResolvesLongestPrefixFirst() {
        val pro = ModelPricing.forModel("gpt-5.4-pro")
        val base = ModelPricing.forModel("gpt-5.4")
        assertEquals(15.00, pro.inputPricePerMillion, 0.001)
        assertEquals(3.00, base.inputPricePerMillion, 0.001)
    }
}
