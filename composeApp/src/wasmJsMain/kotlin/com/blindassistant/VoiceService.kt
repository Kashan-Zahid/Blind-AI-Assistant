package com.blindassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WebVoiceService : VoiceService {
    private val _status = MutableStateFlow("Browser Ready")
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

    override fun dismissCameraPopup() {
        _cameraPopupState.value = CameraPopupState(isVisible = false)
    }

    override fun showCameraPopup(title: String, status: String, thumbnail: String?) {
        _cameraPopupState.value = CameraPopupState(isVisible = true, title = title, status = status, base64Thumbnail = thumbnail)
    }

    private var onResult: ((String) -> Unit)? = null

    override fun startListening() {
        _isListening.value = true
        _status.value = "Listening (Browser)..."
        startWebSpeech(::handleSpeechResult)
    }

    override fun stopListening() {
        _isListening.value = false
        _status.value = "Browser Ready"
    }

    override fun speak(text: String) {
        _assistantReply.value = text
        _status.value = "Speaking..."
        _isSpeaking.value = true
        browserSpeak(text)
    }

    override fun stopSpeaking() {
        _isSpeaking.value = false
    }

    override fun processVoiceCommand(text: String) {
        handleSpeechResult(text)
    }

    override fun setOnResultListener(listener: (String) -> Unit) {
        onResult = listener
    }

    private fun handleSpeechResult(text: String) {
        _isListening.value = false
        _status.value = "Browser Ready"
        _transcript.value = text
        onResult?.invoke(text)
    }
}

actual fun createVoiceService(): VoiceService = WebVoiceService()

actual fun decodeBase64ToImageBitmap(base64: String): androidx.compose.ui.graphics.ImageBitmap? = null

// JS Interop
private fun startWebSpeech(onResult: (String) -> Unit) {
    val recognition = js("new (window.SpeechRecognition || window.webkitSpeechRecognition)()")
    js("recognition.onresult = function(event) { onResult(event.results[0][0].transcript); }")
    js("recognition.start()")
}

private fun browserSpeak(text: String) {
    val utterance = js("new SpeechSynthesisUtterance(text)")
    js("window.speechSynthesis.speak(utterance)")
}
