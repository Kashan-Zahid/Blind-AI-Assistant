package com.blindassistant

import kotlinx.coroutines.flow.StateFlow

data class CameraPopupState(
    val isVisible: Boolean = false,
    val title: String = "Camera Viewfinder",
    val status: String = "Scanning...",
    val base64Thumbnail: String? = null
)

interface VoiceService {
    val status: StateFlow<String>
    val transcript: StateFlow<String>
    val assistantReply: StateFlow<String>
    val isListening: StateFlow<Boolean>
    val isSpeaking: StateFlow<Boolean>
    val cameraPopupState: StateFlow<CameraPopupState>
    
    fun startListening()
    fun stopListening()
    fun speak(text: String)
    fun stopSpeaking()
    fun processVoiceCommand(text: String)
    fun setOnResultListener(listener: (String) -> Unit)
    fun dismissCameraPopup() {}
    fun showCameraPopup(title: String, status: String = "Scanning...", thumbnail: String? = null) {}
}

expect fun createVoiceService(): VoiceService

expect fun decodeBase64ToImageBitmap(base64: String): androidx.compose.ui.graphics.ImageBitmap?
