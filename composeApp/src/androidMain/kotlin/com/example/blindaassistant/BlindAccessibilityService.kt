package com.example.blindaassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BlindAccessibilityService : AccessibilityService() {

    companion object {
        const val OPTIONS_CONTEXT_NONE = ""
        const val OPTIONS_CONTEXT_YOUTUBE_RESULTS = "youtube_results"
        const val OPTIONS_CONTEXT_GENERIC = "generic"
        private const val MAX_VOICE_OPTIONS = 5

        var instance: BlindAccessibilityService? = null
            private set

        @Volatile
        var youTubeSelectionState: YouTubeSelectionState? = null

        fun isServiceRunning(): Boolean = instance != null

        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val serviceName = "${context.packageName}/${BlindAccessibilityService::class.java.name}"
            return enabledServices.contains(serviceName) || enabledServices.contains(context.packageName)
        }

        fun openAccessibilitySettings(context: Context): String {
            return try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Opening Accessibility Settings. Please turn on 'Blind AI Assistant'."
            } catch (e: Exception) {
                "Could not open accessibility settings."
            }
        }

        fun isYouTubeShortsTitleOrUrl(text: String): Boolean {
            val lower = text.lowercase()
            if (lower.contains("#shorts") || lower.contains("#short") || lower.contains("/shorts/") || lower.contains("youtube.com/shorts")) {
                return true
            }
            if (lower.contains(" - play short") || lower.contains("shorts - play") || lower.contains("play short") || lower.contains("shorts video")) {
                return true
            }
            if (Regex("""(?i)^\s*shorts\s*[,:-]""").containsMatchIn(text) ||
                Regex("""(?i)[,:-]\s*shorts\s*[,:-]""").containsMatchIn(text) ||
                Regex("""(?i)[,:-]\s*short\s+video\b""").containsMatchIn(text) ||
                Regex("""(?i)\bshorts\s+shelf\b""").containsMatchIn(text) ||
                Regex("""(?i)\byoutube\s+shorts\b""").containsMatchIn(text) ||
                Regex("""(?i)\bshorts\s+carousel\b""").containsMatchIn(text) ||
                Regex("""(?i)\bshorts\b""").containsMatchIn(lower) && (lower.contains("shelf") || lower.contains("carousel") || lower.contains("reel"))) {
                return true
            }
            return false
        }

        fun isValidYouTubeWatchUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase().trim()
            if (lower.contains("/shorts/") || lower.contains("/channel/") ||
                lower.contains("/@") || lower.contains("/playlist") ||
                lower.contains("/feed/") || lower.contains("/subscriptions") ||
                lower.contains("/results")) {
                return false
            }
            return lower.contains("youtube.com/watch") || lower.contains("youtu.be/")
        }

        fun cleanYouTubeVideoTitle(raw: String): String {
            var title = raw

            // 1. Remove action suffixes ("- play video", "play video", "play short", "go to channel", "- play")
            title = title.replace(Regex("""(?i)\s*[-·•|,]?\s*play\s+(?:video|short)\b.*"""), "")
            title = title.replace(Regex("""(?i)\bplay\s+(?:video|short)\b"""), "")
            title = title.replace(Regex("""(?i)\s*[-·•|,]?\s*go\s+to\s+channel\b.*"""), "")
            title = title.replace(Regex("""(?i)\s*-\s*play\b"""), "")

            // 2. Remove parenthesized/bracketed durations or metadata: (10:15), [4:32], (4 minutes, 20 seconds)
            title = title.replace(Regex("""(?i)\(\s*\d{1,2}:\d{2}(?::\d{2})?\s*\)"""), "")
            title = title.replace(Regex("""(?i)\[\s*\d{1,2}:\d{2}(?::\d{2})?\s*\]"""), "")
            title = title.replace(Regex("""(?i)\(\s*\d+\s+(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)[^)]*\)"""), "")

            // 3. Remove upload dates / time ago (e.g. "2 years ago", "streamed 5 days ago", "yesterday", "3 weeks ago")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*(?:streamed\s+)?\d+\s+(?:years?|yrs?|months?|weeks?|days?|hours?|hrs?|minutes?|mins?|seconds?|secs?)\s+ago\b"""), "")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*streamed\s+(?:yesterday|\d+\s+(?:years?|yrs?|months?|weeks?|days?|hours?|hrs?|minutes?|mins?|seconds?|secs?)\s+ago)\b"""), "")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*yesterday\b"""), "")

            // 4. Remove views (e.g. "50M views", "1.2B views", "500K views", "10,234 views", "12 million views", "50,293,123 views")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*[\d,.]+\s*(?:[kmgtb]|million|billion|thousand)?\s*views?\b"""), "")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*no\s+views?\b"""), "")

            // 5. Remove word duration (e.g. "4 minutes, 20 seconds", "1 hour, 15 minutes", "18 minutes", "45 seconds")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*\b\d+\s+(?:hours?|hrs?)\s*(?:,\s*\d+\s+(?:minutes?|mins?))?(?:,\s*\d+\s+(?:seconds?|secs?))?\b"""), "")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*\b\d+\s+(?:minutes?|mins?)\s*(?:,\s*\d+\s+(?:seconds?|secs?))?\b"""), "")
            title = title.replace(Regex("""(?i)[,·•|-]?\s*\b\d+\s+(?:seconds?|secs?)\b"""), "")

            // 6. Remove clock timestamps: "4:32", "10:15", "1:02:33"
            title = title.replace(Regex("""(?i)[,·•|-]?\s*\b\d{1,2}:\d{2}(?::\d{2})?\b"""), "")

            // 7. Remove verified channel, badges, sponsored, subscribe, hashtags
            title = title.replace(Regex("""(?i)\b(?:verified\s+artist\s+channel|official\s+artist\s+channel|verified\s+channel|verified|more\s+options|sponsored|advertisement|ad\b|subscribers?|subscribe)\b"""), "")
            title = title.replace(Regex("""(?i)\b#\w+\b"""), "")

            // 8. Remove explicit channel suffix: "by <Channel Company>"
            title = title.replace(Regex("""(?i)\s+by\s+(?:tips\s+official|[a-z0-9\s]+(?:official|records|music|vevo|channel|tv|studio|series|films?|entertainment))\b.*"""), "")

            // 9. Remove trailing separator segments (e.g. " · Tips Official", " | Tips Official", " • Channel Name")
            title = title.replace(Regex("""(?i)\s*[·•|]\s*[^·•|]+$"""), "")

            // 10. Clean up whitespace and edge punctuation
            title = title.replace(Regex("""\s+"""), " ").trim()
            title = title.replace(Regex("""^[\s,·•|:.-]+|[\s,·•|:.-]+$"""), "").trim()

            return title
        }

        fun extractVideoId(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val match = Regex("""(?i)(?:v=|\/shorts\/|\/embed\/|youtu\.be\/|\/v\/)([a-zA-Z0-9_-]{11})""").find(url)
            return match?.groupValues?.getOrNull(1)
        }

        fun cleanYouTubeTitle(raw: String): String = cleanYouTubeVideoTitle(raw)

        var latestIncomingMessage: IncomingMessage? = null
    }

    data class IncomingMessage(
        val sender: String,
        val text: String,
        val isVoiceNote: Boolean,
        val packageName: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class WhatsAppSendResult {
        SENT,
        WHATSAPP_NOT_OPEN,
        MESSAGE_ENTRY_FAILED,
        SEND_BUTTON_NOT_FOUND,
        SEND_FAILED,
        VERIFICATION_FAILED
    }

    // ----------------------------------------------------
    // VOICE OPTION SELECTION STATE (Reusable layer)
    // ----------------------------------------------------
    private data class VoiceOption(
        val node: AccessibilityNodeInfo,
        val label: String
    )

    data class YouTubeVideoCandidate(
        val title: String,
        val videoId: String? = null,
        val watchUrl: String? = null,
        val actionableNode: AccessibilityNodeInfo? = null,
        val videoUrl: String? = null,
        val sourceNode: AccessibilityNodeInfo? = null,
        val rawText: String = "",
        val rawDesc: String = "",
        val confidence: Int = 0,
        val insideResultContainer: Boolean = false,
        val hasThumbnail: Boolean = false
    )

    data class YouTubeSelectionState(
        val results: List<YouTubeVideoCandidate>,
        var selectedIndex: Int = 0,
        val query: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val packageName: String = "com.google.android.youtube",
        val screenType: String = "SEARCH_RESULTS"
    )

    class DiagnosticCollector {
        var shortsRejected: Int = 0
        var nonVideoRejected: Int = 0
        var duplicatesRemoved: Int = 0
    }

    private val voiceOptions = LinkedHashMap<Int, VoiceOption>()
    private var optionsContext: String = OPTIONS_CONTEXT_NONE
    private var optionsSourcePackage: String = ""

    private var currentPlayingVideoIndex = 0
    private var currentFocusNodeIndex = -1
    private var lastVolumeKeyPressTime = 0L

    // ----------------------------------------------------
    // INCOMING WHATSAPP CALL SESSION
    // ----------------------------------------------------
    private val callFlow = WhatsAppCallFlow()
    private val callAudioSession by lazy { IncomingCallAudioSession(AndroidRingtoneAudioGateway(this)) }
    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_HAPTIC
        info.notificationTimeout = 100
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
    }

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return false
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                val now = System.currentTimeMillis()
                if (now - lastVolumeKeyPressTime < 600L || hasActiveVoiceOptions()) {
                    lastVolumeKeyPressTime = 0L
                    AndroidVoiceService.activeInstance?.deviceController?.triggerHaptic(HapticFeedbackType.START_LISTENING)
                    AndroidVoiceService.startListeningGlobally()
                    return true
                }
                lastVolumeKeyPressTime = now
            } else if (keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                AndroidVoiceService.activeInstance?.deviceController?.triggerHaptic(HapticFeedbackType.START_LISTENING)
                AndroidVoiceService.startListeningGlobally()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""

        // Drop stale voice options ONLY when the user navigates away from the app (not inside YouTube or BlindAI)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            voiceOptions.isNotEmpty() &&
            pkg.isNotBlank() &&
            !pkg.contains("youtube") &&
            pkg != optionsSourcePackage &&
            pkg != packageName
        ) {
            clearVoiceOptions()
        }

        // YouTube Auto-Ad-Skipper & Background Voice for Blind Accessibility
        if (pkg.contains("youtube")) {
            try {
                BackgroundVoiceService.start(this)
            } catch (_: Exception) {}

            val root = rootInActiveWindow
            if (root != null) {
                val skipAdNode = findSkipAdNode(root)
                if (skipAdNode != null && skipAdNode.isEnabled) {
                    performClickOnNode(skipAdNode)
                    AndroidVoiceService.speakGlobally("Ad skipped.")
                }
            }
        }

        // WhatsApp Incoming Call Detection (Window / Content Change)
        if (isWhatsAppPackage(pkg)) {
            val root = rootInActiveWindow
            if (root != null && isWhatsAppIncomingCallScreen(root)) {
                if (DeviceController.currentCallState != CallState.INCOMING_WHATSAPP_CALL) {
                    val rawCaller = extractWhatsAppCallerName(root)
                    val isVideo = hasVideoCallIndicator(root)
                    handleWhatsAppIncomingCallDetected(rawCaller, isVideo)
                }
            } else if (DeviceController.currentCallState == CallState.INCOMING_WHATSAPP_CALL) {
                if (root != null && hasActiveWhatsAppCallControls(root)) {
                    DeviceController.currentCallState = CallState.ACTIVE_WHATSAPP_CALL
                    finishIncomingCallSession(WhatsAppCallPhase.ANSWERED)
                } else if (root != null && !isWhatsAppIncomingCallScreen(root)) {
                    DeviceController.currentCallState = CallState.IDLE
                    DeviceController.currentWhatsAppCaller = ""
                    finishIncomingCallSession(WhatsAppCallPhase.CALL_ENDED)
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            DeviceController.currentCallState == CallState.INCOMING_WHATSAPP_CALL &&
            pkg.isNotBlank()
        ) {
            // Focus left WhatsApp while a call was incoming: call was missed,
            // dismissed from the UI, or answered elsewhere. Restore audio.
            DeviceController.currentCallState = CallState.IDLE
            DeviceController.currentWhatsAppCaller = ""
            finishIncomingCallSession(WhatsAppCallPhase.CALL_ENDED)
        }

        // Handle incoming notifications (e.g. WhatsApp, SMS)
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: notification?.extras?.getCharSequence("android.conversationTitle")?.toString()
                ?: ""
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""
            val rawTexts = event.text.joinToString(" ")

            if (isWhatsAppPackage(pkg)) {
                val lower = (title + " " + body + " " + rawTexts).lowercase()
                if (lower.contains("incoming voice call") || lower.contains("incoming video call") ||
                    lower.contains("whatsapp call") || lower.contains("incoming call")) {
                    val isVideo = lower.contains("video")
                    val caller = extractCallerFromNotificationText(if (title.isNotBlank()) title else rawTexts)
                    if (DeviceController.currentCallState != CallState.INCOMING_WHATSAPP_CALL) {
                        handleWhatsAppIncomingCallDetected(caller, isVideo)
                    }
                    return
                }
            }

            if (isWhatsAppPackage(pkg) || pkg.contains("messaging") || pkg.contains("sms") || pkg.contains("mms")) {
                handleIncomingMessageNotification(pkg, title, body, rawTexts)
            }
        }
    }

    private fun handleIncomingMessageNotification(
        pkg: String,
        rawTitle: String,
        rawBody: String,
        fallbackText: String
    ) {
        val cleanTitle = rawTitle.trim()
        var cleanBody = rawBody.trim()
        if (cleanBody.isBlank()) cleanBody = fallbackText.trim()

        // Filter out system / noise / status notifications
        val lowerBody = cleanBody.lowercase()
        if (lowerBody.contains("whatsapp web is currently active") ||
            lowerBody.contains("checking for new messages") ||
            lowerBody.contains("backup in progress") ||
            lowerBody.contains("messages are end-to-end encrypted") ||
            lowerBody == "whatsapp" ||
            lowerBody == "messages" ||
            cleanBody.isBlank()
        ) {
            return
        }

        // Determine Sender and Message Text
        var sender = cleanTitle
        var messageText = cleanBody

        if (cleanTitle.equals("WhatsApp", ignoreCase = true) || cleanTitle.equals("Messages", ignoreCase = true) || cleanTitle.isBlank()) {
            if (cleanBody.contains(": ")) {
                sender = cleanBody.substringBefore(": ").trim()
                messageText = cleanBody.substringAfter(": ").trim()
            } else {
                sender = "Someone"
            }
        } else if (cleanBody.startsWith("$cleanTitle: ", ignoreCase = true)) {
            messageText = cleanBody.removePrefix("$cleanTitle: ").removePrefix("$cleanTitle:").trim()
        }

        val isVoiceNote = messageText.contains("Voice message", ignoreCase = true) ||
                messageText.contains("Audio", ignoreCase = true) ||
                messageText.contains("PTT-", ignoreCase = true)

        val incoming = IncomingMessage(
            sender = sender,
            text = messageText,
            isVoiceNote = isVoiceNote,
            packageName = pkg
        )
        latestIncomingMessage = incoming

        val announcement = if (isVoiceNote) {
            "New voice message from $sender. Say play voice note or reply."
        } else {
            "New message from $sender: '$messageText'. Say reply to answer."
        }

        AndroidVoiceService.speakGlobally(announcement)
    }

    fun playLatestWhatsAppVoiceNote(): String {
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppPackage(root.packageName?.toString() ?: "")) {
            return "Please open WhatsApp to play the voice note."
        }
        val playBtn = findVoiceNotePlayButton(root)
        return if (playBtn != null && performClickOnNode(playBtn)) {
            "Playing voice note."
        } else {
            "Could not find any voice note on screen to play."
        }
    }

    private fun findVoiceNotePlayButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (desc.contains("play voice message") || desc.contains("play audio") || desc.contains("play button") ||
            viewId.contains("play") || viewId.contains("voice_message") || viewId.contains("audio")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val match = findVoiceNotePlayButton(child)
                if (match != null) return match
            }
        }
        return null
    }

    // ----------------------------------------------------
    // REUSABLE VOICE OPTION SELECTION & PERSISTENT YOUTUBE STATE
    // ----------------------------------------------------
    fun hasActiveVoiceOptions(): Boolean {
        val ytState = youTubeSelectionState
        if (ytState != null && ytState.results.isNotEmpty()) {
            return true
        }
        return voiceOptions.isNotEmpty() || isYouTubeActive()
    }

    fun isYouTubeActive(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.packageName?.contains("youtube") == true
    }

    @Suppress("DEPRECATION")
    fun clearVoiceOptions() {
        for (option in voiceOptions.values) {
            try {
                option.node.recycle()
            } catch (_: Exception) {
            }
        }
        voiceOptions.clear()
        optionsContext = OPTIONS_CONTEXT_NONE
        optionsSourcePackage = ""
    }

    private fun publishVoiceOptions(
        options: List<Pair<AccessibilityNodeInfo, String>>,
        context: String,
        sourcePackage: String
    ) {
        clearVoiceOptions()
        options.take(MAX_VOICE_OPTIONS).forEachIndexed { i, (node, label) ->
            voiceOptions[i + 1] = VoiceOption(node, label)
        }
        optionsContext = context
        optionsSourcePackage = sourcePackage
    }

    private fun availableOptionsPhrase(): String {
        val count = voiceOptions.size
        return when (count) {
            1 -> "option 1"
            2 -> "option 1 or 2"
            3 -> "option 1, 2, or 3"
            4 -> "option 1, 2, 3, or 4"
            5 -> "option 1, 2, 3, 4, or 5"
            else -> "option 1 or 2"
        }
    }

    private fun numberWord(index: Int): String {
        return when (index) {
            1 -> "one"
            2 -> "two"
            3 -> "three"
            4 -> "four"
            5 -> "five"
            6 -> "six"
            7 -> "seven"
            8 -> "eight"
            9 -> "nine"
            10 -> "ten"
            else -> "$index"
        }
    }

    private fun buildOptionsAnnouncement(noun: String): String {
        val count = voiceOptions.size
        val nounPlural = if (noun.contains("video", ignoreCase = true) || noun.contains("result", ignoreCase = true)) {
            if (count == 1) "video" else "videos"
        } else {
            if (count == 1) "option" else "options"
        }
        val sb = StringBuilder("I found $count $nounPlural. ")
        for (i in 1..count) {
            val label = voiceOptions[i]?.label ?: continue
            sb.append("Option $i: $label. ")
        }
        sb.append("Say an option number to play it.")
        return sb.toString().trim()
    }

    fun buildYouTubeAnnouncement(candidates: List<YouTubeVideoCandidate>): String {
        val count = candidates.size
        val nounPlural = if (count == 1) "video" else "videos"
        val sb = StringBuilder("I found $count $nounPlural.\n\n")
        candidates.forEachIndexed { i, c ->
            sb.append("Option ${i + 1}: ${c.title}.\n")
        }
        sb.append("\nSay an option number to play it.")
        return sb.toString().trim()
    }

    suspend fun activateVoiceOption(index: Int): String {
        val ytState = youTubeSelectionState
        Log.d(
            "BlindAI_YT_OPTION",
            """
            SELECTION_STATE
            exists=${ytState != null}
            count=${ytState?.results?.size ?: 0}
            titles=${ytState?.results?.joinToString(", ") { it.title } ?: "none"}
            """.trimIndent()
        )

        // 1. Persistent YouTube Selection State (survives mic stop, TTS completion, screen changes)
        if (ytState != null && ytState.results.isNotEmpty()) {
            val targetIdx = index - 1
            if (targetIdx !in ytState.results.indices) {
                return "Option $index is not available. I found ${ytState.results.size} videos."
            }

            val targetCandidate = ytState.results[targetIdx]
            ytState.selectedIndex = targetIdx
            val title = targetCandidate.title

            // Ensure YouTube is in foreground
            ensureYouTubeForeground()

            // Wait for active YouTube window (up to 2500ms)
            val root = waitForYouTubeRoot(2500L)
            if (root == null) {
                return "YouTube is no longer open."
            }

            // Click target candidate using logical identity matching & fresh live hierarchy scan
            val clicked = if (root != null) performClickOnTargetCandidate(root, targetCandidate, targetIdx) else false
            if (clicked) {
                return confirmYouTubePlayback(index, title)
            }

            // Fallback: Direct Intent Launch to play the video if accessibility click did not start it
            val videoUrl = targetCandidate.watchUrl ?: targetCandidate.videoUrl ?: (if (!targetCandidate.videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=${targetCandidate.videoId}" else null)
            if (!videoUrl.isNullOrBlank()) {
                try {
                    val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(playIntent)
                    return confirmYouTubePlayback(index, title)
                } catch (_: Exception) {
                    try {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(webIntent)
                        return confirmYouTubePlayback(index, title)
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    val searchPlayIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(title)}")).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(searchPlayIntent)
                    return confirmYouTubePlayback(index, title)
                } catch (_: Exception) {}
            }

            return confirmYouTubePlayback(index, title)
        }

        // 2. Legacy voiceOptions fallback
        if (voiceOptions.isNotEmpty()) {
            val option = voiceOptions[index]
                ?: return "Option $index is not available. Please say ${availableOptionsPhrase()}."

            val label = option.label
            val root = rootInActiveWindow
            var target: AccessibilityNodeInfo? = if (refreshSafely(option.node)) option.node else null
            if (target == null && root != null) {
                target = findNodeByLabel(root, label)
            }
            if (target == null) {
                clearVoiceOptions()
                return "That option is no longer on screen. Please try again."
            }

            val clicked = performClickOnNode(target)
            clearVoiceOptions()

            if (!clicked) {
                return "Found $label but could not select it. Please try again."
            }

            return "Selected option $index: $label."
        }

        // 3. Live search results on screen without saved state
        val liveRoot = rootInActiveWindow
        if (liveRoot != null && liveRoot.packageName?.contains("youtube") == true) {
            val freshCandidates = discoverAndFilterYouTubeCandidates(liveRoot)
            val targetIdx = index - 1
            if (targetIdx in freshCandidates.indices) {
                val candidate = freshCandidates[targetIdx]
                if (performClickOnCandidate(candidate)) {
                    return confirmYouTubePlayback(index, candidate.title)
                }
                return "I found that video, but I couldn't open it."
            }
        }

        return "I don't have any YouTube options available. Please search YouTube again."
    }

    private fun performClickOnTargetCandidate(
        root: AccessibilityNodeInfo,
        targetCandidate: YouTubeVideoCandidate,
        targetIdx: Int
    ): Boolean {
        var foundNode: AccessibilityNodeInfo? = null
        var matchReason = "none"

        // Step 1: Live Hierarchy Scan: Match by Video ID, URL, or Normalized Title
        val liveCandidates = discoverAndFilterYouTubeCandidates(root)
        if (liveCandidates.isNotEmpty()) {
            // Match A: By Video ID
            if (!targetCandidate.videoId.isNullOrBlank()) {
                val match = liveCandidates.firstOrNull { it.videoId == targetCandidate.videoId }
                if (match?.actionableNode != null) {
                    foundNode = match.actionableNode
                    matchReason = "VIDEO_ID_MATCH"
                }
            }

            // Match B: By URL
            if (foundNode == null && !targetCandidate.watchUrl.isNullOrBlank()) {
                val match = liveCandidates.firstOrNull { it.watchUrl == targetCandidate.watchUrl || it.videoUrl == targetCandidate.watchUrl }
                if (match?.actionableNode != null) {
                    foundNode = match.actionableNode
                    matchReason = "WATCH_URL_MATCH"
                }
            }

            // Match C: By Normalized Title
            if (foundNode == null) {
                val normTarget = normalizeForDeduplication(targetCandidate.title)
                val match = liveCandidates.firstOrNull { normalizeForDeduplication(it.title) == normTarget }
                if (match?.actionableNode != null) {
                    foundNode = match.actionableNode
                    matchReason = "NORMALIZED_TITLE_MATCH"
                }
            }

            // Match D: By Target Index Position in fresh candidates
            if (foundNode == null && targetIdx in liveCandidates.indices) {
                val candidateAtPos = liveCandidates[targetIdx]
                foundNode = candidateAtPos.actionableNode ?: candidateAtPos.sourceNode
                matchReason = "INDEX_POSITION_MATCH"
            }
        }

        // Step 2: Direct Tree Search by exact label
        if (foundNode == null) {
            val labelNode = findNodeByLabel(root, targetCandidate.title)
            if (labelNode != null) {
                foundNode = findBestActionableNode(labelNode)
                matchReason = "LABEL_SEARCH_MATCH"
            }
        }

        // Step 3: Fallback to candidate's stored node with refresh
        if (foundNode == null && targetCandidate.actionableNode != null) {
            if (refreshSafely(targetCandidate.actionableNode)) {
                foundNode = targetCandidate.actionableNode
                matchReason = "STORED_NODE_REFRESHED"
            }
        }

        val actionable = foundNode != null && isClickableOrHasClickAction(foundNode)
        Log.d(
            "BlindAI_YT_OPTION",
            """
            OPTION_RESOLVED
            index=${targetIdx + 1}
            title=${targetCandidate.title}
            videoId=${targetCandidate.videoId ?: targetCandidate.watchUrl ?: targetCandidate.videoUrl ?: "none"}
            freshNodeFound=${foundNode != null}
            actionable=$actionable
            """.trimIndent()
        )

        val success = if (foundNode != null) performClickOnNode(foundNode) else false
        Log.d(
            "BlindAI_YT_OPTION",
            """
            OPTION_CLICK_RESULT
            success=$success
            """.trimIndent()
        )
        return success
    }

    private fun ensureYouTubeForeground() {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.contains("youtube") != true) {
            try {
                val intent = packageManager?.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                if (intent != null) {
                    startActivity(intent)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun waitForYouTubeRoot(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.contains("youtube") == true) {
                return root
            }
            delay(150L)
        }
        return rootInActiveWindow
    }

    private fun refreshSafely(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.refresh()
        } catch (_: Exception) {
            false
        }
    }

    private fun findNodeByLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val matches = (text.isNotBlank() && (label.contains(text, ignoreCase = true) || text.contains(label, ignoreCase = true))) ||
                (desc.isNotBlank() && (label.contains(desc, ignoreCase = true) || desc.contains(label, ignoreCase = true)))
        if (matches && (node.isClickable || node.parent?.isClickable == true || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findNodeByLabel(node.getChild(i), label)
            if (found != null) return found
        }
        return null
    }

    /**
     * Collects meaningful clickable options from the current screen and speaks them.
     * Reusable for any Android app that exposes accessible clickable elements.
     */
    fun collectAndSpeakGenericOptions(): String {
        val root = rootInActiveWindow
            ?: return "Accessibility permission is required for this action."

        val collected = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collectMeaningfulOptions(root, collected)

        if (collected.isEmpty()) {
            return "I couldn't find any selectable items on this screen."
        }

        publishVoiceOptions(collected, OPTIONS_CONTEXT_GENERIC, root.packageName?.toString() ?: "")
        return buildOptionsAnnouncement("options")
    }

    private fun collectMeaningfulOptions(
        node: AccessibilityNodeInfo?,
        list: MutableList<Pair<AccessibilityNodeInfo, String>>
    ) {
        if (node == null || list.size >= MAX_VOICE_OPTIONS) return
        if (!node.isVisibleToUser) return

        if (node.isClickable && node.isEnabled) {
            val label = cleanOptionLabel(rawNodeLabel(node))
            if (isMeaningfulOptionLabel(label) && list.none { it.second.equals(label, ignoreCase = true) }) {
                list.add(node to label)
            }
        }

        for (i in 0 until node.childCount) {
            collectMeaningfulOptions(node.getChild(i), list)
        }
    }

    private fun rawNodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) return text
        return node.contentDescription?.toString()?.trim() ?: ""
    }

    private fun cleanOptionLabel(label: String): String {
        val collapsed = label.replace("\\s+".toRegex(), " ").trim()
        return if (collapsed.length > 90) collapsed.take(90) else collapsed
    }

    private fun isMeaningfulOptionLabel(label: String): Boolean {
        if (label.length < 3) return false
        if (!label.any { it.isLetter() }) return false
        val noise = setOf("navigate up", "more options", "double tap to activate", "overflow menu")
        return label.lowercase() !in noise
    }

    fun extractVideoTitle(node: AccessibilityNodeInfo): String {
        val titleFromId = findTitleInNode(node)
        if (titleFromId.isNotBlank() && titleFromId.length >= 3) {
            val cleaned = cleanYouTubeTitle(titleFromId)
            if (cleaned.isNotBlank()) return cleaned
        }

        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank() && text.length >= 3) {
            val cleaned = cleanYouTubeTitle(text)
            if (cleaned.isNotBlank()) return cleaned
        }

        val desc = node.contentDescription?.toString()?.trim() ?: ""
        return cleanYouTubeTitle(desc)
    }

    private fun findTitleInNode(node: AccessibilityNodeInfo): String {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        if ((viewId.contains("video_title") || viewId.contains("title")) && text.isNotBlank() && !text.equals("youtube", ignoreCase = true)) {
            return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTitleInNode(child)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    // ----------------------------------------------------
    // YOUTUBE: SEARCH, RESULTS AS VOICE OPTIONS, PLAYBACK
    // ----------------------------------------------------
    private fun launchYouTubeSearch(query: String): Boolean {
        // 1. ACTION_SEARCH with query extras (standard across YouTube app releases)
        try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(searchIntent)
            return true
        } catch (_: Exception) {}

        // 2. ACTION_VIEW with YouTube Search results Uri
        try {
            val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return true
        } catch (_: Exception) {}

        // 3. Fallback to generic browser search
        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(webIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Voice-first YouTube search: opens results, waits for the accessibility tree,
     * extracts ONLY valid regular video entries, assigns voice options and speaks pure titles.
     */
    suspend fun searchYouTubeAndCollectOptions(query: String): String {
        // Reset state for new search
        youTubeSelectionState = null
        clearVoiceOptions()

        if (!launchYouTubeSearch(query)) {
            return "Could not start YouTube search."
        }

        val deadline = System.currentTimeMillis() + 12000L
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                Log.d(
                    "BlindAI_YT_TREE",
                    """
                    ROOT_RECEIVED
                    packageName=${root.packageName}
                    className=${root.className}
                    childCount=${root.childCount}
                    """.trimIndent()
                )
            } else {
                Log.w("BlindAI_YT_TREE", "ROOT_NULL")
            }

            if (root != null && root.packageName?.contains("youtube") == true) {
                val diag = DiagnosticCollector()
                val raw = discoverYouTubeCandidates(root)
                val candidates = filterYouTubeCandidates(raw, diag)
                if (candidates.isNotEmpty()) {
                    currentPlayingVideoIndex = 0

                    // 1. Save exact list to persistent state
                    val newState = YouTubeSelectionState(
                        results = candidates,
                        selectedIndex = 0,
                        query = query,
                        timestamp = System.currentTimeMillis(),
                        packageName = root.packageName?.toString() ?: "com.google.android.youtube"
                    )
                    youTubeSelectionState = newState

                    // 2. Publish to legacy voiceOptions map
                    val options = candidates.mapNotNull { if (it.actionableNode != null) it.actionableNode to it.title else null }
                    if (options.isNotEmpty()) {
                        publishVoiceOptions(
                            options,
                            OPTIONS_CONTEXT_YOUTUBE_RESULTS,
                            root.packageName?.toString() ?: ""
                        )
                    }

                    Log.d(
                        "BlindAI_YT_STATE",
                        """
                        SELECTION_CREATED
                        count=${candidates.size}
                        query=$query
                        titles=${candidates.joinToString(", ") { it.title }}
                        """.trimIndent()
                    )

                    val announcement = buildYouTubeAnnouncement(candidates)
                    Log.d("BlindAI_YT", "YOUTUBE_ANNOUNCEMENT\ncandidateCount=${candidates.size}\nannouncement=\"${announcement.replace("\n", " ")}\"")

                    // 3. Spoken announcement generated from the SAME candidates list
                    return announcement
                }
            }
            delay(350L)
        }
        Log.w("BlindAI_YT", "YOUTUBE_SELECTION_MISSING\nreason=TIMEOUT_NO_CANDIDATES_FOUND")
        return "I couldn't find any regular videos."
    }

    private suspend fun confirmYouTubePlayback(index: Int, label: String): String {
        val cleanTitle = cleanYouTubeTitle(label)
        val deadline = System.currentTimeMillis() + 6000L
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.contains("youtube") == true && (isYouTubePlayerScreen(root) || hasPlayingVideo(root))) {
                ensurePlaybackStarted(root)
                return "Playing $cleanTitle."
            }
            delay(500L)
        }
        return "Playing $cleanTitle."
    }

    private fun isYouTubePlayerScreen(root: AccessibilityNodeInfo): Boolean {
        if (findPlaybackControlNode(root) != null) return true
        return hasViewIdContaining(root, "player") || hasViewIdContaining(root, "watch")
    }

    private fun hasPlayingVideo(root: AccessibilityNodeInfo): Boolean {
        return findPlaybackControlNode(root) != null ||
                hasViewIdContaining(root, "player") ||
                hasViewIdContaining(root, "watch")
    }

    private fun hasViewIdContaining(node: AccessibilityNodeInfo?, fragment: String): Boolean {
        if (node == null) return false
        if (node.viewIdResourceName?.contains(fragment) == true) return true
        for (i in 0 until node.childCount) {
            if (hasViewIdContaining(node.getChild(i), fragment)) return true
        }
        return false
    }

    private fun findPlaybackControlNode(node: AccessibilityNodeInfo?, targetAction: String? = null): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isPause = desc == "pause" || desc.contains("pause video") || text == "pause" || viewId.contains("pause_button")
        val isPlay = desc == "play" || desc.contains("play video") || text == "play" || viewId.contains("play_button")

        if (targetAction == "pause" && isPause && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }
        if (targetAction == "play" && isPlay && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }
        if (targetAction == null && (isPause || isPlay) && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findPlaybackControlNode(node.getChild(i), targetAction)
            if (found != null) return found
        }
        return null
    }

    private fun ensurePlaybackStarted(root: AccessibilityNodeInfo) {
        val control = findPlaybackControlNode(root, "play")
        if (control != null) {
            performClickOnNode(control)
        }
    }

    private fun sendMediaKeyEvent(keyCode: Int): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null) {
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun revealYouTubePlayerControls() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val displayMetrics = resources.displayMetrics
                val x = (displayMetrics.widthPixels / 2).toFloat()
                val y = (displayMetrics.heightPixels / 4).toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(x, y)
                }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            }
        } catch (_: Exception) {}
    }

    fun pauseMediaPlayback(): String {
        val root = rootInActiveWindow ?: return "I couldn't pause the video."

        val pauseControl = findPlaybackControlNode(root, "pause") ?: findClickableNode(root, "pause") ?: findClickableNode(root, "pause video")
        if (pauseControl != null) {
            return if (performClickOnNode(pauseControl)) "Video paused." else "I couldn't pause the video."
        }

        val playControl = findPlaybackControlNode(root, "play") ?: findClickableNode(root, "play")
        if (playControl != null) {
            return "Video is already paused."
        }

        sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        revealYouTubePlayerControls()

        val rootAfter = rootInActiveWindow
        if (rootAfter != null) {
            val controlAfter = findPlaybackControlNode(rootAfter, "pause")
            if (controlAfter != null && performClickOnNode(controlAfter)) {
                return "Video paused."
            }
        }
        return "Video paused."
    }

    fun resumeMediaPlayback(): String {
        val root = rootInActiveWindow ?: return "I couldn't resume the video."

        val playControl = findPlaybackControlNode(root, "play") ?: findClickableNode(root, "play") ?: findClickableNode(root, "play video")
        if (playControl != null) {
            return if (performClickOnNode(playControl)) "Video resumed." else "I couldn't resume the video."
        }

        val pauseControl = findPlaybackControlNode(root, "pause") ?: findClickableNode(root, "pause")
        if (pauseControl != null) {
            return "Video is already playing."
        }

        sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        revealYouTubePlayerControls()

        val rootAfter = rootInActiveWindow
        if (rootAfter != null) {
            val controlAfter = findPlaybackControlNode(rootAfter, "play")
            if (controlAfter != null && performClickOnNode(controlAfter)) {
                return "Video resumed."
            }
        }
        return "Video resumed."
    }

    fun toggleMediaPlayPause(): String {
        val root = rootInActiveWindow ?: return "No playback control visible on screen."
        val control = findPlaybackControlNode(root)
            ?: findClickableNode(root, "pause")
            ?: findClickableNode(root, "play")

        if (control != null) {
            val isPause = control.contentDescription?.toString()?.lowercase()?.contains("pause") == true
            val success = performClickOnNode(control)
            return if (success) {
                if (isPause) "Video paused." else "Video resumed."
            } else {
                "Found playback control but could not click."
            }
        } else {
            sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            revealYouTubePlayerControls()
            return "Toggled playback."
        }
    }

    fun stopMediaPlayback(): String {
        val root = rootInActiveWindow ?: return "Playback stopped."
        val control = findPlaybackControlNode(root, "pause") ?: findClickableNode(root, "pause")
        if (control != null) {
            performClickOnNode(control)
        }
        sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
        return "Playback stopped."
    }

    suspend fun playNextYouTubeVideo(): String {
        val root = rootInActiveWindow
        val nextBtn = if (root != null) findNextButtonInPlayer(root) else null
        if (nextBtn != null && performClickOnNode(nextBtn)) {
            return "Playing next video."
        }

        var cards = findYouTubeVideoCards(rootInActiveWindow)
        if (cards.isEmpty()) {
            cards = returnToYouTubeResults()
        }
        if (cards.isNotEmpty()) {
            val nextIdx = currentPlayingVideoIndex + 1
            if (nextIdx in cards.indices) {
                return clickYouTubeCard(cards[nextIdx], nextIdx, "next")
            }
        }

        sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        return "Playing next video."
    }

    suspend fun playPreviousYouTubeVideo(): String {
        val root = rootInActiveWindow
        val prevBtn = if (root != null) findPreviousButtonInPlayer(root) else null
        if (prevBtn != null && performClickOnNode(prevBtn)) {
            return "Playing previous video."
        }

        var cards = findYouTubeVideoCards(rootInActiveWindow)
        if (cards.isEmpty()) {
            cards = returnToYouTubeResults()
        }
        if (cards.isNotEmpty()) {
            val prevIdx = currentPlayingVideoIndex - 1
            if (prevIdx in cards.indices) {
                return clickYouTubeCard(cards[prevIdx], prevIdx, "previous")
            }
        }

        sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return "Playing previous video."
    }

    private fun findNextButtonInPlayer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if ((desc.contains("next video") || desc == "next" || viewId.contains("next")) &&
            (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }
        for (i in 0 until node.childCount) {
            val match = findNextButtonInPlayer(node.getChild(i))
            if (match != null) return match
        }
        return null
    }

    private fun findPreviousButtonInPlayer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if ((desc.contains("previous video") || desc == "previous" || viewId.contains("previous") || viewId.contains("prev")) &&
            (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }
        for (i in 0 until node.childCount) {
            val match = findPreviousButtonInPlayer(node.getChild(i))
            if (match != null) return match
        }
        return null
    }

    private suspend fun returnToYouTubeResults(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.contains("youtube") != true) return emptyList()

        performGlobalAction(GLOBAL_ACTION_BACK)
        val deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline) {
            delay(500L)
            val current = rootInActiveWindow ?: continue
            if (current.packageName?.contains("youtube") != true) continue
            val cards = findYouTubeVideoCards(current)
            if (cards.isNotEmpty()) return cards
        }
        return emptyList()
    }

    private fun clickYouTubeCard(card: AccessibilityNodeInfo, index: Int, kind: String): String {
        currentPlayingVideoIndex = index
        val title = cleanYouTubeTitle(rawNodeLabel(card))
        val success = performClickOnNode(card)
        return if (success) {
            if (kind == "next") {
                "Playing next video: $title."
            } else if (kind == "previous") {
                "Playing previous video: $title."
            } else {
                "Playing option ${index + 1}: $title."
            }
        } else {
            "Could not open $title. Please try again."
        }
    }

    fun isShortsNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val label = rawNodeLabel(node)
        if (isYouTubeShortsTitleOrUrl(label)) return true

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("reel") || viewId.contains("shorts_cell") || viewId.contains("shorts_shelf") ||
            viewId.contains("shorts_video") || viewId.contains("shorts_container") || viewId.contains("shorts_compact") ||
            viewId.contains("shorts_grid") || viewId.contains("reel_recycler")) {
            return true
        }

        // Check ancestors for Shorts carousel / shelf container
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            val pViewId = parent.viewIdResourceName?.lowercase() ?: ""
            val pDesc = parent.contentDescription?.toString()?.lowercase() ?: ""
            if (pViewId.contains("shorts_shelf") || pViewId.contains("reel_shelf") ||
                pViewId.contains("shorts_container") || pViewId.contains("reel_recycler") ||
                pViewId.contains("shorts_grid") || pDesc.contains("shorts shelf") || pDesc.contains("shorts carousel")) {
                return true
            }
            parent = parent.parent
            depth++
        }

        // Check immediate children for "shorts" badge
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cViewId = child.viewIdResourceName?.lowercase() ?: ""
            val cText = child.text?.toString()?.trim()?.lowercase() ?: ""
            val cDesc = child.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            if (cViewId.contains("shorts_badge") || cViewId.contains("reel_badge") ||
                cText == "shorts" || cDesc == "shorts") {
                return true
            }
        }

        return false
    }

    // ----------------------------------------------------
    // YOUTUBE CANDIDATE DISCOVERY & FILTERING (Structural & Confidence Engine)
    // ----------------------------------------------------

    fun discoverYouTubeCandidates(root: AccessibilityNodeInfo?): List<YouTubeVideoCandidate> {
        if (root == null) return emptyList()
        val candidates = mutableListOf<YouTubeVideoCandidate>()

        // 1. Locate search results feed container or fallback to content root
        val resultsContainer = findResultsFeedContainer(root) ?: root

        // 2. Discover video result cards
        val cardNodes = mutableListOf<AccessibilityNodeInfo>()
        collectCandidateCards(resultsContainer, cardNodes)

        // 3. For each candidate card, validate and score
        for (card in cardNodes) {
            val candidate = evaluateCardCandidate(card, resultsContainer != root)
            if (candidate != null) {
                candidates.add(candidate)
            }
        }

        return candidates
    }

    private fun findResultsFeedContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val listContainers = mutableListOf<AccessibilityNodeInfo>()

        fun findContainers(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val cls = node.className?.toString()?.lowercase() ?: ""

            if (!isUiToolbarOrNavBar(node)) {
                if (viewId.contains("results_list") || viewId.contains("results_recycler") ||
                    viewId.contains("section_list") || viewId.contains("results_container") ||
                    viewId.contains("feed") ||
                    ((cls.contains("recyclerview") || cls.contains("listview")) && node.childCount >= 1)) {
                    listContainers.add(node)
                }
            }

            for (i in 0 until node.childCount) {
                findContainers(node.getChild(i))
            }
        }

        findContainers(root)
        return listContainers.maxByOrNull { it.childCount }
    }

    private fun collectCandidateCards(
        container: AccessibilityNodeInfo,
        cardList: MutableList<AccessibilityNodeInfo>
    ) {
        val cls = container.className?.toString()?.lowercase() ?: ""
        val isList = cls.contains("recyclerview") || cls.contains("listview") ||
                (container.viewIdResourceName?.lowercase()?.contains("results") == true)

        if (isList && container.childCount > 0) {
            for (i in 0 until container.childCount) {
                val child = container.getChild(i) ?: continue
                if (child.isVisibleToUser && !isUiToolbarOrNavBar(child)) {
                    cardList.add(child)
                }
            }
            return
        }

        val visited = HashSet<AccessibilityNodeInfo>()
        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser || cardList.size >= 30) return
            if (isUiToolbarOrNavBar(node)) return

            if (isVideoItemContainer(node) && !visited.contains(node)) {
                cardList.add(node)
                visited.add(node)
                return
            }

            for (i in 0 until node.childCount) {
                scan(node.getChild(i))
            }
        }
        scan(container)
    }

    private fun evaluateCardCandidate(
        card: AccessibilityNodeInfo,
        isInsideFeedContainer: Boolean
    ): YouTubeVideoCandidate? {
        val viewId = card.viewIdResourceName?.lowercase() ?: ""
        val cls = card.className?.toString() ?: ""

        // 1. Exclude top search bar, bottom nav bar, filter chips
        if (isUiToolbarOrNavBar(card)) {
            logCandidateDiagnostic(
                title = card.text?.toString() ?: "",
                url = null,
                className = cls,
                viewId = viewId,
                clickable = card.isClickable,
                hasThumbnail = false,
                hasActionClick = false,
                insideResultContainer = isInsideFeedContainer,
                shortsDetected = false,
                confidence = 0,
                accepted = false,
                reason = "UI_TOOLBAR_OR_NAV_BAR"
            )
            return null
        }

        // 2. Extract URL
        val url = extractUrlFromNode(card)
        if (url != null && !isValidYouTubeWatchUrl(url)) {
            logCandidateDiagnostic(
                title = card.text?.toString() ?: "",
                url = url,
                className = cls,
                viewId = viewId,
                clickable = card.isClickable,
                hasThumbnail = false,
                hasActionClick = false,
                insideResultContainer = isInsideFeedContainer,
                shortsDetected = url.contains("/shorts/"),
                confidence = 0,
                accepted = false,
                reason = "NON_WATCH_URL"
            )
            return null
        }
        val hasWatchUrl = url != null && isValidYouTubeWatchUrl(url)

        // 3. Shorts exclusion
        if (isShortsCard(card)) {
            val shortTitle = cleanYouTubeVideoTitle(card.text?.toString() ?: card.contentDescription?.toString() ?: "unknown")
            Log.d(
                "BlindAI_YT",
                """
                REJECTED SHORT:
                  title="$shortTitle"
                  reason=SHORTS
                """.trimIndent()
            )
            logCandidateDiagnostic(
                title = shortTitle,
                url = url,
                className = cls,
                viewId = viewId,
                clickable = card.isClickable,
                hasThumbnail = false,
                hasActionClick = false,
                insideResultContainer = isInsideFeedContainer,
                shortsDetected = true,
                confidence = 0,
                accepted = false,
                reason = "SHORTS_DETECTED"
            )
            return null
        }

        // 4. Extract and validate title
        val title = extractTitleFromCard(card)
        if (title.isBlank() || title.length < 3 || isUiNoiseOrStatusTitle(title)) {
            logCandidateDiagnostic(
                title = title.ifBlank { card.text?.toString() ?: "empty" },
                url = url,
                className = cls,
                viewId = viewId,
                clickable = card.isClickable,
                hasThumbnail = false,
                hasActionClick = false,
                insideResultContainer = isInsideFeedContainer,
                shortsDetected = false,
                confidence = 0,
                accepted = false,
                reason = "UI_STATUS_OR_EMPTY_TITLE"
            )
            return null
        }

        // 5. Check structural features
        val isVideoContainer = isVideoItemContainer(card) || isInsideFeedContainer
        val hasThumbnail = hasThumbnailChild(card)
        val actionNode = findBestActionableNode(card)
        val isActionable = isClickableOrHasClickAction(actionNode) || isClickableOrHasClickAction(card)
        val hasMetadata = hasVideoMetadata(card, title)

        // 6. Compute Confidence
        var confidence = 1 // +1 for valid title
        if (hasWatchUrl) confidence += 5
        if (isVideoContainer) confidence += 4
        if (hasThumbnail) confidence += 3
        if (isActionable) confidence += 3
        if (hasMetadata) confidence += 2

        if (confidence < 6) {
            logCandidateDiagnostic(
                title = title,
                url = url,
                className = cls,
                viewId = viewId,
                clickable = isActionable,
                hasThumbnail = hasThumbnail,
                hasActionClick = actionNode.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                insideResultContainer = isInsideFeedContainer,
                shortsDetected = false,
                confidence = confidence,
                accepted = false,
                reason = "LOW_CONFIDENCE_STANDALONE_TEXT"
            )
            return null
        }

        val meta = extractInternalMetadata(card)
        Log.d(
            "BlindAI_YT",
            """
            VIDEO CANDIDATE:
              title="$title"
              shorts=false
              channel="${meta.channel.ifBlank { "internal only" }}"
              views="${meta.views.ifBlank { "internal only" }}"
              duration="${meta.duration.ifBlank { "internal only" }}"
              uploadDate="${meta.uploadDate.ifBlank { "internal only" }}"
              sourceCard="$cls"
              accepted=true
            """.trimIndent()
        )

        logCandidateDiagnostic(
            title = title,
            url = url,
            className = cls,
            viewId = viewId,
            clickable = isActionable,
            hasThumbnail = hasThumbnail,
            hasActionClick = actionNode.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
            insideResultContainer = isInsideFeedContainer,
            shortsDetected = false,
            confidence = confidence,
            accepted = true,
            reason = "VIDEO_RESULT_CARD"
        )

        val videoId = extractVideoId(url)
        val watchUrl = if (hasWatchUrl) url else if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else null

        return YouTubeVideoCandidate(
            title = title,
            videoId = videoId,
            watchUrl = watchUrl,
            actionableNode = actionNode,
            videoUrl = watchUrl ?: url,
            sourceNode = card,
            rawText = card.text?.toString() ?: "",
            rawDesc = card.contentDescription?.toString() ?: "",
            confidence = confidence,
            insideResultContainer = isInsideFeedContainer,
            hasThumbnail = hasThumbnail
        )
    }

    private data class CandidateMetadata(
        val channel: String = "",
        val views: String = "",
        val duration: String = "",
        val uploadDate: String = ""
    )

    private fun extractInternalMetadata(card: AccessibilityNodeInfo): CandidateMetadata {
        val desc = card.contentDescription?.toString() ?: ""
        val textList = mutableListOf<String>()
        collectTextDescendants(card, textList)
        val combined = "$desc ${textList.joinToString(" ")}"

        val viewsMatch = Regex("""(?i)\b[\d,.]+\s*(?:[kmgtb]|million|billion|thousand)?\s*views?\b""").find(combined)?.value ?: ""
        val durationMatch = Regex("""(?i)\b\d{1,2}:\d{2}(?::\d{2})?\b|\b\d+\s+(?:hours?|minutes?|seconds?)\b""").find(combined)?.value ?: ""
        val dateMatch = Regex("""(?i)\b(?:streamed\s+)?\d+\s+(?:years?|months?|weeks?|days?|hours?|minutes?)\s+ago\b|\byesterday\b""").find(combined)?.value ?: ""
        val channelMatch = Regex("""(?i)\bby\s+([^\-·•|,]+)""").find(desc)?.groupValues?.getOrNull(1)?.trim() ?: ""

        return CandidateMetadata(
            channel = channelMatch,
            views = viewsMatch,
            duration = durationMatch,
            uploadDate = dateMatch
        )
    }

    fun isUiToolbarOrNavBar(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("search_edit_text") || viewId.contains("search_bar") ||
            viewId.contains("search_plate") || viewId.contains("clear_button") ||
            viewId.contains("navigation_bar") || viewId.contains("bottom_bar") ||
            viewId.contains("tab_bar") || viewId.contains("bottom_navigation") ||
            viewId.contains("filter_bar") || viewId.contains("chip_cloud") ||
            viewId.contains("filter_chip") || viewId.contains("category_chip")) {
            return true
        }
        var p = node.parent
        var d = 0
        while (p != null && d < 3) {
            val pViewId = p.viewIdResourceName?.lowercase() ?: ""
            if (pViewId.contains("search_bar") || pViewId.contains("navigation_bar") ||
                pViewId.contains("bottom_bar") || pViewId.contains("filter_bar") ||
                pViewId.contains("chip_cloud")) {
                return true
            }
            p = p.parent
            d++
        }
        return false
    }

    fun isUiNoiseOrStatusTitle(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        val exactMatches = setOf(
            "clear", "clear search", "clear query", "clear all",
            "new content available", "content available", "new videos available", "new video available",
            "refresh", "updated", "loading", "loading complete", "loading more", "no results", "no results found",
            "home", "shorts", "subscriptions", "library", "you", "explore", "search", "voice search", "search with your voice",
            "notifications", "cast", "settings", "history", "watch later", "sign in", "sign up", "account", "switch account",
            "search filters", "filter", "filters", "filter options", "sort by", "upload date", "duration", "type", "features",
            "search suggestions", "search suggestions available", "people also search for", "related searches", "people also watched",
            "navigate up", "back", "close", "more options", "menu", "overflow menu",
            "double tap to activate", "double-tap to activate", "turn on", "turn off", "dismiss", "got it",
            "subscribe", "subscribed", "subscribe to channel", "view channel", "visit channel", "official artist channel", "subscribers",
            "all", "music", "news", "recently uploaded", "watched", "unwatched", "live", "playlists", "posts"
        )
        if (lower in exactMatches) return true

        if (lower.startsWith("subscribe to ") || lower.startsWith("switch to ") || lower.startsWith("search for ")) {
            return true
        }

        if (lower.contains("new content available") || lower.contains("search suggestions") || lower.contains("related searches")) {
            return true
        }

        return false
    }

    fun isShortsCard(node: AccessibilityNodeInfo): Boolean {
        // 1. Direct label and URL check on the node
        val label = rawNodeLabel(node)
        if (isYouTubeShortsTitleOrUrl(label)) return true

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val combinedSelf = "$desc $text"
        if (combinedSelf.contains("/shorts/") || combinedSelf.contains("#shorts") ||
            combinedSelf.contains("play short") || combinedSelf.contains("shorts shelf") ||
            combinedSelf.contains("shorts carousel") || combinedSelf.contains("shorts video")) {
            return true
        }

        // 2. View ID checks on self
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("shorts") || viewId.contains("reel")) {
            return true
        }

        // 3. Recursive descendant tree check
        if (hasShortsInDescendants(node)) {
            return true
        }

        // 4. Ancestor and ancestor-sibling check (up to 8 levels)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            val pViewId = parent.viewIdResourceName?.lowercase() ?: ""
            val pDesc = parent.contentDescription?.toString()?.lowercase() ?: ""
            val pText = parent.text?.toString()?.lowercase() ?: ""
            if (pViewId.contains("shorts") || pViewId.contains("reel") ||
                pDesc.contains("shorts") || pText.contains("shorts") ||
                pDesc.contains("reel") || pText.contains("reel")) {
                return true
            }

            // Check if any sibling under this ancestor is a "Shorts" section header/shelf
            for (i in 0 until parent.childCount) {
                val sib = parent.getChild(i) ?: continue
                if (sib == node) continue
                val sViewId = sib.viewIdResourceName?.lowercase() ?: ""
                val sText = sib.text?.toString()?.trim()?.lowercase() ?: ""
                val sDesc = sib.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                if (sViewId.contains("shorts_shelf_header") || sViewId.contains("reel_shelf_header") ||
                    sText == "shorts" || sDesc == "shorts" ||
                    sText.contains("shorts shelf") || sDesc.contains("shorts shelf") ||
                    sText.contains("shorts carousel") || sDesc.contains("shorts carousel")) {
                    return true
                }
            }

            parent = parent.parent
            depth++
        }

        return false
    }

    private fun hasShortsInDescendants(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cViewId = child.viewIdResourceName?.lowercase() ?: ""
            val cText = child.text?.toString()?.trim()?.lowercase() ?: ""
            val cDesc = child.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            if (cViewId.contains("shorts") || cViewId.contains("reel") ||
                cText == "shorts" || cDesc == "shorts" ||
                cDesc.contains("play short") || cText.contains("play short") ||
                cDesc.contains("#shorts") || cText.contains("#shorts") ||
                cDesc.contains("/shorts/") || cText.contains("/shorts/")) {
                return true
            }

            if (hasShortsInDescendants(child)) {
                return true
            }
        }
        return false
    }

    private fun isVideoItemContainer(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("video_item") || viewId.contains("compact_video") ||
            viewId.contains("video_card") || viewId.contains("item_layout") ||
            viewId.contains("video_renderer") || viewId.contains("entry_point") ||
            viewId.contains("video_row") || viewId.contains("feed_item") ||
            viewId.contains("results_item") || viewId.contains("card_layout")) {
            return true
        }
        val parent = node.parent
        val pCls = parent?.className?.toString()?.lowercase() ?: ""
        val pViewId = parent?.viewIdResourceName?.lowercase() ?: ""
        if ((pCls.contains("recyclerview") || pCls.contains("listview") || pViewId.contains("results")) && node.childCount >= 2) {
            return true
        }
        return false
    }

    private fun hasThumbnailChild(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val cls = node.className?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (viewId.contains("thumbnail") || viewId.contains("poster") || viewId.contains("image_preview") || viewId.contains("video_image")) {
            return true
        }
        if (cls.contains("imageview") && !viewId.contains("icon") && !viewId.contains("button") && !viewId.contains("avatar") && !viewId.contains("menu")) {
            return true
        }
        if (desc.contains("thumbnail") || desc.contains("play video")) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasThumbnailChild(child)) return true
        }
        return false
    }

    private fun extractTitleFromCard(card: AccessibilityNodeInfo): String {
        // Priority 1: Check node with viewId containing "title" or "video_title"
        val bestTitle = findTitleByViewId(card)
        if (bestTitle.isNotBlank()) {
            val cleaned = cleanYouTubeVideoTitle(bestTitle)
            if (isValidExtractedTitle(cleaned)) {
                return cleaned
            }
        }

        // Priority 2: Inspect child TextViews in the card (before checking merged contentDescription)
        val textList = mutableListOf<String>()
        collectTextDescendants(card, textList)
        for (t in textList) {
            val trimmed = t.trim()
            if (trimmed.length in 3..250 && !isUiNoiseOrStatusTitle(trimmed) && !isMetadataOnly(trimmed) && !isChannelOnlyText(trimmed)) {
                val cleaned = cleanYouTubeVideoTitle(trimmed)
                if (isValidExtractedTitle(cleaned)) {
                    return cleaned
                }
            }
        }

        // Priority 3: Fall back to contentDescription on card/thumbnail if no separate TextViews exist
        val cardDesc = card.contentDescription?.toString()?.trim() ?: ""
        if (cardDesc.isNotBlank() && cardDesc.length in 3..300 && !isUiNoiseOrStatusTitle(cardDesc)) {
            val cleaned = cleanYouTubeVideoTitle(cardDesc)
            if (isValidExtractedTitle(cleaned)) {
                return cleaned
            }
        }

        return ""
    }

    private fun isValidExtractedTitle(title: String): Boolean {
        if (title.length < 3) return false
        if (isUiNoiseOrStatusTitle(title)) return false
        if (isMetadataOnly(title)) return false
        return title.any { it.isLetter() }
    }

    private fun isChannelOnlyText(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.startsWith("by ") || lower.contains("subscribers") ||
            lower.contains("official artist channel") || lower.contains("verified channel") ||
            lower == "subscribe" || lower == "subscribed") {
            return true
        }
        return false
    }

    private fun findTitleByViewId(node: AccessibilityNodeInfo): String {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if ((viewId.contains("video_title") || viewId.contains("title")) && (text.isNotBlank() || desc.isNotBlank())) {
            val raw = if (text.isNotBlank()) text else desc
            if (!isUiNoiseOrStatusTitle(raw) && !isMetadataOnly(raw)) {
                return raw
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTitleByViewId(child)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun collectTextDescendants(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) list.add(text)
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        if (desc.isNotBlank() && desc != text) list.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextDescendants(child, list)
        }
    }

    private fun isMetadataOnly(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return true

        // Clock timestamp: "4:32", "10:15", "1:02:33"
        if (lower.matches(Regex("""^\d{1,2}:\d{2}(?::\d{2})?$"""))) return true

        // Views: "50M views", "1.2K views", "1000 views", "12 million views"
        if (lower.matches(Regex("""^[\d,.]+\s*(?:[kmgtb]|million|billion|thousand)?\s*views?$"""))) return true

        // Upload date: "2 years ago", "streamed 5 days ago", "yesterday", "3 weeks ago"
        if (lower.matches(Regex("""^(?:streamed\s+)?\d+\s+(?:years?|months?|weeks?|days?|hours?|minutes?|seconds?)\s+ago$"""))) return true
        if (lower in setOf("yesterday", "just now", "live", "premiere", "hd", "cc", "4k", "new", "ad", "sponsored", "play video", "play short")) return true

        // Word duration: "4 minutes, 20 seconds", "10 minutes", "45 seconds"
        if (lower.matches(Regex("""^\d+\s+(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)(?:,\s*\d+\s+(?:minutes?|mins?|seconds?|secs?))?$"""))) return true

        // Channel / subscribe metadata
        if (lower in setOf("subscribe", "subscribed", "verified", "official artist channel", "verified channel", "verified artist")) return true
        if (lower.matches(Regex("""^[\d,.]+\s*(?:[kmgtb]|million)?\s*subscribers?$"""))) return true

        // Merged metadata line: e.g. "50M views • 2 years ago", "12M views · 4:32 · 2 years ago", "Tips Official • 50M views"
        val parts = lower.split(Regex("""[·•|\-]""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val allMetadata = parts.all { part ->
                part.matches(Regex("""^[\d,.]+\s*(?:[kmgtb]|million|billion|thousand)?\s*views?$""")) ||
                part.matches(Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")) ||
                part.matches(Regex("""^(?:streamed\s+)?\d+\s+(?:years?|months?|weeks?|days?|hours?|minutes?|seconds?)\s+ago$""")) ||
                part.matches(Regex("""^\d+\s+(?:hours?|hrs?|minutes?|mins?|seconds?|secs?).*""")) ||
                part in setOf("yesterday", "live", "hd", "cc", "4k", "new", "ad", "verified", "official artist channel", "subscribe", "subscribed")
            }
            if (allMetadata) return true
        }

        return false
    }

    private fun hasVideoMetadata(node: AccessibilityNodeInfo, text: String = ""): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$desc ${node.text?.toString()?.lowercase() ?: ""} $text"
        if (combined.contains("play video") || combined.contains("views") ||
            combined.contains("ago") || combined.contains("minute") ||
            combined.contains("second") || combined.contains("hour") ||
            combined.contains("duration") || Regex("""\b\d{1,2}:\d{2}\b""").containsMatchIn(combined)) {
            return true
        }
        return false
    }

    private fun extractUrlFromNode(node: AccessibilityNodeInfo): String? {
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val combined = "$desc $text"
        val match = Regex("""(?i)\b(https?://(?:www\.)?(?:youtube\.com|youtu\.be)\S+)""").find(combined)
        return match?.value
    }

    private fun logCandidateDiagnostic(
        title: String,
        url: String?,
        className: String,
        viewId: String,
        clickable: Boolean,
        hasThumbnail: Boolean,
        hasActionClick: Boolean,
        insideResultContainer: Boolean,
        shortsDetected: Boolean,
        confidence: Int,
        accepted: Boolean,
        reason: String
    ) {
        val status = if (accepted) "ACCEPTED" else "REJECTED"
        Log.d(
            "BlindAI_YT",
            """
            $status:
              title="$title"
              url="${url ?: "none"}"
              class="$className"
              viewId="$viewId"
              clickable=$clickable
              hasThumbnail=$hasThumbnail
              hasActionClick=$hasActionClick
              insideResultContainer=$insideResultContainer
              shortsDetected=$shortsDetected
              confidence=$confidence
              reason=$reason
            """.trimIndent()
        )
    }

    fun filterYouTubeCandidates(
        candidates: List<YouTubeVideoCandidate>,
        collector: DiagnosticCollector? = null
    ): List<YouTubeVideoCandidate> {
        val accepted = mutableListOf<YouTubeVideoCandidate>()
        val seenTitles = HashSet<String>()

        for (candidate in candidates) {
            // Stage 2.1: Shorts Exclusion
            if (isShortsCandidate(candidate)) {
                collector?.let { it.shortsRejected++ }
                Log.d(
                    "BlindAI_YT_CANDIDATE",
                    """
                    CANDIDATE_REJECTED
                    text=${candidate.title}
                    reason=SHORTS_DETECTED
                    """.trimIndent()
                )
                continue
            }

            // Stage 2.2: Non-Video UI & Status Exclusion
            if (isNonVideoCandidate(candidate)) {
                collector?.let { it.nonVideoRejected++ }
                Log.d(
                    "BlindAI_YT_CANDIDATE",
                    """
                    CANDIDATE_REJECTED
                    text=${candidate.title}
                    reason=NON_VIDEO_UI
                    """.trimIndent()
                )
                continue
            }

            // Stage 2.3: Deduplication
            val normKey = normalizeForDeduplication(candidate.title)
            if (normKey.length < 2 || !seenTitles.add(normKey)) {
                collector?.let { it.duplicatesRemoved++ }
                Log.d(
                    "BlindAI_YT_CANDIDATE",
                    """
                    CANDIDATE_REJECTED
                    text=${candidate.title}
                    reason=DUPLICATE_TITLE
                    """.trimIndent()
                )
                continue
            }

            accepted.add(candidate)
            val isAct = if (candidate.actionableNode != null) isClickableOrHasClickAction(candidate.actionableNode) else false
            Log.d(
                "BlindAI_YT_CANDIDATE",
                """
                CANDIDATE_ACCEPTED
                index=${accepted.size}
                title=${candidate.title}
                videoId=${candidate.videoId ?: "none"}
                watchUrl=${candidate.watchUrl ?: candidate.videoUrl ?: "none"}
                clickable=${candidate.actionableNode?.isClickable ?: false}
                actionable=$isAct
                """.trimIndent()
            )

            if (accepted.size >= MAX_VOICE_OPTIONS) {
                break
            }
        }

        val totalRejected = (collector?.nonVideoRejected ?: 0) + (collector?.shortsRejected ?: 0) + (collector?.duplicatesRemoved ?: 0)
        Log.d(
            "BlindAI_YT_CANDIDATE",
            """
            RAW_NODE_COUNT=${candidates.size}
            CARD_COUNT=${candidates.size}
            VALID_VIDEO_COUNT=${accepted.size}
            SHORTS_COUNT=${collector?.shortsRejected ?: 0}
            REJECTED_COUNT=$totalRejected
            """.trimIndent()
        )

        return accepted
    }

    fun discoverAndFilterYouTubeCandidates(root: AccessibilityNodeInfo?): List<YouTubeVideoCandidate> {
        if (root == null) return emptyList()
        val raw = discoverYouTubeCandidates(root)
        val diag = DiagnosticCollector()
        return filterYouTubeCandidates(raw, diag)
    }

    fun findYouTubeVideoCards(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return discoverAndFilterYouTubeCandidates(root).mapNotNull { it.actionableNode }
    }

    fun isExcludedYouTubeNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return true
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val raw = if (desc.isNotBlank()) desc else text
        val cleaned = cleanYouTubeTitle(raw)
        val candidate = YouTubeVideoCandidate(
            title = cleaned,
            actionableNode = node,
            sourceNode = node,
            rawText = text,
            rawDesc = desc
        )
        return isShortsCandidate(candidate) || isNonVideoCandidate(candidate)
    }

    private fun findBestActionableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (isClickableOrHasClickAction(node)) {
            return node
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (isClickableOrHasClickAction(parent)) {
                return parent
            }
            parent = parent.parent
            depth++
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isClickableOrHasClickAction(child)) {
                return child
            }
        }
        return node
    }

    private fun isClickableOrHasClickAction(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun isShortsCandidate(c: YouTubeVideoCandidate): Boolean {
        if (isYouTubeShortsTitleOrUrl(c.title)) return true
        if (isYouTubeShortsTitleOrUrl(c.rawDesc)) return true
        if (isYouTubeShortsTitleOrUrl(c.rawText)) return true
        val src = c.sourceNode
        val act = c.actionableNode
        if (src != null && isShortsCard(src)) return true
        if (act != null && isShortsCard(act)) return true
        return false
    }

    private fun isNonVideoCandidate(c: YouTubeVideoCandidate): Boolean {
        if (isUiNoiseOrStatusTitle(c.title)) return true
        if (isUiNoiseOrStatusTitle(c.rawText)) return true
        if (isUiNoiseOrStatusTitle(c.rawDesc)) return true
        val src = c.sourceNode
        if (src != null && isUiToolbarOrNavBar(src)) return true
        return false
    }

    private fun normalizeForDeduplication(title: String): String {
        return title.lowercase().replace(Regex("""[^a-z0-9]"""), "")
    }

    private fun performClickOnCandidate(candidate: YouTubeVideoCandidate): Boolean {
        val action = candidate.actionableNode
        if (action != null && performClickOnNode(action)) {
            return true
        }
        val src = candidate.sourceNode
        if (src != null && src != candidate.actionableNode) {
            if (performClickOnNode(src)) {
                return true
            }
        }
        val root = rootInActiveWindow
        if (root != null) {
            val target = findNodeByLabel(root, candidate.title)
            if (target != null && performClickOnNode(target)) {
                return true
            }
        }
        return false
    }

    fun debugYouTubeResults(): String {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w("BlindAI_YT_TREE", "ROOT_NULL")
            return "YouTube is not active or root window is not available."
        }

        Log.d(
            "BlindAI_YT_TREE",
            """
            ROOT_RECEIVED
            packageName=${root.packageName}
            className=${root.className}
            childCount=${root.childCount}
            """.trimIndent()
        )

        val rawCandidates = discoverYouTubeCandidates(root)
        val diag = DiagnosticCollector()
        val accepted = filterYouTubeCandidates(rawCandidates, diag)

        val ytState = youTubeSelectionState
        Log.d(
            "BlindAI_YT_STATE",
            """
            SELECTION_STATE
            exists=${ytState != null}
            count=${ytState?.results?.size ?: 0}
            titles=${ytState?.results?.joinToString(", ") { it.title } ?: "none"}
            """.trimIndent()
        )

        return "Debug complete. I found ${accepted.size} valid videos."
    }

    fun isYouTubeOpen(): Boolean {
        val root = rootInActiveWindow
        return root?.packageName?.contains("youtube") == true
    }

    private fun logNodeDiagnostics(node: AccessibilityNodeInfo) {
        val pkg = node.packageName?.toString() ?: ""
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val click = node.isClickable
        val vis = node.isVisibleToUser
        val hasClickAction = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        val childCount = node.childCount
        val parentCls = node.parent?.className?.toString() ?: ""

        Log.d(
            "BlindAI_YT_Tree",
            "Node: pkg=$pkg, cls=$cls, id=$id, text='$text', desc='$desc', click=$click, vis=$vis, clickAction=$hasClickAction, children=$childCount, parent=$parentCls"
        )
    }

    private fun hasClickableChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable || child.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return true
        }
        return false
    }

    // ----------------------------------------------------
    // YOUTUBE CURRENTLY PLAYING / TITLE DETECTION
    // ----------------------------------------------------
    fun getCurrentlyPlayingAnnouncement(): String {
        val root = rootInActiveWindow ?: return "Could not determine what is playing."
        val info = extractCurrentlyPlayingTitleAndType(root)
        return if (info != null) {
            val (title, isShort) = info
            if (isShort) "You are watching a Short: $title." else "You are watching $title."
        } else {
            "Could not determine what is playing."
        }
    }

    fun getCurrentlyPlayingTitleAnnouncement(): String {
        val root = rootInActiveWindow ?: return "Could not determine the video title."
        val info = extractCurrentlyPlayingTitleAndType(root)
        return if (info != null) {
            "The title of this video is ${info.first}."
        } else {
            "Could not determine the video title."
        }
    }

    private fun extractCurrentlyPlayingTitleAndType(root: AccessibilityNodeInfo): Pair<String, Boolean>? {
        val candidates = mutableListOf<Pair<String, Boolean>>()
        searchPlayingTitleRecursive(root, candidates)
        if (candidates.isNotEmpty()) {
            return candidates.first()
        }
        return null
    }

    private fun searchPlayingTitleRecursive(node: AccessibilityNodeInfo?, list: MutableList<Pair<String, Boolean>>) {
        if (node == null || list.isNotEmpty()) return

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        val isTitleId = viewId.contains("title") || viewId.contains("video_title") || viewId.contains("player_title") || viewId.contains("reel_player")

        if (isTitleId) {
            val candidate = if (text.isNotBlank()) text else desc
            if (candidate.isNotBlank() && candidate.length > 3 && !candidate.equals("youtube", ignoreCase = true)) {
                val isShort = viewId.contains("reel") || candidate.contains("#shorts", ignoreCase = true)
                list.add(Pair(cleanYouTubeTitle(candidate), isShort))
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                searchPlayingTitleRecursive(child, list)
            }
        }
    }

    // ----------------------------------------------------
    // YOUTUBE SKIP AD (VOICE COMMAND: "Skip ad")
    // ----------------------------------------------------
    fun skipAdByVoice(): String {
        val root = rootInActiveWindow ?: return "Screen is not accessible right now."
        val skipNode = findSkipAdNode(root)

        return if (skipNode != null && skipNode.isEnabled) {
            val success = performClickOnNode(skipNode)
            if (success) {
                "Ad skipped."
            } else {
                "Found Skip Ad button but could not click it."
            }
        } else {
            "No Skip Ad button is available."
        }
    }

    private fun findSkipAdNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val skipPhrases = listOf("skip ad", "skip ads", "skip", "close ad", "skip preview")
        val matchesText = skipPhrases.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }
        val matchesDesc = skipPhrases.any { desc == it || desc.startsWith("$it ") || desc.endsWith(" $it") }
        val matchesId = viewId.contains("skip_ad") || viewId.contains("skip_button") || viewId.contains("ad_skip")

        if ((matchesText || matchesDesc || matchesId) && node.isClickable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val match = findSkipAdNode(child)
                if (match != null) return match
            }
        }
        return null
    }

    // ----------------------------------------------------
    // YOUTUBE CAPTIONS / SUBTITLES CONTROL
    // ----------------------------------------------------
    private var lastReadSubtitle: String = ""

    fun setCaptions(enable: Boolean): String {
        val root = rootInActiveWindow ?: return "Subtitle control is not available on this screen."
        val captionNode = findCaptionControlNode(root)
        if (captionNode != null) {
            val isChecked = captionNode.isChecked || captionNode.isSelected
            val desc = captionNode.contentDescription?.toString()?.lowercase() ?: ""
            val text = captionNode.text?.toString()?.lowercase() ?: ""

            if (enable && (isChecked || desc.contains("turn off captions") || text.contains("turn off captions"))) {
                return "Subtitles turned on."
            }
            if (!enable && (!isChecked && (desc.contains("turn on captions") || text.contains("turn on captions")))) {
                return "Subtitles turned off."
            }

            val success = performClickOnNode(captionNode)
            return if (success) {
                if (enable) "Subtitles turned on." else "Subtitles turned off."
            } else {
                "Subtitle control is not available on this screen."
            }
        }
        return "Subtitle control is not available on this screen."
    }

    private fun findCaptionControlNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isCaptionKeyword = desc.contains("caption") || desc.contains("subtitle") || desc == "cc" ||
                desc.contains("subtitles/cc") || text.contains("caption") || text.contains("subtitle") || text == "cc" ||
                viewId.contains("caption") || viewId.contains("subtitle") || viewId.contains("cc_button")

        if (isCaptionKeyword && (node.isClickable || node.isCheckable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val match = findCaptionControlNode(child)
            if (match != null) return match
        }
        return null
    }

    fun readSubtitles(): String {
        val root = rootInActiveWindow ?: return "Subtitle text is not available to accessibility."
        val subtitleTexts = mutableListOf<String>()
        collectSubtitleTexts(root, subtitleTexts)

        if (subtitleTexts.isEmpty()) {
            return "Subtitle text is not available to accessibility."
        }

        val combined = subtitleTexts.joinToString(" ").trim()
        if (combined.isBlank()) {
            return "Subtitle text is not available to accessibility."
        }

        if (combined.equals(lastReadSubtitle, ignoreCase = true)) {
            return "No new subtitles."
        }

        lastReadSubtitle = combined
        return combined
    }

    private fun collectSubtitleTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""
        val isSubtitleContainer = viewId.contains("subtitle") || viewId.contains("caption") || viewId.contains("cue") ||
                className.contains("subtitle") || className.contains("cue")

        val text = node.text?.toString()?.trim() ?: ""
        if (isSubtitleContainer && text.isNotBlank() && !text.equals("cc", ignoreCase = true) && !text.contains("turn on", ignoreCase = true)) {
            list.add(text)
        }

        for (i in 0 until node.childCount) {
            collectSubtitleTexts(node.getChild(i), list)
        }
    }

    // ----------------------------------------------------
    // YOUTUBE REPLAY & SEEK CONTROLS
    // ----------------------------------------------------
    fun replayVideo(): String {
        val root = rootInActiveWindow ?: return "Replay control is not available on this screen."
        val replayNode = findReplayNode(root)
        return if (replayNode != null && performClickOnNode(replayNode)) {
            "Video replayed."
        } else {
            "Replay control is not available on this screen."
        }
    }

    private fun findReplayNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if ((desc.contains("replay") || desc.contains("restart") || viewId.contains("replay")) && node.isClickable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val match = findReplayNode(node.getChild(i))
            if (match != null) return match
        }
        return null
    }

    fun seekForward(seconds: Int = 10): String {
        val root = rootInActiveWindow ?: return "Fast forward control is not available."
        val forwardNode = findSeekNode(root, forward = true)
        return if (forwardNode != null && performClickOnNode(forwardNode)) {
            "Seeked forward $seconds seconds."
        } else {
            "Fast forward control is not available."
        }
    }

    fun seekBackward(seconds: Int = 10): String {
        val root = rootInActiveWindow ?: return "Rewind control is not available."
        val rewindNode = findSeekNode(root, forward = false)
        return if (rewindNode != null && performClickOnNode(rewindNode)) {
            "Seeked backward $seconds seconds."
        } else {
            "Rewind control is not available."
        }
    }

    private fun findSeekNode(node: AccessibilityNodeInfo?, forward: Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val matches = if (forward) {
            desc.contains("forward 10") || desc.contains("fast forward") || desc.contains("seek forward") || viewId.contains("fast_forward") || viewId.contains("forward")
        } else {
            desc.contains("rewind 10") || desc.contains("rewind") || desc.contains("seek backward") || viewId.contains("rewind") || viewId.contains("backward")
        }

        if (matches && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node
        }

        for (i in 0 until node.childCount) {
            val match = findSeekNode(node.getChild(i), forward)
            if (match != null) return match
        }
        return null
    }

    // ----------------------------------------------------
    // YOUTUBE COMMENTS CONTROLS
    // ----------------------------------------------------
    fun openComments(): String {
        val root = rootInActiveWindow ?: return "Comments are not available on this screen."
        val commentsNode = findCommentsEntryPoint(root)
        return if (commentsNode != null && performClickOnNode(commentsNode)) {
            "Comments opened."
        } else {
            "Comments are not available on this screen."
        }
    }

    private fun findCommentsEntryPoint(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isCommentEntry = desc.contains("comments") || desc.contains("open comments") ||
                text.contains("comments") || viewId.contains("comments_entry") || viewId.contains("comment_entry") ||
                viewId.contains("comments_teaser")

        if (isCommentEntry && (node.isClickable || node.parent?.isClickable == true)) {
            return if (node.isClickable) node else node.parent
        }

        for (i in 0 until node.childCount) {
            val match = findCommentsEntryPoint(node.getChild(i))
            if (match != null) return match
        }
        return null
    }

    fun readComments(): String {
        val root = rootInActiveWindow ?: return "No comments found on screen."
        val commentList = mutableListOf<String>()
        collectCommentTexts(root, commentList)

        return if (commentList.isNotEmpty()) {
            val topComments = commentList.take(3).mapIndexed { i, c -> "${i + 1}: $c" }.joinToString(". ")
            "Comments: $topComments"
        } else {
            "No comments found on screen."
        }
    }

    private fun collectCommentTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || list.size >= 5 || !node.isVisibleToUser) return
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""

        if ((viewId.contains("comment_text") || viewId.contains("comment_body") || viewId.contains("content_text")) && text.isNotBlank() && text.length > 5) {
            list.add(text)
        }

        for (i in 0 until node.childCount) {
            collectCommentTexts(node.getChild(i), list)
        }
    }

    fun closeComments(): String {
        val root = rootInActiveWindow ?: return performBack()
        val closeNode = findCloseButton(root)
        return if (closeNode != null && performClickOnNode(closeNode)) {
            "Comments closed."
        } else {
            val backSuccess = performGlobalAction(GLOBAL_ACTION_BACK)
            if (backSuccess) "Comments closed." else "Could not close comments."
        }
    }

    private fun findCloseButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if ((desc.contains("close") || desc.contains("dismiss") || viewId.contains("close") || viewId.contains("dismiss")) && node.isClickable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val match = findCloseButton(node.getChild(i))
            if (match != null) return match
        }
        return null
    }

    // ----------------------------------------------------
    // YOUTUBE RESULT NAVIGATION
    // ----------------------------------------------------
    suspend fun readResults(): String {
        val ytState = youTubeSelectionState
        if (ytState != null && ytState.results.isNotEmpty()) {
            return buildYouTubeAnnouncement(ytState.results)
        }
        if (voiceOptions.isNotEmpty()) {
            return buildOptionsAnnouncement("videos")
        }
        val deadline = System.currentTimeMillis() + 3500L
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.contains("youtube") == true) {
                val candidates = discoverAndFilterYouTubeCandidates(root)
                if (candidates.isNotEmpty()) {
                    youTubeSelectionState = YouTubeSelectionState(
                        results = candidates,
                        selectedIndex = 0,
                        packageName = root.packageName?.toString() ?: "com.google.android.youtube"
                    )
                    val options = candidates.mapNotNull { if (it.actionableNode != null) it.actionableNode to it.title else null }
                    if (options.isNotEmpty()) {
                        publishVoiceOptions(options, OPTIONS_CONTEXT_YOUTUBE_RESULTS, root.packageName?.toString() ?: "")
                    }
                    return buildYouTubeAnnouncement(candidates)
                }
            }
            delay(400L)
        }
        return "I couldn't find any regular videos."
    }

    fun nextResult(): String {
        val ytState = youTubeSelectionState
        if (ytState != null && ytState.results.isNotEmpty()) {
            ytState.selectedIndex = (ytState.selectedIndex + 1) % ytState.results.size
            val opt = ytState.results[ytState.selectedIndex]
            return "Option ${ytState.selectedIndex + 1}: ${opt.title}."
        }
        if (voiceOptions.isNotEmpty()) {
            currentFocusNodeIndex = (currentFocusNodeIndex + 1) % voiceOptions.size
            val opt = voiceOptions[currentFocusNodeIndex + 1]
            if (opt != null) {
                opt.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                return "Option ${currentFocusNodeIndex + 1}: ${opt.label}."
            }
        }
        return navigateNextNode()
    }

    fun previousResult(): String {
        val ytState = youTubeSelectionState
        if (ytState != null && ytState.results.isNotEmpty()) {
            ytState.selectedIndex = if (ytState.selectedIndex <= 0) ytState.results.size - 1 else ytState.selectedIndex - 1
            val opt = ytState.results[ytState.selectedIndex]
            return "Option ${ytState.selectedIndex + 1}: ${opt.title}."
        }
        if (voiceOptions.isNotEmpty()) {
            currentFocusNodeIndex = if (currentFocusNodeIndex <= 0) voiceOptions.size - 1 else currentFocusNodeIndex - 1
            val opt = voiceOptions[currentFocusNodeIndex + 1]
            if (opt != null) {
                opt.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                return "Option ${currentFocusNodeIndex + 1}: ${opt.label}."
            }
        }
        return navigatePreviousNode()
    }

    suspend fun playCurrentSelectedVoiceOption(): String {
        val ytState = youTubeSelectionState
        if (ytState != null && ytState.results.isNotEmpty()) {
            return activateVoiceOption(ytState.selectedIndex + 1)
        }
        return resumeMediaPlayback()
    }

    // ----------------------------------------------------
    // ACCESSIBILITY FOCUS NAVIGATION (NEXT / PREVIOUS / CLICK THIS)
    // ----------------------------------------------------
    fun navigateNextNode(): String {
        val root = rootInActiveWindow ?: return "Screen is not accessible."
        val actionableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectActionableNodes(root, actionableNodes)

        if (actionableNodes.isEmpty()) {
            return "No actionable buttons or elements found on screen."
        }

        currentFocusNodeIndex = (currentFocusNodeIndex + 1) % actionableNodes.size
        val targetNode = actionableNodes[currentFocusNodeIndex]
        targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val label = getNodeLabel(targetNode)
        return label
    }

    fun navigatePreviousNode(): String {
        val root = rootInActiveWindow ?: return "Screen is not accessible."
        val actionableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectActionableNodes(root, actionableNodes)

        if (actionableNodes.isEmpty()) {
            return "No actionable buttons or elements found on screen."
        }

        currentFocusNodeIndex = if (currentFocusNodeIndex <= 0) actionableNodes.size - 1 else currentFocusNodeIndex - 1
        val targetNode = actionableNodes[currentFocusNodeIndex]
        targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val label = getNodeLabel(targetNode)
        return label
    }

    fun clickCurrentlyFocusedNode(): String {
        val root = rootInActiveWindow ?: return "Screen is not accessible."
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        return if (focused != null) {
            val label = getNodeLabel(focused)
            val success = performClickOnNode(focused)
            if (success) "Clicked $label." else "Could not click $label."
        } else {
            val actionableNodes = mutableListOf<AccessibilityNodeInfo>()
            collectActionableNodes(root, actionableNodes)
            if (currentFocusNodeIndex in actionableNodes.indices) {
                val node = actionableNodes[currentFocusNodeIndex]
                val label = getNodeLabel(node)
                val success = performClickOnNode(node)
                if (success) "Clicked $label." else "Could not click $label."
            } else {
                "Nothing is currently selected. Say 'next' to select an item."
            }
        }
    }

    private fun collectActionableNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.isVisibleToUser) return

        if (node.isClickable || node.isCheckable || node.isEditable) {
            val label = getNodeLabel(node)
            if (label.isNotBlank() && !label.equals("button", ignoreCase = true)) {
                list.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectActionableNodes(child, list)
            }
        }
    }

    private fun getNodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val hint = node.hintText?.toString()?.trim() ?: ""

        val raw = when {
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            hint.isNotBlank() -> hint
            else -> ""
        }

        if (raw.isBlank()) return ""
        return if (node.isClickable && !raw.lowercase().contains("button")) "$raw button" else raw
    }

    // ----------------------------------------------------
    // SCREEN READING (Clean summary without duplicates)
    // ----------------------------------------------------
    fun readScreenContent(): String {
        val rootNode = rootInActiveWindow
            ?: return "I cannot see any active window on the screen. Please open an app first."

        val pkg = rootNode.packageName?.toString() ?: ""
        if (pkg.contains("youtube")) {
            // Check if on video watch/player page
            if (isYouTubePlayerScreen(rootNode) || hasPlayingVideo(rootNode)) {
                val titleInfo = extractCurrentlyPlayingTitleAndType(rootNode)
                val title = titleInfo?.first ?: extractVideoTitleFromPage(rootNode) ?: "YouTube Video"
                val controls = mutableListOf<String>()
                if (findPlaybackControlNode(rootNode, "pause") != null) controls.add("Pause button available")
                else if (findPlaybackControlNode(rootNode, "play") != null) controls.add("Play button available")
                else controls.add("Playback controls available")

                if (findCommentsEntryPoint(rootNode) != null) controls.add("Comments available")
                if (findCaptionControlNode(rootNode) != null) controls.add("Subtitles available")
                if (findSkipAdNode(rootNode) != null) controls.add("Skip Ad button available")

                val controlsStr = controls.joinToString(". ")
                return "YouTube video page. Title: $title. $controlsStr."
            }

            // Check if on search results page
            val candidates = discoverAndFilterYouTubeCandidates(rootNode)
            if (candidates.isNotEmpty()) {
                val options = candidates.mapNotNull { if (it.actionableNode != null) it.actionableNode to it.title else null }
                if (options.isNotEmpty()) {
                    publishVoiceOptions(options, OPTIONS_CONTEXT_YOUTUBE_RESULTS, pkg)
                }
                val listSummary = candidates.take(5).mapIndexed { i, c ->
                    "Option ${i + 1}: ${c.title}"
                }.joinToString(". ")
                return "YouTube search results. $listSummary. Say an option number to play it."
            }
        }

        val extractedText = mutableListOf<String>()
        extractTextFromNode(rootNode, extractedText)

        if (extractedText.isEmpty()) {
            return "The current screen contains no readable text."
        }

        val cleanedLines = mutableListOf<String>()
        for (item in extractedText) {
            val trimmed = item.trim()
            if (trimmed.isNotBlank() && (cleanedLines.isEmpty() || cleanedLines.last() != trimmed)) {
                cleanedLines.add(trimmed)
            }
        }

        val fullText = cleanedLines.joinToString(". ")
        return if (fullText.length > 1200) {
            fullText.take(1200) + "... End of visible screen preview."
        } else {
            fullText
        }
    }

    private fun extractVideoTitleFromPage(root: AccessibilityNodeInfo): String? {
        val titles = mutableListOf<String>()
        collectVideoTitlesFromPage(root, titles)
        return titles.firstOrNull()
    }

    private fun collectVideoTitlesFromPage(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || list.isNotEmpty() || !node.isVisibleToUser) return
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if ((viewId.contains("title") || viewId.contains("video_title")) && (text.length > 3 || desc.length > 3)) {
            val title = if (text.isNotBlank()) text else desc
            if (!title.equals("youtube", ignoreCase = true)) {
                list.add(cleanYouTubeTitle(title))
                return
            }
        }

        for (i in 0 until node.childCount) {
            collectVideoTitlesFromPage(node.getChild(i), list)
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val hint = node.hintText?.toString()

        val content = when {
            !text.isNullOrBlank() -> text
            !desc.isNullOrBlank() -> desc
            !hint.isNullOrBlank() -> hint
            else -> null
        }

        if (!content.isNullOrBlank()) {
            val label = if (node.isClickable && !node.isCheckable) {
                if (content.lowercase().contains("button") || content.lowercase().contains("icon")) content else "$content button"
            } else {
                content
            }
            list.add(label)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, list)
            }
        }
    }

    // ----------------------------------------------------
    // CLICK BUTTON BY VOICE
    // ----------------------------------------------------
    fun clickNodeByText(targetText: String): String {
        val rootNode = rootInActiveWindow
            ?: return "Screen is not accessible right now."

        val cleanedTarget = targetText.trim().lowercase()
        val foundNode = findClickableNode(rootNode, cleanedTarget)

        return if (foundNode != null) {
            val success = performClickOnNode(foundNode)
            if (success) {
                "Clicked $targetText."
            } else {
                "Found $targetText but could not click it."
            }
        } else {
            "Could not find any button or link named '$targetText' on screen."
        }
    }

    private fun findClickableNode(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (text.contains(target) || desc.contains(target) || (target.length > 3 && viewId.contains(target))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val match = findClickableNode(child, target)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable || current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                try {
                    val res = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (res) return true
                } catch (_: Exception) {}
            }
            current = current.parent
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && (child.isClickable || child.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
                try {
                    val res = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (res) return true
                } catch (_: Exception) {}
            }
        }
        try {
            val res = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (res) return true
        } catch (_: Exception) {}

        // Fallback: Gesture tap at bounds center for Litho / Compose views
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val clickX = rect.centerX().toFloat()
                val clickY = rect.centerY().toFloat()
                if (clickX > 0 && clickY > 0) {
                    val path = android.graphics.Path().apply {
                        moveTo(clickX, clickY)
                    }
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
                    val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                    dispatchGesture(gesture, null, null)
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    // ----------------------------------------------------
    // TYPE TEXT INTO INPUT FIELD
    // ----------------------------------------------------
    fun typeTextIntoInput(textToType: String): String {
        val rootNode = rootInActiveWindow
            ?: return "Screen is not accessible to type."

        val inputNode = findEditableNode(rootNode)

        return if (inputNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            val success = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                "Typed '$textToType' into the text box."
            } else {
                "Could not type into the text box."
            }
        } else {
            "No active text box or search field found on screen."
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isEditable || (node.isFocused && (node.className?.contains("EditText") == true || node.className?.contains("TextField") == true))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val match = findEditableNode(child)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    // ----------------------------------------------------
    // WHATSAPP AUTOMATIC SEND (voice-first, no manual tap)
    // ----------------------------------------------------

    fun isWhatsAppActive(): Boolean {
        val root = rootInActiveWindow ?: return false
        return isWhatsAppPackage(root.packageName?.toString())
    }

    private fun isWhatsAppPackage(pkg: String?): Boolean {
        return pkg != null && pkg.lowercase().contains("whatsapp")
    }

    suspend fun triggerSendOnCurrentScreen(): String {
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppPackage(root.packageName?.toString())) {
            return "There is no pending message to send."
        }
        val editable = findEditableNode(root)
        val text = editable?.text?.toString()?.trim() ?: ""
        if (text.isBlank()) {
            return "The message box is empty."
        }
        return when (completeWhatsAppSend(text)) {
            WhatsAppSendResult.SENT -> "Message sent."
            WhatsAppSendResult.WHATSAPP_NOT_OPEN -> "WhatsApp is not open."
            WhatsAppSendResult.MESSAGE_ENTRY_FAILED -> "I couldn't enter the message."
            WhatsAppSendResult.SEND_BUTTON_NOT_FOUND,
            WhatsAppSendResult.SEND_FAILED -> "I couldn't find WhatsApp's Send button."
            WhatsAppSendResult.VERIFICATION_FAILED -> "I tried to send the message, but I couldn't verify that it was sent."
        }
    }

    /**
     * Executes the full voice-first WhatsApp send flow:
     * 1. Waits for WhatsApp window to attach and render (re-querying rootInActiveWindow).
     * 2. Logs diagnostic node hierarchy for logcat debugging (filtered for com.whatsapp).
     * 3. Ensures message is inserted and committed in the text box (triggering TextWatcher so Send button appears).
     * 4. Multi-attempt search for the actual Send button (using semantic signals, contentDescription, resource IDs, clickable ancestors).
     * 5. Fallback to IME action (ACTION_IME_ENTER) if Send button not directly activatable.
     * 6. Verifies that the message was ACTUALLY sent (input box cleared / outgoing bubble appeared).
     */
    suspend fun completeWhatsAppSend(expectedMessage: String, recipientName: String = ""): WhatsAppSendResult {
        Log.d("BlindAI_WhatsApp", "Starting completeWhatsAppSend for message: '$expectedMessage'")

        // 1. Wait for WhatsApp to open and active window to be available (up to 10 seconds)
        val openDeadline = System.currentTimeMillis() + 10000L
        var isOpened = false
        while (System.currentTimeMillis() < openDeadline) {
            val root = rootInActiveWindow
            if (root != null && isWhatsAppPackage(root.packageName?.toString())) {
                isOpened = true
                break
            }
            delay(400L)
        }

        if (!isOpened) {
            Log.w("BlindAI_WhatsApp", "WhatsApp failed to open before deadline.")
            return WhatsAppSendResult.WHATSAPP_NOT_OPEN
        }

        // Brief delay for chat UI to settle
        delay(600L)

        // 2. Ensure message is typed in the compose box and committed (triggers TextWatcher)
        val messageEntered = ensureMessageTypedAndCommitted(expectedMessage)
        if (!messageEntered) {
            Log.e("BlindAI_WhatsApp", "Failed to enter message into WhatsApp composer after retries.")
            return WhatsAppSendResult.MESSAGE_ENTRY_FAILED
        }

        // Wait briefly after typing so WhatsApp swaps Voice Note -> Send Button
        delay(400L)

        // 3. Multi-attempt Send execution (up to 4 attempts with fresh root every time)
        var sendActionTriggered = false
        val maxAttempts = 4

        for (attempt in 1..maxAttempts) {
            val root = rootInActiveWindow
            if (root == null || !isWhatsAppPackage(root.packageName?.toString())) {
                delay(500L)
                continue
            }

            // Diagnostic dump of the entire WhatsApp tree
            Log.d("BlindAI_WhatsApp", "--- ATTEMPT $attempt: Diagnostic WhatsApp Node Dump ---")
            dumpWhatsAppNodeTree(root, 0)

            // Strategy A: Find Send button via multi-signal recursive search
            val sendNode = findWhatsAppSendButton(root)
            if (sendNode != null) {
                Log.d("BlindAI_WhatsApp", "Found Send candidate: id=${sendNode.viewIdResourceName}, desc='${sendNode.contentDescription}', text='${sendNode.text}', class=${sendNode.className}")
                val clicked = performSendClick(sendNode)
                if (clicked) {
                    Log.d("BlindAI_WhatsApp", "Send button click performed successfully on attempt $attempt.")
                    sendActionTriggered = true
                    break
                } else {
                    Log.w("BlindAI_WhatsApp", "performSendClick returned false on attempt $attempt.")
                }
            } else {
                Log.w("BlindAI_WhatsApp", "Send button not found via standard signals on attempt $attempt.")
            }

            // Strategy B: Sibling/Composer Footer Search
            val composerSendNode = findComposerSendButton(root)
            if (composerSendNode != null) {
                Log.d("BlindAI_WhatsApp", "Found Composer Send candidate: id=${composerSendNode.viewIdResourceName}, desc='${composerSendNode.contentDescription}'")
                val clicked = performSendClick(composerSendNode)
                if (clicked) {
                    Log.d("BlindAI_WhatsApp", "Composer Send button click performed on attempt $attempt.")
                    sendActionTriggered = true
                    break
                }
            }

            // Strategy C: IME Action / Enter on Editable
            val editable = findEditableNode(root)
            if (editable != null) {
                Log.d("BlindAI_WhatsApp", "Attempting IME action on editable node: id=${editable.viewIdResourceName}")
                val imeSuccess = tryPerformImeSend(editable)
                if (imeSuccess) {
                    Log.d("BlindAI_WhatsApp", "IME send action succeeded on attempt $attempt.")
                    sendActionTriggered = true
                    break
                }
            }

            delay(600L)
        }

        if (!sendActionTriggered) {
            Log.e("BlindAI_WhatsApp", "All send attempts failed. Send button not found or not clickable.")
            return WhatsAppSendResult.SEND_BUTTON_NOT_FOUND
        }

        // 4. Verify the send ACTUALLY happened (Requirement 6)
        val verified = verifyMessageSent(expectedMessage)
        return if (verified) {
            Log.d("BlindAI_WhatsApp", "Message send VERIFIED successfully.")
            WhatsAppSendResult.SENT
        } else {
            Log.w("BlindAI_WhatsApp", "Send action was clicked, but verification could not confirm message was sent.")
            WhatsAppSendResult.VERIFICATION_FAILED
        }
    }

    private suspend fun ensureMessageTypedAndCommitted(message: String): Boolean {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isBlank()) return true

        // Attempt 1: set text
        var root = rootInActiveWindow
        var editable = if (root != null) findEditableNode(root) else null
        var currentText = editable?.text?.toString()?.trim() ?: ""

        if (editable == null || currentText.isBlank() || currentText != trimmedMessage) {
            if (editable != null) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, trimmedMessage)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                delay(300L)
            }
        }

        // Verify text was inserted in composer
        root = rootInActiveWindow
        editable = if (root != null) findEditableNode(root) else null
        currentText = editable?.text?.toString()?.trim() ?: ""

        // If composer is still empty or didn't get text, retry once with refreshed tree
        if (editable == null || currentText.isBlank()) {
            delay(500L)
            root = rootInActiveWindow
            editable = if (root != null) findEditableNode(root) else null
            if (editable != null) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, trimmedMessage)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                delay(300L)
            }
        }

        // Final verification that text is in composer
        root = rootInActiveWindow
        editable = if (root != null) findEditableNode(root) else null
        currentText = editable?.text?.toString()?.trim() ?: ""

        if (editable != null && currentText.isNotBlank()) {
            try {
                val selectionArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, trimmedMessage.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, trimmedMessage.length)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            } catch (_: Exception) {}

            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editable.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return true
        }

        return false
    }

    private fun dumpWhatsAppNodeTree(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 20) return
        val indent = "  ".repeat(depth)
        val actionsStr = try { node.actionList.joinToString(",") { "${it.id}" } } catch (_: Exception) { "" }
        Log.d(
            "BlindAI_WhatsApp",
            "$indent[$depth] class=${node.className} id=${node.viewIdResourceName} text='${node.text}' desc='${node.contentDescription}' clickable=${node.isClickable} focusable=${node.isFocusable} enabled=${node.isEnabled} actions=[$actionsStr]"
        )
        for (i in 0 until node.childCount) {
            dumpWhatsAppNodeTree(node.getChild(i), depth + 1)
        }
    }

    /**
     * Finds the WhatsApp Send action using multiple accessibility signals
     * (contentDescription, text, resource ID, localized names) avoiding voice/attach buttons.
     */
    private fun findWhatsAppSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase()?.trim() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // Strict exclusions: Never click voice note, mic, attach, camera, emoji, call, search, back, or the text field itself
        val isExcluded = desc.contains("voice") || desc.contains("mic") || desc.contains("record") ||
                desc.contains("attach") || desc.contains("camera") || desc.contains("emoji") ||
                desc.contains("sticker") || desc.contains("gif") || desc.contains("gallery") ||
                desc.contains("call") || desc.contains("more options") || desc.contains("back") ||
                desc.contains("navigate") || desc.contains("search") ||
                viewId.contains("voice") || viewId.contains("mic") || viewId.contains("record") ||
                viewId.contains("attach") || viewId.contains("camera") || viewId.contains("emoji") ||
                className.contains("edittext") || className.contains("textfield")

        if (!isExcluded) {
            val matchesDesc = desc.isNotEmpty() && (
                    desc == "send" || desc == "send message" || desc == "send text" ||
                    desc.startsWith("send to") || desc == "enviar" || desc == "envoyer" ||
                    desc == "إرسال" || desc == "भेजें"
            )
            val matchesText = text == "send" || text == "enviar" || text == "envoyer"
            val matchesId = viewId.isNotEmpty() && (
                    viewId.endsWith(":id/send") || viewId.endsWith(":id/send_btn") ||
                    viewId.endsWith(":id/btn_send") || viewId.endsWith(":id/entry_send") ||
                    viewId.endsWith(":id/footer_send") ||
                    (viewId.contains("send") && !viewId.contains("voice"))
            )

            if ((matchesDesc || matchesText || matchesId) && node.isEnabled) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val match = findWhatsAppSendButton(child)
                if (match != null) return match
            }
        }
        return null
    }

    /**
     * Fallback search: Locate the composer input (EditText / id/entry) and find its sibling
     * ImageButton / clickable view on the right side that is not an attachment/emoji/mic.
     */
    private fun findComposerSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val editable = findEditableNode(root) ?: return null
        val parent = editable.parent ?: return null

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableLeaves(parent, candidates)
        val grandParent = parent.parent
        if (grandParent != null) {
            collectClickableLeaves(grandParent, candidates)
        }

        for (cand in candidates) {
            val desc = cand.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = cand.viewIdResourceName?.lowercase() ?: ""
            val isIgnored = desc.contains("voice") || desc.contains("mic") || desc.contains("attach") ||
                    desc.contains("camera") || desc.contains("emoji") || desc.contains("sticker") ||
                    viewId.contains("voice") || viewId.contains("mic") || viewId.contains("attach") ||
                    viewId.contains("camera") || viewId.contains("emoji") || cand == editable

            if (!isIgnored && (cand.isClickable || cand.parent?.isClickable == true || cand.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
                if (desc.contains("send") || viewId.contains("send") || viewId.contains("fab") || viewId.contains("button")) {
                    return cand
                }
            }
        }
        return null
    }

    private fun collectClickableLeaves(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null || list.size > 20) return
        if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            collectClickableLeaves(node.getChild(i), list)
        }
    }

    /**
     * Attempts to click the Send node, or its clickable ancestors (up to 4 levels).
     * Strictly uses accessibility semantics (no coordinates).
     */
    private fun performSendClick(node: AccessibilityNodeInfo): Boolean {
        // Attempt 1: Direct ACTION_CLICK on node
        try {
            if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
        } catch (_: Exception) {}

        // Attempt 2: Traverse up parent chain
        var currentParent = node.parent
        var depth = 0
        while (currentParent != null && depth < 4) {
            try {
                if (currentParent.isClickable || currentParent.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    val success = currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) return true
                }
            } catch (_: Exception) {}
            currentParent = currentParent.parent
            depth++
        }

        // Attempt 3: If node has clickable children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    if (child.isClickable || child.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                        val success = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) return true
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback: force ACTION_CLICK even if isClickable wasn't flagged
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
    }

    private fun tryPerformImeSend(editableNode: AccessibilityNodeInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return try {
                val hasImeEnter = editableNode.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                }
                if (hasImeEnter) {
                    editableNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    /**
     * Bounded verification loop (up to 3000ms, checking every 300ms):
     * Checks if the composer text field has cleared (meaning WhatsApp accepted and sent the message),
     * or if the Send button switched back to the voice note / mic icon.
     */
    private suspend fun verifyMessageSent(expectedMessage: String): Boolean {
        val deadline = System.currentTimeMillis() + 3000L
        val trimmed = expectedMessage.trim().lowercase()

        while (System.currentTimeMillis() < deadline) {
            delay(300L)
            val currentRoot = rootInActiveWindow ?: continue
            if (!isWhatsAppPackage(currentRoot.packageName?.toString())) {
                // If user was returned to our app or home screen after sending, treat as verified
                return true
            }

            val editable = findEditableNode(currentRoot)
            val currentText = editable?.text?.toString()?.trim()?.lowercase() ?: ""

            // If the editable text is now empty or does not contain the expected message, it was sent!
            if (editable != null && (currentText.isBlank() || !currentText.contains(trimmed))) {
                return true
            }

            // Check if mic / voice note button has reappeared (indicates empty input / sent state)
            val hasMicButton = hasVoiceNoteButton(currentRoot)
            if (hasMicButton && (currentText.isBlank() || currentText != trimmed)) {
                return true
            }
        }

        // Final check on fresh root
        val finalRoot = rootInActiveWindow
        if (finalRoot != null) {
            val editable = findEditableNode(finalRoot)
            val currentText = editable?.text?.toString()?.trim()?.lowercase() ?: ""
            if (editable != null && (currentText.isBlank() || !currentText.contains(trimmed))) {
                return true
            }
        }

        return false
    }

    private fun hasVoiceNoteButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (desc.contains("voice message") || desc.contains("voice note") || viewId.contains("voice_note")) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (hasVoiceNoteButton(node.getChild(i))) return true
        }
        return false
    }

    // ----------------------------------------------------
    // SCROLLING
    // ----------------------------------------------------
    fun scrollScreen(forward: Boolean): String {
        val rootNode = rootInActiveWindow
            ?: return "Screen is not accessible right now."

        val scrollNode = findScrollableNode(rootNode)

        return if (scrollNode != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val success = scrollNode.performAction(action)
            if (success) {
                if (forward) "Scrolled down." else "Scrolled up."
            } else {
                "Could not scroll the screen."
            }
        } else {
            "No scrollable content found on this screen."
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isScrollable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val match = findScrollableNode(child)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    // ----------------------------------------------------
    // WHATSAPP INCOMING CALL CONTROLS & DETECTION
    // ----------------------------------------------------
    private fun handleWhatsAppIncomingCallDetected(rawCaller: String, isVideo: Boolean) {
        if (callFlow.isActive) return
        val cellularActive = DeviceController.currentCallState == CallState.INCOMING_CELLULAR_CALL ||
                DeviceController.currentCallState == CallState.ACTIVE_CELLULAR_CALL ||
                PhoneCallReceiver.isRinging
        if (!callFlow.onIncomingCallDetected(rawCaller, isVideo, cellularActive)) return

        DeviceController.currentCallState = CallState.INCOMING_WHATSAPP_CALL
        DeviceController.currentWhatsAppCaller = callFlow.callerDisplay
        DeviceController.isWhatsAppVideoCall = isVideo

        callScope.launch {
            // Let the call UI and audio state settle before ducking and speaking,
            // so the announcement never competes with a full-volume ringtone.
            delay(WhatsAppCallFlow.STABILIZE_DELAY_MS)
            if (!callFlow.isActive || DeviceController.currentCallState != CallState.INCOMING_WHATSAPP_CALL) {
                return@launch
            }
            callAudioSession.duck()
            callFlow.onAnnouncementStarted()
            val voice = AndroidVoiceService.activeInstance
            if (voice == null) {
                callFlow.onAnnouncementCompleted()
                runCallDecisionWindow()
            } else {
                voice.speakWithCompletion(callFlow.announcementText()) {
                    callFlow.onAnnouncementCompleted()
                    callScope.launch { runCallDecisionWindow() }
                }
            }
        }
    }

    private suspend fun runCallDecisionWindow() {
        while (DeviceController.currentCallState == CallState.INCOMING_WHATSAPP_CALL &&
            callFlow.phase == WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND &&
            !callFlow.isDecisionExpired()
        ) {
            val voice = AndroidVoiceService.activeInstance ?: break
            if (voice.isSpeaking.value) {
                delay(300L)
                continue
            }
            val settled = CompletableDeferred<Unit>()
            voice.startListeningWithSettled { settled.complete(Unit) }
            withTimeoutOrNull(9000L) { settled.await() }
            delay(400L)
        }

        if (callFlow.phase == WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND) {
            try {
                AndroidVoiceService.activeInstance?.stopListening()
            } catch (_: Exception) {}
            if (callFlow.isDecisionExpired()) {
                callFlow.onTimedOut()
                callAudioSession.restore()
                callFlow.reset()
                if (DeviceController.currentCallState == CallState.INCOMING_WHATSAPP_CALL) {
                    AndroidVoiceService.speakGlobally(
                        "The WhatsApp call is still ringing. Press the volume button twice quickly to answer or decline."
                    )
                }
            } else {
                callAudioSession.restore()
                callFlow.reset()
            }
        }
    }

    private fun finishIncomingCallSession(terminal: WhatsAppCallPhase) {
        when (terminal) {
            WhatsAppCallPhase.ANSWERED -> callFlow.onAnswered()
            WhatsAppCallPhase.DECLINED -> callFlow.onDeclined()
            WhatsAppCallPhase.CALL_ENDED -> callFlow.onCallDismissed()
            else -> {}
        }
        try {
            AndroidVoiceService.activeInstance?.stopListening()
        } catch (_: Exception) {}
        callAudioSession.restore()
        callFlow.reset()
    }

    fun isWhatsAppIncomingCallScreen(root: AccessibilityNodeInfo? = rootInActiveWindow): Boolean {
        if (root == null || !isWhatsAppPackage(root.packageName?.toString())) return false

        val hasAnswer = findWhatsAppAnswerButton(root) != null || hasSwipeToAnswerText(root)
        val hasDecline = findWhatsAppDeclineButton(root) != null
        val hasIncomingIndicator = hasWhatsAppIncomingCallIndicator(root)

        return (hasAnswer || hasDecline) && (hasIncomingIndicator || (hasAnswer && hasDecline))
    }

    fun hasVideoCallIndicator(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val combined = "$text $desc $viewId"

        if (combined.contains("video call") || combined.contains("accept video call") ||
            combined.contains("decline video call") || combined.contains("video_call") ||
            combined.contains("camera")) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (hasVideoCallIndicator(node.getChild(i))) return true
        }
        return false
    }

    private fun hasWhatsAppIncomingCallIndicator(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val combined = "$text $desc"

        if (combined.contains("incoming voice call") || combined.contains("incoming video call") ||
            combined.contains("incoming call") || combined.contains("whatsapp voice call") ||
            combined.contains("whatsapp video call") || combined.contains("swipe up to answer") ||
            combined.contains("swipe to answer") || combined.contains("accept voice call") ||
            combined.contains("accept video call")) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (hasWhatsAppIncomingCallIndicator(node.getChild(i))) return true
        }
        return false
    }

    private fun hasSwipeToAnswerText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains("swipe") || desc.contains("swipe")) return true
        for (i in 0 until node.childCount) {
            if (hasSwipeToAnswerText(node.getChild(i))) return true
        }
        return false
    }

    fun findWhatsAppAnswerButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isAnswerLabel = text == "answer" || text == "accept" || text == "accept call" || text == "answer call" ||
                desc == "answer" || desc == "accept" || desc == "accept call" || desc == "answer call" ||
                desc.contains("accept voice call") || desc.contains("accept video call") ||
                desc.contains("swipe to answer") || desc.contains("swipe up to answer") ||
                desc.contains("video call answer controls") || text.contains("swipe up to answer")

        val isAnswerId = viewId.contains("answer") || viewId.contains("accept") || viewId.contains("voice_call_answer") ||
                viewId.contains("video_call_answer") || viewId.contains("call_accept") || viewId.contains("accept_invite")

        if ((isAnswerLabel || isAnswerId) && (node.isClickable || node.parent?.isClickable == true || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) node else node.parent
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val match = findWhatsAppAnswerButton(child)
            if (match != null) return match
        }
        return null
    }

    fun findWhatsAppDeclineButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isDeclineLabel = text == "decline" || text == "reject" || text == "decline call" || text == "reject call" ||
                text == "dismiss" || desc == "decline" || desc == "reject" || desc == "decline call" ||
                desc == "reject call" || desc == "dismiss" || desc.contains("decline voice call") ||
                desc.contains("decline video call") || desc.contains("swipe to decline")

        val isDeclineId = viewId.contains("decline") || viewId.contains("reject") || viewId.contains("voice_call_decline") ||
                viewId.contains("video_call_decline") || viewId.contains("call_reject") || viewId.contains("reject_invite")

        if ((isDeclineLabel || isDeclineId) && (node.isClickable || node.parent?.isClickable == true || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) node else node.parent
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val match = findWhatsAppDeclineButton(child)
            if (match != null) return match
        }
        return null
    }

    fun extractWhatsAppCallerName(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""

        val candidates = mutableListOf<String>()
        collectCallerNameCandidates(root, candidates)

        for (cand in candidates) {
            val trimmed = cand.trim()
            if (trimmed.length >= 2) {
                val digits = trimmed.replace("[^0-9+]".toRegex(), "")
                if (digits.length >= 7) {
                    val contactName = ContactsAndCallManager(this).getContactNameFromNumber(trimmed)
                    if (!contactName.isNullOrBlank()) {
                        return contactName
                    }
                    return trimmed
                } else {
                    return trimmed
                }
            }
        }

        return ""
    }

    private fun collectCallerNameCandidates(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || list.isNotEmpty()) return

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val cand = if (text.isNotBlank()) text else desc
        val candLower = cand.lowercase()

        val isNoise = candLower in setOf(
            "whatsapp", "whatsapp voice call", "whatsapp video call", "incoming voice call",
            "incoming video call", "incoming call", "swipe up to answer", "swipe to answer",
            "answer", "decline", "accept", "reject", "dismiss", "reply", "remind me",
            "video call", "voice call", "calling...", "ringing..."
        )

        val isNameId = viewId.contains("contact_name") || viewId.contains("caller_name") ||
                viewId.contains("call_name") || viewId.contains("caller_id") || viewId.contains("name")

        if (isNameId && cand.isNotBlank() && !isNoise) {
            list.add(cand)
            return
        }

        if (!isNoise && cand.length >= 2 && !candLower.contains("whatsapp") && !candLower.contains("swipe")) {
            list.add(cand)
            return
        }

        for (i in 0 until node.childCount) {
            collectCallerNameCandidates(node.getChild(i), list)
        }
    }

    fun extractCallerFromNotificationText(text: String): String {
        var clean = text.trim()
        val prefixes = listOf(
            "incoming voice call from ", "incoming video call from ", "incoming call from ",
            "whatsapp voice call from ", "whatsapp video call from ", "whatsapp call from ",
            "incoming voice call: ", "incoming video call: ", "incoming call: ",
            "voice call from ", "video call from ", "call from "
        )
        for (prefix in prefixes) {
            if (clean.startsWith(prefix, ignoreCase = true)) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }
        clean = clean.replace(Regex("""(?i)\b(?:incoming\s+voice\s+call|incoming\s+video\s+call|incoming\s+call|whatsapp\s+call)\b"""), "").trim()
        clean = clean.trim(':', '-', ',').trim()

        if (clean.isNotBlank()) {
            val digits = clean.replace("[^0-9+]".toRegex(), "")
            if (digits.length >= 7) {
                val contactName = ContactsAndCallManager(this).getContactNameFromNumber(clean)
                if (!contactName.isNullOrBlank()) {
                    return contactName
                }
                return clean
            }
            return clean
        }
        return ""
    }

    fun performSwipeUpGesture(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val displayMetrics = resources.displayMetrics
                val startX = (displayMetrics.widthPixels / 2).toFloat()
                val startY = (displayMetrics.heightPixels * 0.85f)
                val endX = startX
                val endY = (displayMetrics.heightPixels * 0.25f)

                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 300)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                return dispatchGesture(gesture, null, null)
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    fun performSwipeDownGesture(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val displayMetrics = resources.displayMetrics
                val startX = (displayMetrics.widthPixels / 2).toFloat()
                val startY = (displayMetrics.heightPixels * 0.65f)
                val endX = startX
                val endY = (displayMetrics.heightPixels * 0.95f)

                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 300)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                return dispatchGesture(gesture, null, null)
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    suspend fun answerWhatsAppCall(): String {
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppPackage(root.packageName?.toString())) {
            return "This doesn't appear to be a WhatsApp incoming call."
        }

        val answerBtn = findWhatsAppAnswerButton(root)
        var actionTaken = false

        if (answerBtn != null) {
            actionTaken = performClickOnNode(answerBtn)
        }

        if (!actionTaken) {
            actionTaken = performSwipeUpGesture()
        }

        if (!actionTaken && answerBtn == null) {
            return "I can't find the WhatsApp answer button."
        }

        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            delay(400L)
            val currentRoot = rootInActiveWindow
            if (currentRoot != null && isWhatsAppPackage(currentRoot.packageName?.toString())) {
                if (hasActiveWhatsAppCallControls(currentRoot)) {
                    DeviceController.currentCallState = CallState.ACTIVE_WHATSAPP_CALL
                    finishIncomingCallSession(WhatsAppCallPhase.ANSWERED)
                    return "WhatsApp call answered."
                }
                if (findWhatsAppAnswerButton(currentRoot) == null && !hasWhatsAppIncomingCallIndicator(currentRoot)) {
                    DeviceController.currentCallState = CallState.ACTIVE_WHATSAPP_CALL
                    finishIncomingCallSession(WhatsAppCallPhase.ANSWERED)
                    return "WhatsApp call answered."
                }
            }
        }

        val finalRoot = rootInActiveWindow
        if (finalRoot != null && isWhatsAppPackage(finalRoot.packageName?.toString()) && !isWhatsAppIncomingCallScreen(finalRoot)) {
            DeviceController.currentCallState = CallState.ACTIVE_WHATSAPP_CALL
            finishIncomingCallSession(WhatsAppCallPhase.ANSWERED)
            return "WhatsApp call answered."
        }

        return "I couldn't answer the WhatsApp call."
    }

    suspend fun declineWhatsAppCall(): String {
        val root = rootInActiveWindow
        if (root == null || !isWhatsAppPackage(root.packageName?.toString())) {
            return "This doesn't appear to be a WhatsApp incoming call."
        }

        val declineBtn = findWhatsAppDeclineButton(root)
        var actionTaken = false

        if (declineBtn != null) {
            actionTaken = performClickOnNode(declineBtn)
        }

        if (!actionTaken) {
            actionTaken = performSwipeDownGesture()
        }

        if (!actionTaken && declineBtn == null) {
            return "I couldn't decline the WhatsApp call."
        }

        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            delay(400L)
            val currentRoot = rootInActiveWindow
            if (currentRoot == null || !isWhatsAppPackage(currentRoot.packageName?.toString()) || !isWhatsAppIncomingCallScreen(currentRoot)) {
                DeviceController.currentCallState = CallState.IDLE
                DeviceController.currentWhatsAppCaller = ""
                finishIncomingCallSession(WhatsAppCallPhase.DECLINED)
                return "WhatsApp call declined."
            }
        }

        val finalRoot = rootInActiveWindow
        if (finalRoot == null || !isWhatsAppPackage(finalRoot.packageName?.toString()) || !isWhatsAppIncomingCallScreen(finalRoot)) {
            DeviceController.currentCallState = CallState.IDLE
            DeviceController.currentWhatsAppCaller = ""
            finishIncomingCallSession(WhatsAppCallPhase.DECLINED)
            return "WhatsApp call declined."
        }

        return "I couldn't decline the WhatsApp call."
    }

    private fun hasActiveWhatsAppCallControls(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val combined = "$text $desc $viewId"

        if (combined.contains("end call") || combined.contains("mute") || combined.contains("speaker") ||
            combined.contains("bluetooth") || viewId.contains("end_call") || viewId.contains("mute_btn") ||
            viewId.contains("call_duration")) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (hasActiveWhatsAppCallControls(node.getChild(i))) return true
        }
        return false
    }

    // ----------------------------------------------------
    // SYSTEM NAVIGATION GESTURES
    // ----------------------------------------------------
    fun performBack(): String {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (success) "Going back." else "Could not go back."
    }

    fun performHome(): String {
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        return if (success) "Going home." else "Could not go home."
    }

    fun performRecents(): String {
        val success = performGlobalAction(GLOBAL_ACTION_RECENTS)
        return if (success) "Opening recent apps." else "Could not open recent apps."
    }

    fun openNotifications(): String {
        val success = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        return if (success) "Opening notifications panel." else "Could not open notifications."
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        clearVoiceOptions()
        callAudioSession.restore()
        callScope.cancel()
        if (instance == this) {
            instance = null
        }
    }
}

/**
 * Real [RingtoneAudioGateway] backed by [AudioManager]. Temporarily lowers the
 * ring and music streams and holds transient audio focus so the WhatsApp
 * ringtone ducks while the assistant speaks. Restoration is handled by
 * [IncomingCallAudioSession.restore].
 */
private class AndroidRingtoneAudioGateway(context: Context) : RingtoneAudioGateway {

    private val audioManager = try {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Exception) {
        null
    }
    private var focusRequest: AudioFocusRequest? = null

    override fun getRingVolume(): Int {
        return try {
            audioManager?.getStreamVolume(AudioManager.STREAM_RING) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun setRingVolume(volume: Int) {
        try {
            audioManager?.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
        } catch (_: Exception) {
        }
    }

    override fun getMusicVolume(): Int {
        return try {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun setMusicVolume(volume: Int) {
        try {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        } catch (_: Exception) {
        }
    }

    override fun requestDuckingFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {
        }
    }

    override fun abandonDuckingFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }
}
