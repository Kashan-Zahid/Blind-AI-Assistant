package com.blindassistant

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiClientTest {

    @Test
    fun testApiKeyDetection() {
        val client = AiClient()
        assertFalse(client.hasApiKey())

        client.configure("  ")
        assertFalse(client.hasApiKey())

        client.configure("REPLACE_WITH_GEMINI_KEY")
        assertFalse(client.hasApiKey())

        client.configure("YOUR_GEMINI_KEY_HERE")
        assertFalse(client.hasApiKey())

        client.configure("YOUR_KEY_HERE")
        assertFalse(client.hasApiKey())

        client.configure("AIzaSyTestKey123456789")
        assertTrue(client.hasApiKey())
    }

    @Test
    fun testNoApiKeyMessage() = runBlocking {
        val client = AiClient()
        val response = client.ask("Why is the sky blue?")
        assertEquals("Gemini API key is not configured.", response)
    }

    @Test
    fun testNoApiKeyMessageForVision() = runBlocking {
        val client = AiClient()
        val response = client.askWithVision("Describe scene", "dummy_base64_data")
        assertEquals("Gemini API key is not configured.", response)
    }

    @Test
    fun testNoApiKeyMessageForContext() = runBlocking {
        val client = AiClient()
        val response = client.askWithContext("System prompt", "User prompt")
        assertEquals("Gemini API key is not configured.", response)
    }

    @Test
    fun testBlankQuestion() = runBlocking {
        val client = AiClient("AIzaSyTestKey123456789")
        val response = client.ask("   ")
        assertEquals("I did not hear a question. Please ask again.", response)
    }

    @Test
    fun testBlankVisionImage() = runBlocking {
        val client = AiClient("AIzaSyTestKey123456789")
        val response = client.askWithVision("Describe scene", "   ")
        assertEquals("Camera image is not available.", response)
    }

    @Test
    fun testBlankContextPrompt() = runBlocking {
        val client = AiClient("AIzaSyTestKey123456789")
        val response = client.askWithContext("System prompt", "   ")
        assertEquals("No text provided to process.", response)
    }

    @Test
    fun testDefaultModelIsGemini36Flash() {
        assertEquals("gemini-3.6-flash", AiClient.DEFAULT_MODEL)
    }

    @Test
    fun testCustomModelConfiguration() {
        val client = AiClient("AIzaSyTestKey123456789", "gemini-3.6-flash")
        assertTrue(client.hasApiKey())
    }

    @Test
    fun testSpeechCleaning() {
        val client = AiClient()
        val dirtyText = "## Title\n* **Bold text** and _italics_\n* Item 1\n* Item 2\nVisit https://example.com/test for info. 25 + 40 = 65 & 100% at 30°!"
        val cleaned = client.cleanForSpeech(dirtyText)
        assertFalse(cleaned.contains("##"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://"))
        assertTrue(cleaned.contains("plus"))
        assertTrue(cleaned.contains("equals"))
        assertTrue(cleaned.contains("percent"))
        assertTrue(cleaned.contains("and"))
        assertTrue(cleaned.contains("degrees"))
        assertTrue(cleaned.contains("at"))
    }
}
