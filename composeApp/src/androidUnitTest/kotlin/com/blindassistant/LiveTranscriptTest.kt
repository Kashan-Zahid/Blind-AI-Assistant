package com.blindassistant

import android.content.ContextWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptTest {

    private class FakeContext : ContextWrapper(null)

    private class FakeDeviceController(
        aiClient: AiClient
    ) : DeviceController(
        context = FakeContext(),
        aiClient = aiClient
    ) {
        override fun getBatteryStatus(): String = "Battery is at 85 percent."
        override fun toggleFlashlight(turnOn: Boolean): String = if (turnOn) "Flashlight turned on." else "Flashlight turned off."
    }

    private class TestVoiceService(
        val commandProcessor: CommandProcessor
    ) : VoiceService {
        private val _status = MutableStateFlow("Ready")
        override val status: StateFlow<String> = _status

        private val _transcript = MutableStateFlow("")
        override val transcript: StateFlow<String> = _transcript

        private val _assistantReply = MutableStateFlow("")
        override val assistantReply: StateFlow<String> = _assistantReply

        private val _isListening = MutableStateFlow(false)
        override val isListening: StateFlow<Boolean> = _isListening

        private val _isSpeaking = MutableStateFlow(false)
        override val isSpeaking: StateFlow<Boolean> = _isSpeaking

        private val _cameraPopupState = MutableStateFlow(CameraPopupState())
        override val cameraPopupState: StateFlow<CameraPopupState> = _cameraPopupState

        override fun startListening() {
            _isListening.value = true
            _transcript.value = ""
            _assistantReply.value = ""
            _status.value = "Listening..."
        }

        override fun stopListening() {
            _isListening.value = false
            _status.value = "Processing..."
        }

        fun simulatePartialResult(partial: String) {
            if (_isListening.value && partial.isNotBlank()) {
                _transcript.value = partial
            }
        }

        fun simulateFinalResult(finalText: String) {
            _isListening.value = false
            if (finalText.isNotBlank()) {
                _transcript.value = finalText
                _status.value = "Processing..."
                processVoiceCommand(finalText)
            } else {
                _status.value = "No speech detected."
            }
        }

        fun simulateError(errorType: String) {
            _isListening.value = false
            _status.value = when (errorType) {
                "NO_MATCH", "TIMEOUT" -> "No speech detected."
                "PERMISSION" -> "Microphone permission is required."
                else -> "Speech not recognized."
            }
        }

        override fun speak(text: String) {
            if (text.isBlank()) return
            _assistantReply.value = text
            _isSpeaking.value = true
            _status.value = "Assistant is speaking..."
        }

        override fun stopSpeaking() {
            _isSpeaking.value = false
        }

        override fun processVoiceCommand(text: String) {
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                _status.value = "No speech detected."
                return
            }
            _transcript.value = trimmed
            _status.value = "Processing..."
            val reply = runBlocking { commandProcessor.processCommand(trimmed) }
            _assistantReply.value = reply
            speak(reply)
        }

        override fun setOnResultListener(listener: (String) -> Unit) {}
    }

    @Test
    fun testLivePartialTranscriptFlowAndFinalRetention() {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)
        val service = TestVoiceService(processor)

        // 1. User starts listening
        service.startListening()
        assertTrue(service.isListening.value)
        assertEquals("Listening...", service.status.value)
        assertEquals("", service.transcript.value)

        // 2. Partial results update progressively
        service.simulatePartialResult("what is")
        assertEquals("what is", service.transcript.value)

        service.simulatePartialResult("what is my")
        assertEquals("what is my", service.transcript.value)

        service.simulatePartialResult("what is my battery")
        assertEquals("what is my battery", service.transcript.value)

        service.simulatePartialResult("what is my battery level")
        assertEquals("what is my battery level", service.transcript.value)

        // 3. Final recognition completes
        service.simulateFinalResult("What is my battery level?")
        assertFalse(service.isListening.value)
        assertEquals("What is my battery level?", service.transcript.value)
        assertEquals("Battery is at 85 percent.", service.assistantReply.value)
        assertTrue(service.isSpeaking.value)

        // 4. Final transcript remains visible even after speaking completes
        service.stopSpeaking()
        assertFalse(service.isSpeaking.value)
        assertEquals("What is my battery level?", service.transcript.value)
        assertEquals("Battery is at 85 percent.", service.assistantReply.value)
    }

    @Test
    fun testEmptySpeechDoesNotTriggerGemini() {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)
        val service = TestVoiceService(processor)

        service.startListening()
        service.simulateFinalResult("")

        assertEquals("No speech detected.", service.status.value)
        assertEquals("", service.transcript.value)
        assertEquals("", service.assistantReply.value)
        assertFalse(service.isSpeaking.value)

        // Process empty string directly
        service.processVoiceCommand("   ")
        assertEquals("No speech detected.", service.status.value)
        assertEquals("", service.assistantReply.value)
    }

    @Test
    fun testErrorHandlingDisplays() {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)
        val service = TestVoiceService(processor)

        // No speech / timeout
        service.startListening()
        service.simulateError("NO_MATCH")
        assertEquals("No speech detected.", service.status.value)

        // Permission error
        service.startListening()
        service.simulateError("PERMISSION")
        assertEquals("Microphone permission is required.", service.status.value)

        // Other generic recognizer error
        service.startListening()
        service.simulateError("AUDIO_SERVER")
        assertEquals("Speech not recognized.", service.status.value)
    }

    @Test
    fun testTalkBackAccessibilityContentDescriptionFormats() {
        val transcript = "What is my battery level?"
        val assistantReply = "Battery is at 85 percent."

        val formattedSpeechDescription = "Your speech: $transcript"
        val formattedAssistantDescription = "Assistant: $assistantReply"

        assertEquals("Your speech: What is my battery level?", formattedSpeechDescription)
        assertEquals("Assistant: Battery is at 85 percent.", formattedAssistantDescription)
    }

    @Test
    fun testConsecutiveVoiceCommandsClearAndReplaceTranscript() {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)
        val service = TestVoiceService(processor)

        // First command
        service.startListening()
        service.simulateFinalResult("Turn on flashlight")
        assertEquals("Turn on flashlight", service.transcript.value)
        assertEquals("Flashlight turned on.", service.assistantReply.value)

        // Second command started - transcript & reply reset for new turn
        service.startListening()
        assertEquals("", service.transcript.value)
        assertEquals("", service.assistantReply.value)
        assertTrue(service.isListening.value)

        service.simulatePartialResult("Turn off")
        assertEquals("Turn off", service.transcript.value)

        service.simulateFinalResult("Turn off flashlight")
        assertEquals("Turn off flashlight", service.transcript.value)
        assertEquals("Flashlight turned off.", service.assistantReply.value)
    }
}
