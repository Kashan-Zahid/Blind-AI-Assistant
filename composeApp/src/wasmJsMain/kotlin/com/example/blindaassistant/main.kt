package com.example.blindaassistant

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val voiceService = createVoiceService()
    val aiClient = AiClient(cloudKey = "")

    val body = kotlinx.browser.document.body ?: return
    ComposeViewport(body) {
        App(aiClient, voiceService)
    }
}
