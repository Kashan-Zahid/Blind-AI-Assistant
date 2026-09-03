package com.example.blindaassistant

/**
 * Pure, unit-testable core of the WhatsApp incoming-call flow.
 * Android integration (accessibility events, TTS, recognizer, AudioManager)
 * drives this state machine; the logic itself has no Android dependencies.
 */

enum class WhatsAppCallPhase {
    IDLE,
    STABILIZING,
    ANNOUNCING_CALLER,
    WAITING_FOR_CALL_COMMAND,
    ANSWERED,
    DECLINED,
    TIMED_OUT,
    CALL_ENDED
}

/**
 * Abstraction over the device audio knobs used to duck the WhatsApp ringtone
 * while the assistant speaks and listens. Implementations must never leave
 * volumes altered after [IncomingCallAudioSession.restore].
 */
interface RingtoneAudioGateway {
    fun getRingVolume(): Int
    fun setRingVolume(volume: Int)
    fun getMusicVolume(): Int
    fun setMusicVolume(volume: Int)
    fun requestDuckingFocus()
    fun abandonDuckingFocus()
}

/**
 * Captures the pre-call audio state, ducks ringtone/media for the duration of
 * the incoming-call interaction, and restores the exact previous state.
 * Idempotent: safe to call restore() multiple times or without a prior duck().
 */
class IncomingCallAudioSession(private val audio: RingtoneAudioGateway) {

    companion object {
        // Low but non-zero to avoid triggering silent/DND modes on some devices.
        const val DUCKED_RING_VOLUME = 1
        const val DUCKED_MUSIC_VOLUME = 1
    }

    private var savedRingVolume: Int? = null
    private var savedMusicVolume: Int? = null
    private var focusHeld = false

    val isDucked: Boolean
        get() = savedRingVolume != null

    fun duck() {
        if (savedRingVolume == null) {
            savedRingVolume = audio.getRingVolume()
            savedMusicVolume = audio.getMusicVolume()
        }
        audio.setRingVolume(DUCKED_RING_VOLUME)
        audio.setMusicVolume(DUCKED_MUSIC_VOLUME)
        if (!focusHeld) {
            audio.requestDuckingFocus()
            focusHeld = true
        }
    }

    fun restore() {
        val ring = savedRingVolume
        val music = savedMusicVolume
        if (ring != null) {
            audio.setRingVolume(ring)
        }
        if (music != null) {
            audio.setMusicVolume(music)
        }
        savedRingVolume = null
        savedMusicVolume = null
        if (focusHeld) {
            audio.abandonDuckingFocus()
            focusHeld = false
        }
    }
}

/**
 * Short, natural caller wording. Never produces doubled phrasing like
 * "03001234567 on WhatsApp is calling you on WhatsApp".
 */
object WhatsAppCallerDisplay {

    fun sanitize(rawCaller: String): String {
        var cleaned = rawCaller.trim()
        val suffixPattern = Regex("""(?i)\s+on\s+whatsapp$""")
        cleaned = cleaned.replace(suffixPattern, "").trim()
        return cleaned
    }

    fun announcement(caller: String, isVideo: Boolean): String {
        val callType = if (isVideo) "video call" else "call"
        val display = if (caller.isBlank()) "an unknown caller" else caller
        return "Incoming WhatsApp $callType from $display."
    }
}

/**
 * Incoming WhatsApp call session state machine:
 * IDLE -> STABILIZING -> ANNOUNCING_CALLER -> WAITING_FOR_CALL_COMMAND
 *      -> ANSWERED / DECLINED / TIMED_OUT / CALL_ENDED
 *
 * Recognition is only permitted after the announcement TTS has fully
 * completed, never while TTS is speaking, and never past the decision window.
 */
class WhatsAppCallFlow(private val clock: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        const val STABILIZE_DELAY_MS = 800L
        const val DECISION_WINDOW_MS = 25_000L
    }

    var phase: WhatsAppCallPhase = WhatsAppCallPhase.IDLE
        private set
    var callerDisplay: String = ""
        private set
    var isVideoCall: Boolean = false
        private set
    private var decisionDeadlineMs: Long = 0L

    val isActive: Boolean
        get() = phase == WhatsAppCallPhase.STABILIZING ||
                phase == WhatsAppCallPhase.ANNOUNCING_CALLER ||
                phase == WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND

    /**
     * Returns true when a new session was started. Refuses to start while a
     * cellular call is ringing/active or while a WhatsApp session is live.
     */
    fun onIncomingCallDetected(rawCaller: String, isVideo: Boolean, cellularCallActive: Boolean): Boolean {
        if (isActive || cellularCallActive) return false
        phase = WhatsAppCallPhase.STABILIZING
        callerDisplay = WhatsAppCallerDisplay.sanitize(rawCaller)
        isVideoCall = isVideo
        return true
    }

    fun announcementText(): String = WhatsAppCallerDisplay.announcement(callerDisplay, isVideoCall)

    fun onAnnouncementStarted() {
        if (phase == WhatsAppCallPhase.STABILIZING) {
            phase = WhatsAppCallPhase.ANNOUNCING_CALLER
        }
    }

    fun onAnnouncementCompleted() {
        if (phase == WhatsAppCallPhase.ANNOUNCING_CALLER) {
            phase = WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND
            decisionDeadlineMs = clock() + DECISION_WINDOW_MS
        }
    }

    fun canStartRecognition(isSpeaking: Boolean): Boolean {
        return phase == WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND &&
                !isSpeaking &&
                clock() < decisionDeadlineMs
    }

    fun isDecisionExpired(): Boolean {
        return phase == WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND && clock() >= decisionDeadlineMs
    }

    fun onAnswered() {
        if (isActive) phase = WhatsAppCallPhase.ANSWERED
    }

    fun onDeclined() {
        if (isActive) phase = WhatsAppCallPhase.DECLINED
    }

    fun onCallDismissed() {
        if (isActive) phase = WhatsAppCallPhase.CALL_ENDED
    }

    fun onTimedOut() {
        if (phase == WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND) {
            phase = WhatsAppCallPhase.TIMED_OUT
        }
    }

    fun reset() {
        phase = WhatsAppCallPhase.IDLE
        callerDisplay = ""
        isVideoCall = false
        decisionDeadlineMs = 0L
    }
}
