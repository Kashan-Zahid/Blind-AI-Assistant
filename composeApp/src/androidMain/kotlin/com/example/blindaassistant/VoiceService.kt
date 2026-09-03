package com.example.blindaassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class AndroidVoiceService(
    private val context: Context,
    val deviceController: DeviceController,
    private val commandProcessor: CommandProcessor
) : VoiceService, TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _status = MutableStateFlow("Press and hold the center button to speak")
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
        _cameraPopupState.value = CameraPopupState(
            isVisible = true,
            title = title,
            status = status,
            base64Thumbnail = thumbnail ?: _cameraPopupState.value.base64Thumbnail
        )
    }

    private var onResult: ((String) -> Unit)? = null
    private var ttsCompletionCallback: (() -> Unit)? = null
    private var listenSettledCallback: (() -> Unit)? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isRecognizerInitializing = false
    private var pendingAutoListen = false
    private var pendingTtsText: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private enum class InternalState {
        IDLE, LISTENING, PROCESSING
    }
    private var currentState = InternalState.IDLE

    init {
        activeInstance = this
        mainHandler.post {
            initSpeechRecognizer()
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    private var isSilencedForListening = false

    private fun silenceBeepBeforeListening() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            isSilencedForListening = true
        } catch (_: Exception) {}
    }

    private fun restoreVolumeAfterListening() {
        if (!isSilencedForListening) return
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            isSilencedForListening = false
        } catch (_: Exception) {}
    }

    private fun requestListeningAudioFocus(duckOnly: Boolean = true) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusType = if (duckOnly) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                val req = AudioFocusRequest.Builder(focusType)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                audioManager?.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                val focusType = if (duckOnly) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    focusType
                )
            }
        } catch (_: Exception) {}
    }

    private fun abandonListeningAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager?.abandonAudioFocusRequest(it)
                    audioFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun requestTtsAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                audioManager?.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {}
    }

    private fun initSpeechRecognizer() {
        if (isRecognizerInitializing) return
        if (!SpeechRecognizer.isRecognitionAvailable(context.applicationContext)) {
            _status.value = "Speech recognition is not available on this device."
            return
        }

        try {
            isRecognizerInitializing = true
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _status.value = "Listening... Speak now"
                        _isListening.value = true
                        currentState = InternalState.LISTENING
                        isRecognizerInitializing = false
                        mainHandler.postDelayed({ restoreVolumeAfterListening() }, 250L)
                        deviceController.triggerHaptic(HapticFeedbackType.START_LISTENING)
                    }

                    override fun onBeginningOfSpeech() {
                        _status.value = "Listening..."
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        currentState = InternalState.PROCESSING
                        _status.value = "Processing..."
                        deviceController.triggerHaptic(HapticFeedbackType.STOP_LISTENING)
                        abandonListeningAudioFocus()
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        isRecognizerInitializing = false
                        currentState = InternalState.IDLE
                        restoreVolumeAfterListening()
                        abandonListeningAudioFocus()
                        settleListening()

                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                            else -> "Speech not recognized."
                        }
                        _status.value = errorMsg
                        // Recreate recognizer cleanly on error so subsequent attempts work
                        mainHandler.post {
                            try {
                                speechRecognizer?.destroy()
                                speechRecognizer = null
                            } catch (_: Exception) {}
                        }

                        // Reset message after 2.5s if not listening or speaking so the user isn't stuck on an error
                        mainHandler.postDelayed({
                            if (!_isListening.value && !_isSpeaking.value && _status.value == "No speech detected.") {
                                _status.value = "Tap or hold the center button to speak."
                            }
                        }, 2500L)

                        // If YouTube selection state is active or YouTube is open in background, keep listening
                        val isYouTubeActive = BlindAccessibilityService.youTubeSelectionState?.results?.isNotEmpty() == true ||
                                BlindAccessibilityService.instance?.isYouTubeOpen() == true

                        if (isYouTubeActive && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                            mainHandler.postDelayed({
                                if (!_isSpeaking.value && !_isListening.value) {
                                    startListening()
                                }
                            }, 500L)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        isRecognizerInitializing = false
                        currentState = InternalState.IDLE
                        restoreVolumeAfterListening()
                        abandonListeningAudioFocus()

                        BlindAccessibilityService.youTubeSelectionState?.let { yt ->
                            Log.d(
                                "BlindAI_YT_STATE",
                                """
                                SELECTION_PERSISTENCE_CHECK
                                count=${yt.results.size}
                                query=${yt.query}
                                titles=${yt.results.joinToString(", ") { it.title }}
                                """.trimIndent()
                            )
                        }

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim()

                        if (!spokenText.isNullOrBlank()) {
                            _transcript.value = spokenText
                            _status.value = "Processing..."
                            deviceController.triggerHaptic(HapticFeedbackType.SUCCESS)
                            onResult?.invoke(spokenText)
                            settleListening()
                            processVoiceCommand(spokenText)
                        } else {
                            _status.value = "No speech detected."
                            deviceController.triggerHaptic(HapticFeedbackType.ERROR)
                            settleListening()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim()
                        if (!partial.isNullOrBlank()) {
                            _transcript.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            isRecognizerInitializing = false
            _status.value = "Speech recognizer is ready."
        }
    }

    override fun startListening() {
        if (_isListening.value || _isSpeaking.value) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _status.value = "Microphone permission is required."
            _isListening.value = false
            return
        }

        isRecognizerInitializing = false

        BlindAccessibilityService.youTubeSelectionState?.let { yt ->
            Log.d("BlindAI_YT", "YOUTUBE_SELECTION_REUSED\ncount=${yt.results.size}\nquery=\"${yt.query}\"")
        }

        mainHandler.post {
            try {
                silenceBeepBeforeListening()
                requestListeningAudioFocus(duckOnly = true)

                speechRecognizer?.destroy()
                speechRecognizer = null

                initSpeechRecognizer()

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.US.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }

                _transcript.value = ""
                _assistantReply.value = ""
                _status.value = "Listening..."
                _isListening.value = true
                currentState = InternalState.LISTENING

                val yt = BlindAccessibilityService.youTubeSelectionState
                Log.d(
                    "BlindAI_YT_STATE",
                    """
                    SELECTION_REUSE_CHECK
                    count=${yt?.results?.size ?: 0}
                    query=${yt?.query ?: ""}
                    titles=${yt?.results?.joinToString(", ") { it.title } ?: "none"}
                    """.trimIndent()
                )

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                isRecognizerInitializing = false
                currentState = InternalState.IDLE
                restoreVolumeAfterListening()
                abandonListeningAudioFocus()
                _status.value = "Press and hold the center button to speak."
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                _isListening.value = false
                isRecognizerInitializing = false
                currentState = InternalState.IDLE
                restoreVolumeAfterListening()
                abandonListeningAudioFocus()
                settleListening()
                BlindAccessibilityService.youTubeSelectionState?.let { yt ->
                    Log.d(
                        "BlindAI_YT_STATE",
                        """
                        SELECTION_PERSISTENCE_CHECK
                        count=${yt.results.size}
                        query=${yt.query}
                        titles=${yt.results.joinToString(", ") { it.title }}
                        """.trimIndent()
                    )
                }
                _status.value = "Processing..."
            } catch (_: Exception) {}
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        _assistantReply.value = text
        _status.value = "Assistant is speaking..."
        _isSpeaking.value = true

        Log.d(
            "BlindAI_YT_TTS",
            """
            TTS_REQUEST
            text=$text
            """.trimIndent()
        )

        // Detect if the speech expects a direct follow-up response from the user
        val lower = text.lowercase()
        pendingAutoListen = lower.contains("option") ||
                lower.contains("say an option") ||
                lower.contains("say option") ||
                lower.contains("say reply") ||
                lower.contains("reply to answer") ||
                lower.contains("say play voice note") ||
                lower.contains("what message should i send") ||
                lower.contains("say send to confirm") ||
                lower.contains("did you mean") ||
                BlindAccessibilityService.youTubeSelectionState?.results?.isNotEmpty() == true ||
                BlindAccessibilityService.instance?.isYouTubeOpen() == true

        if (lower.contains("i found") && lower.contains("video")) {
            val countMatch = Regex("""(?i)\bi found (\d+)""").find(text)
            val cCount = countMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            Log.d("BlindAI_YT", "YOUTUBE_ANNOUNCEMENT\ncandidateCount=$cCount\nannouncement=\"${text.replace("\n", " ")}\"")
        }

        mainHandler.post {
            if (isTtsInitialized && tts != null) {
                try {
                    tts?.stop()
                } catch (_: Exception) {}

                requestTtsAudioFocus()
                val utteranceId = "blind_assistant_utt_${System.currentTimeMillis()}"
                val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    if (lower.contains("i found") && lower.contains("video")) {
                        Log.e("BlindAI_YT", "YOUTUBE_RESULTS_FOUND_TTS_FAILED\nerror=TTS_SPEAK_ERROR")
                    }
                    _isSpeaking.value = false
                    abandonListeningAudioFocus()
                    _status.value = "Press and hold the center button to speak."
                    invokeTtsCompletion()
                }
            } else {
                if (lower.contains("i found") && lower.contains("video")) {
                    Log.w("BlindAI_YT", "YOUTUBE_RESULTS_FOUND_TTS_FAILED\nerror=TTS_NOT_INITIALIZED_YET")
                }
                pendingTtsText = text
                _isSpeaking.value = false
                _status.value = "Press and hold the center button to speak."
                invokeTtsCompletion()
            }
        }
    }

    override fun stopSpeaking() {
        mainHandler.post {
            tts?.stop()
            _isSpeaking.value = false
            abandonListeningAudioFocus()
        }
    }

    /**
     * Speaks [text] and invokes [onDone] once TTS finishes (or fails).
     * Used by the incoming-call flow: recognition must only start after the
     * announcement has fully completed, so the recognizer never hears the
     * assistant's own speech.
     */
    fun speakWithCompletion(text: String, onDone: () -> Unit) {
        if (text.isBlank()) {
            mainHandler.post { onDone() }
            return
        }
        ttsCompletionCallback = onDone
        speak(text)
    }

    /**
     * Starts one recognition pass and invokes [onSettled] when the pass ends
     * (result, error, or explicit stop). If listening/speaking is already in
     * progress, [onSettled] is invoked immediately so waiters are not stranded.
     */
    fun startListeningWithSettled(onSettled: () -> Unit) {
        if (_isListening.value || _isSpeaking.value) {
            mainHandler.post { onSettled() }
            return
        }
        listenSettledCallback = onSettled
        startListening()
    }

    private fun invokeTtsCompletion() {
        val callback = ttsCompletionCallback
        ttsCompletionCallback = null
        callback?.invoke()
    }

    private fun settleListening() {
        val callback = listenSettledCallback
        listenSettledCallback = null
        callback?.invoke()
    }

    override fun processVoiceCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            _status.value = "No speech detected."
            return
        }
        scope.launch {
            _transcript.value = trimmed
            _status.value = "Processing..."
            val reply = commandProcessor.processCommand(trimmed)
            _assistantReply.value = reply
            speak(reply)
        }
    }

    override fun setOnResultListener(listener: (String) -> Unit) {
        onResult = listener
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttributes)
                }
            } catch (_: Exception) {}
            try {
                val englishLocale = Locale.US
                val avail = tts?.isLanguageAvailable(englishLocale)
                if (avail == TextToSpeech.LANG_AVAILABLE || avail == TextToSpeech.LANG_COUNTRY_AVAILABLE || avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                    tts?.language = englishLocale
                } else {
                    tts?.language = Locale.ENGLISH
                }
            } catch (_: Exception) {
                tts?.language = Locale.ENGLISH
            }
            tts?.setSpeechRate(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    _status.value = "Assistant is speaking..."
                    Log.d("BlindAI_YT_TTS", "TTS_STARTED")
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    abandonListeningAudioFocus()
                    _status.value = "Press and hold the center button to speak."
                    Log.d("BlindAI_YT_TTS", "TTS_FINISHED")

                    val shouldAutoListen = pendingAutoListen ||
                            BlindAccessibilityService.youTubeSelectionState?.results?.isNotEmpty() == true ||
                            BlindAccessibilityService.instance?.isYouTubeOpen() == true

                    pendingAutoListen = false
                    if (shouldAutoListen) {
                        mainHandler.postDelayed({
                            if (!_isSpeaking.value && !_isListening.value) {
                                startListening()
                            }
                        }, 400L)
                    }
                    invokeTtsCompletion()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    abandonListeningAudioFocus()
                    _status.value = "Press and hold the center button to speak."
                    invokeTtsCompletion()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    abandonListeningAudioFocus()
                    _status.value = "Press and hold the center button to speak."
                    invokeTtsCompletion()
                }
            })

            val pending = pendingTtsText
            if (pending != null) {
                pendingTtsText = null
                speak(pending)
            } else {
                speak("Welcome to Blind AI Assistant. Press and hold the center button to speak a command.")
            }
        } else {
            _status.value = "Text-to-speech initialization failed."
        }
    }

    fun setTtsLanguage(locale: Locale) {
        mainHandler.post {
            try {
                tts?.language = locale
            } catch (_: Exception) {}
        }
    }

    fun updateSpeechRate(rate: Float) {
        mainHandler.post {
            try {
                tts?.setSpeechRate(rate)
            } catch (_: Exception) {}
        }
    }

    fun onDestroy() {
        try {
            if (activeInstance == this) {
                activeInstance = null
            }
            speechRecognizer?.destroy()
            tts?.stop()
            tts?.shutdown()
            restoreVolumeAfterListening()
            abandonListeningAudioFocus()
            settleListening()
            invokeTtsCompletion()
        } catch (_: Exception) {}
    }

    companion object {
        var activeInstance: AndroidVoiceService? = null
            private set

        fun getInstance(
            context: Context,
            deviceController: DeviceController,
            commandProcessor: CommandProcessor
        ): AndroidVoiceService {
            val current = activeInstance
            if (current != null) return current
            val newInstance = AndroidVoiceService(context.applicationContext, deviceController, commandProcessor)
            activeInstance = newInstance
            return newInstance
        }

        fun speakGlobally(text: String) {
            activeInstance?.speak(text)
        }

        fun startListeningGlobally() {
            activeInstance?.startListening()
        }

        fun showCameraPopupGlobally(title: String, status: String = "Scanning...", thumbnail: String? = null) {
            activeInstance?.showCameraPopup(title, status, thumbnail)
        }

        fun dismissCameraPopupGlobally() {
            activeInstance?.dismissCameraPopup()
        }
    }
}

lateinit var androidVoiceServiceInstance: AndroidVoiceService

actual fun createVoiceService(): VoiceService = androidVoiceServiceInstance

actual fun decodeBase64ToImageBitmap(base64: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val clean = if (base64.contains(",")) base64.substringAfter(",") else base64
        val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bitmap?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
