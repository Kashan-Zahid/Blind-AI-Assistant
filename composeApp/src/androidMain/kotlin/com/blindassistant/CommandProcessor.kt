package com.blindassistant

import android.util.Log
import java.util.Locale
import java.util.regex.Pattern

class CommandProcessor(
    private val deviceController: DeviceController,
    private val aiClient: AiClient,
    private val prefManager: PreferenceManager? = null
) {

    private data class PendingWhatsAppSend(val contact: String, val message: String)
    private data class PendingContactChoice(
        val matches: List<Pair<String, String>>,
        val pendingMessage: String?,
        val originalQuery: String
    )
    private data class PendingCallChoice(
        val matches: List<Pair<String, String>>,
        val onSpeaker: Boolean
    )

    private var pendingWhatsAppSend: PendingWhatsAppSend? = null
    private var pendingWhatsAppRecipient: String? = null
    private var pendingContactChoice: PendingContactChoice? = null
    private var pendingCallChoice: PendingCallChoice? = null

    private val wordNumbers = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    private val ordinalWords = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10
    )

    suspend fun processCommand(rawInput: String): String {
        val trimmed = rawInput.trim()
        val input = trimmed.lowercase(Locale.ROOT)

        if (input.isBlank()) {
            return "I did not hear anything. Please speak a command."
        }

        val clean = input.trimEnd('?', '.', '!', ' ').trim()
        val normalized = stripConversationalFillers(clean)

        // ---------------------------------------------------------
        // 0a. PENDING CALL CONTACT DISAMBIGUATION ("option 1", "call option 2", "Ahmed Khan", "1", "2", or "cancel")
        // ---------------------------------------------------------
        val callChoice = pendingCallChoice
        if (callChoice != null) {
            if (isSendCancellationPhrase(clean) || isSendCancellationPhrase(normalized)) {
                pendingCallChoice = null
                return "Call cancelled."
            }
            var targetChoice: String = clean
            if (targetChoice.startsWith("call ")) {
                targetChoice = targetChoice.removePrefix("call ").trim()
            }
            val optIdx = parseOptionSelection(clean) ?: parseOptionSelection(normalized) ?: parseOptionSelection(targetChoice)
            var selected: Pair<String, String>? = null
            if (optIdx != null && optIdx in 1..callChoice.matches.size) {
                selected = callChoice.matches[optIdx - 1]
            } else {
                selected = callChoice.matches.firstOrNull {
                    it.first.contains(targetChoice, ignoreCase = true) || targetChoice.contains(it.first, ignoreCase = true)
                }
            }
            if (selected != null) {
                pendingCallChoice = null
                val (displayName, _) = selected
                return deviceController.callContactByName(displayName, callChoice.onSpeaker)
            }
        }

        // ---------------------------------------------------------
        // 0b. PENDING CONTACT DISAMBIGUATION ("option 1", "Ali Hussain", or "cancel")
        // ---------------------------------------------------------
        val contactChoice = pendingContactChoice
        if (contactChoice != null) {
            if (isSendCancellationPhrase(clean) || isSendCancellationPhrase(normalized)) {
                pendingContactChoice = null
                return "Message cancelled."
            }
            val optIdx = parseOptionSelection(clean) ?: parseOptionSelection(normalized)
            var selected: Pair<String, String>? = null
            if (optIdx != null && optIdx in 1..contactChoice.matches.size) {
                selected = contactChoice.matches[optIdx - 1]
            } else {
                selected = contactChoice.matches.firstOrNull {
                    it.first.contains(clean, ignoreCase = true) || clean.contains(it.first, ignoreCase = true)
                }
            }
            if (selected != null) {
                pendingContactChoice = null
                val (displayName, _) = selected
                if (!contactChoice.pendingMessage.isNullOrBlank()) {
                    return deviceController.sendWhatsApp(displayName, contactChoice.pendingMessage)
                } else {
                    pendingWhatsAppRecipient = displayName
                    return "What message should I send to $displayName?"
                }
            }
        }

        // ---------------------------------------------------------
        // 0c. PENDING MESSAGE INPUT ("What message should I send to Ali?")
        // ---------------------------------------------------------
        val recipient = pendingWhatsAppRecipient
        if (recipient != null) {
            pendingWhatsAppRecipient = null
            if (isSendCancellationPhrase(clean) || isSendCancellationPhrase(normalized)) {
                return "Message cancelled."
            }
            val cleanMessage = trimmed.trimEnd('.', '?', '!', ' ')
            pendingWhatsAppSend = PendingWhatsAppSend(recipient, cleanMessage)
            return "Ready to send to $recipient: '$cleanMessage'. Say send to confirm or cancel."
        }

        // ---------------------------------------------------------
        // 0d. PENDING WHATSAPP SEND CONFIRMATION ("Ready to send...")
        // ---------------------------------------------------------
        val pending = pendingWhatsAppSend
        if (pending != null) {
            pendingWhatsAppSend = null
            if (isSendConfirmationPhrase(clean) || isSendConfirmationPhrase(normalized)) {
                return deviceController.sendWhatsApp(pending.contact, pending.message)
            }
            if (isSendCancellationPhrase(clean) || isSendCancellationPhrase(normalized)) {
                return "Message cancelled."
            }
            // If user spoke another command, let it fall through and clear the pending send.
        } else if (isSendConfirmationPhrase(clean) || isSendConfirmationPhrase(normalized)) {
            val service = BlindAccessibilityService.instance
            if (service != null && service.isWhatsAppActive()) {
                return service.triggerSendOnCurrentScreen()
            }
            return "There is no pending message to send."
        }

        // ---------------------------------------------------------
        // 0c2. INCOMING MESSAGE VOICE REPLY ("Reply", "Reply to Sarah", "Answer message")
        // ---------------------------------------------------------
        val replyPhrases = setOf(
            "reply", "reply to message", "reply message", "answer message", "send reply",
            "reply to this", "reply to him", "reply to her", "respond", "reply back", "send a reply", "reply now"
        )
        val isReplyCommand = clean in replyPhrases || normalized in replyPhrases ||
                clean.startsWith("reply to ") || clean.startsWith("reply ") ||
                normalized.startsWith("reply to ") || normalized.startsWith("reply ")
        if (isReplyCommand) {
            val msg = BlindAccessibilityService.latestIncomingMessage
            val rawReplyTarget = when {
                clean.startsWith("reply to ") -> clean.removePrefix("reply to ").trim()
                clean.startsWith("reply ") -> clean.removePrefix("reply ").trim()
                normalized.startsWith("reply to ") -> normalized.removePrefix("reply to ").trim()
                normalized.startsWith("reply ") -> normalized.removePrefix("reply ").trim()
                else -> null
            }
            val explicitTarget = rawReplyTarget?.takeIf { it.isNotBlank() && it != "message" && it != "this" && it != "back" }
            val targetName = explicitTarget ?: msg?.sender
            if (targetName != null) {
                pendingWhatsAppRecipient = targetName
                return "What message should I send to $targetName?"
            } else {
                return "There is no recent message to reply to. Who would you like to message?"
            }
        }

        // ---------------------------------------------------------
        // 0e. VOICE OPTION SELECTION ("option 2", "number two", "second", "2", "play option 1")
        // ---------------------------------------------------------
        val optionIndex = parseOptionSelection(clean) ?: parseOptionSelection(normalized)
        if (optionIndex != null) {
            Log.d(
                "BlindAI_YT_OPTION",
                """
                OPTION_RECEIVED
                raw=$trimmed
                """.trimIndent()
            )
            Log.d(
                "BlindAI_YT_OPTION",
                """
                OPTION_PARSED
                index=$optionIndex
                """.trimIndent()
            )
            return deviceController.selectVoiceOption(optionIndex)
        }

        // ---------------------------------------------------------
        // 1. YOUTUBE AD SKIP & ACCESSIBILITY FOCUS (Instant Native)
        // ---------------------------------------------------------
        val skipAdPhrases = setOf(
            "skip ad", "skip ads", "skip the ad", "skip the ads", "close ad", "close ads",
            "skip advertisement", "skip the advertisement", "skip promo", "skip this ad",
            "close the ad", "dismiss ad", "bypass ad", "skip commercial"
        )
        if (matchesCommand(clean, normalized, skipAdPhrases)) {
            return deviceController.skipAd()
        }

        val nextPhrases = setOf("next", "next button", "next item", "next element", "go next", "move next", "focus next")
        if (matchesCommand(clean, normalized, nextPhrases)) {
            return deviceController.navigateNextNode()
        }

        val prevPhrases = setOf("previous", "previous button", "previous item", "previous element", "prev", "go previous", "move previous", "focus previous")
        if (matchesCommand(clean, normalized, prevPhrases)) {
            return deviceController.navigatePreviousNode()
        }

        val clickPhrases = setOf("click this", "click it", "click", "tap this", "tap it", "tap", "press this", "press it", "press", "select this", "activate this", "hit this")
        if (matchesCommand(clean, normalized, clickPhrases)) {
            return deviceController.clickFocusedNode()
        }

        // ---------------------------------------------------------
        // 2. YOUTUBE SEARCH & VOICE CONTROLS (Voice-First YouTube)
        // ---------------------------------------------------------
        val ytSearchPrefixes = listOf(
            "open youtube and search for ",
            "open youtube and search ",
            "search youtube for ",
            "search youtube ",
            "search on youtube for ",
            "search on youtube ",
            "find on youtube ",
            "look for on youtube ",
            "play on youtube ",
            "youtube search for ",
            "youtube search ",
            "youtube play ",
            "youtube find ",
            "watch on youtube "
        )
        for (prefix in ytSearchPrefixes) {
            val q = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (q != null && q.isNotBlank()) {
                return deviceController.searchYouTube(q)
            }
        }

        // "Find Atif Aslam songs on YouTube" / "Look for today's news on YouTube"
        val ytEndPattern = Pattern.compile("^(?:find|search for|look for|search|play|watch|listen to)\\s+(.+?)\\s+(?:on\\s+youtube|in\\s+youtube|on\\s+yt)$", Pattern.CASE_INSENSITIVE)
        val ytEndMatcher = ytEndPattern.matcher(clean)
        val endMatcher = if (ytEndMatcher.find()) ytEndMatcher else ytEndPattern.matcher(normalized).takeIf { it.find() }
        if (endMatcher != null) {
            val q = endMatcher.group(1)?.trim() ?: ""
            if (q.isNotBlank()) {
                return deviceController.searchYouTube(q)
            }
        }

        val openYtPhrases = setOf("open youtube", "launch youtube", "start youtube", "go to youtube", "youtube")
        if (matchesCommand(clean, normalized, openYtPhrases)) {
            return deviceController.launchApp("youtube")
        }

        // Quick trigger: "youtube <query>" (e.g. "youtube atif aslam")
        if (clean.startsWith("youtube ") || normalized.startsWith("youtube ")) {
            val q = (if (clean.startsWith("youtube ")) clean.removePrefix("youtube ") else normalized.removePrefix("youtube ")).trim()
            if (q.isNotBlank() && !q.startsWith("debug") && !q.startsWith("results") && !q.startsWith("search")) {
                return deviceController.searchYouTube(q)
            }
        }

        // Diagnostic YouTube Command
        val debugYtPhrases = setOf("debug youtube results", "debug youtube", "debug search results", "youtube debug", "debug youtube search")
        if (matchesCommand(clean, normalized, debugYtPhrases)) {
            return deviceController.debugYouTubeResults()
        }

        // Results navigation
        val readResultsPhrases = setOf(
            "read results", "read the results", "list results", "tell me results", "show results",
            "what are the results", "read search results", "search results", "results"
        )
        if (matchesCommand(clean, normalized, readResultsPhrases)) {
            return deviceController.readYouTubeResults()
        }
        val nextResultPhrases = setOf("next result", "next search result", "next option", "next video result")
        if (matchesCommand(clean, normalized, nextResultPhrases)) {
            return deviceController.nextYouTubeResult()
        }
        val prevResultPhrases = setOf("previous result", "previous search result", "prev result", "previous option")
        if (matchesCommand(clean, normalized, prevResultPhrases)) {
            return deviceController.previousYouTubeResult()
        }

        // Playback
        val pausePhrases = setOf("pause the video", "pause video", "pause", "pause playback", "pause music", "pause song")
        if (matchesCommand(clean, normalized, pausePhrases)) {
            return deviceController.pauseMediaPlayback()
        }
        val playThisPhrases = setOf("play this", "play this video", "play current", "play current video", "select this")
        if (matchesCommand(clean, normalized, playThisPhrases)) {
            if (deviceController.hasActiveVoiceOptions()) {
                return deviceController.playSelectedYouTubeOption()
            }
            return deviceController.resumeMediaPlayback()
        }
        if (matchesCommand(clean, normalized, setOf("play it"))) {
            val latestMsg = BlindAccessibilityService.latestIncomingMessage
            if (latestMsg != null && latestMsg.isVoiceNote) {
                return deviceController.playVoiceNote()
            }
            if (deviceController.hasActiveVoiceOptions()) {
                return deviceController.playSelectedYouTubeOption()
            }
            return deviceController.resumeMediaPlayback()
        }
        val resumePhrases = setOf(
            "play the video", "play video", "play", "resume the video", "resume video", "resume",
            "unpause", "continue video", "continue playback", "resume playback"
        )
        if (matchesCommand(clean, normalized, resumePhrases)) {
            return deviceController.resumeMediaPlayback()
        }
        val nextVidPhrases = setOf(
            "go to the next video", "next video", "play next video", "skip video", "play next",
            "next song", "next track", "skip to next video"
        )
        if (matchesCommand(clean, normalized, nextVidPhrases)) {
            return deviceController.playNextYouTubeVideo()
        }
        val prevVidPhrases = setOf(
            "go to the previous video", "previous video", "play previous video", "last video",
            "play previous", "previous song", "previous track"
        )
        if (matchesCommand(clean, normalized, prevVidPhrases)) {
            return deviceController.playPreviousYouTubeVideo()
        }
        val stopPhrases = setOf(
            "stop the video", "stop video", "stop", "stop playing", "stop playback", "stop music",
            "end video", "halt playback"
        )
        if (matchesCommand(clean, normalized, stopPhrases)) {
            return deviceController.stopMediaPlayback()
        }
        val replayPhrases = setOf(
            "replay", "replay video", "replay the video", "restart video", "restart the video",
            "play from beginning", "start over", "play again"
        )
        if (matchesCommand(clean, normalized, replayPhrases)) {
            return deviceController.replayYouTubeVideo()
        }
        val seekFwdPhrases = setOf(
            "seek forward 10 seconds", "seek forward", "fast forward 10 seconds", "fast forward",
            "forward 10 seconds", "forward", "jump forward 10 seconds", "skip 10 seconds"
        )
        if (matchesCommand(clean, normalized, seekFwdPhrases)) {
            return deviceController.seekForwardYouTube(10)
        }
        val seekBwdPhrases = setOf(
            "seek backward 10 seconds", "seek backward", "rewind 10 seconds", "rewind",
            "backward 10 seconds", "backward", "jump backward 10 seconds"
        )
        if (matchesCommand(clean, normalized, seekBwdPhrases)) {
            return deviceController.seekBackwardYouTube(10)
        }

        // Subtitles / Captions
        val subsOnPhrases = setOf("turn subtitles on", "turn captions on", "subtitles on", "captions on", "enable subtitles", "enable captions", "show subtitles", "show captions")
        if (matchesCommand(clean, normalized, subsOnPhrases)) {
            return deviceController.setYouTubeCaptions(true)
        }
        val subsOffPhrases = setOf("turn subtitles off", "turn captions off", "subtitles off", "captions off", "disable subtitles", "disable captions", "hide subtitles", "hide captions")
        if (matchesCommand(clean, normalized, subsOffPhrases)) {
            return deviceController.setYouTubeCaptions(false)
        }
        val readSubsPhrases = setOf(
            "read subtitles", "read captions", "what are the subtitles", "what do the captions say",
            "read the subtitles", "read the captions", "tell me subtitles", "subtitles"
        )
        if (matchesCommand(clean, normalized, readSubsPhrases)) {
            return deviceController.readYouTubeSubtitles()
        }

        // Comments (read checked before open)
        val readCommentsPhrases = setOf("read comments", "read the comments")
        if (matchesCommand(clean, normalized, readCommentsPhrases)) {
            return deviceController.readYouTubeComments()
        }
        val openCommentsPhrases = setOf("open comments", "show comments", "view comments", "comments")
        if (matchesCommand(clean, normalized, openCommentsPhrases)) {
            return deviceController.openYouTubeComments()
        }
        val closeCommentsPhrases = setOf("close comments", "hide comments", "dismiss comments")
        if (matchesCommand(clean, normalized, closeCommentsPhrases)) {
            return deviceController.closeYouTubeComments()
        }

        val whatPlayingPhrases = setOf(
            "what is playing", "what's playing", "what is playing now", "what's playing right now",
            "what are we watching", "what am i watching", "currently playing"
        )
        if (matchesCommand(clean, normalized, whatPlayingPhrases)) {
            return deviceController.getYouTubeCurrentlyPlaying()
        }
        val titlePhrases = setOf(
            "what is the title", "what's the title", "what is this video called", "video title",
            "tell me the title", "what is the video title", "title"
        )
        if (matchesCommand(clean, normalized, titlePhrases)) {
            return deviceController.getYouTubeVideoTitle()
        }

        // ---------------------------------------------------------
        // 3. INCOMING CALL & LOUDSPEAKER VOICE CONTROLS
        // ---------------------------------------------------------
        val callerPhrases = setOf(
            "who is calling", "who's calling", "who is this calling", "who is calling me",
            "who's calling me", "who is it", "who is on the call", "whose call is this",
            "caller name", "who is calling now", "who is this", "caller", "check caller", "who's on the line"
        )
        if (matchesCommand(clean, normalized, callerPhrases)) {
            return deviceController.whoIsCalling()
        }
        val declinePhrases = setOf(
            "reject the call", "reject call", "reject", "hang up", "end the call", "end call", "end",
            "decline the call", "decline call", "decline", "cut call", "cancel call", "dismiss call",
            "don't answer", "dont answer", "drop call", "ignore call", "hang up the call"
        )
        if (matchesCommand(clean, normalized, declinePhrases)) {
            return deviceController.declineIncomingCall()
        }
        val answerPhrases = setOf(
            "answer the call", "answer call", "answer", "pick up the call", "pick up call",
            "pick up", "accept call", "accept the call", "accept", "take call", "take the call",
            "receive call", "receive the call"
        )
        if (matchesCommand(clean, normalized, answerPhrases)) {
            return deviceController.answerIncomingCall()
        }
        val speakerOnPhrases = setOf(
            "turn on speakerphone", "turn on the speakerphone", "turn on loudspeaker", "turn on speaker",
            "speakerphone on", "loudspeaker on", "speaker on", "enable speaker", "loudspeaker"
        )
        if (matchesCommand(clean, normalized, speakerOnPhrases)) {
            return deviceController.setSpeakerphone(true)
        }
        val speakerOffPhrases = setOf(
            "turn off speakerphone", "turn off the speakerphone", "turn off loudspeaker", "turn off speaker",
            "speakerphone off", "loudspeaker off", "speaker off", "disable speaker"
        )
        if (matchesCommand(clean, normalized, speakerOffPhrases)) {
            return deviceController.setSpeakerphone(false)
        }

        // ---------------------------------------------------------
        // 4. TIME & DATE (Exact local device queries)
        // ---------------------------------------------------------
        if (isTimeAndDateCommand(clean) || isTimeAndDateCommand(normalized)) {
            return deviceController.getTimeAndDate()
        }
        if (isLocalTimeCommand(clean) || isLocalTimeCommand(normalized)) {
            return deviceController.getCurrentTime()
        }
        if (isLocalDateCommand(clean) || isLocalDateCommand(normalized)) {
            return deviceController.getCurrentDate()
        }

        // ---------------------------------------------------------
        // 5. BATTERY STATUS (Exact device battery queries)
        // ---------------------------------------------------------
        if (isBatteryCommand(clean) || isBatteryCommand(normalized)) {
            return deviceController.getBatteryStatus()
        }

        // ---------------------------------------------------------
        // 6. FLASHLIGHT / TORCH (Off checked before On)
        // ---------------------------------------------------------
        val torchOffPhrases = setOf(
            "turn off the flashlight", "turn off flashlight", "switch off the flashlight", "switch off flashlight",
            "turn the torch off", "turn off the torch", "turn off torch", "flashlight off", "torch off",
            "light off", "turn off light", "turn off the light", "disable flashlight", "turn flashlight off",
            "turn torch off", "turn light off", "switch light off", "lights off", "turn off flash", "flash off"
        )
        if (matchesCommand(clean, normalized, torchOffPhrases)) {
            return deviceController.toggleFlashlight(false)
        }

        val torchOnPhrases = setOf(
            "turn on the flashlight", "turn on flashlight", "switch on the flashlight", "switch on flashlight",
            "turn the torch on", "turn on the torch", "turn on torch", "flashlight on", "torch on",
            "light on", "turn on light", "turn on the light", "enable flashlight", "turn flashlight on",
            "turn torch on", "turn light on", "switch light on", "lights on", "flashlight", "torch", "light",
            "turn on flash", "flash on"
        )
        if (matchesCommand(clean, normalized, torchOnPhrases)) {
            return deviceController.toggleFlashlight(true)
        }

        // ---------------------------------------------------------
        // 7. VOLUME CONTROLS
        // ---------------------------------------------------------
        val volUpPhrases = setOf(
            "increase the volume", "increase volume", "volume up", "turn up the volume", "turn up volume",
            "turn volume up", "raise volume", "boost volume", "make it louder", "louder", "volume louder",
            "higher volume", "more volume", "sound up"
        )
        if (matchesCommand(clean, normalized, volUpPhrases)) {
            return deviceController.adjustVolume(increase = true)
        }

        val volDownPhrases = setOf(
            "decrease the volume", "decrease volume", "volume down", "turn down the volume", "turn down volume",
            "turn volume down", "lower volume", "reduce volume", "make it quieter", "quieter", "volume quieter",
            "lower the volume", "less volume", "softer", "sound down"
        )
        if (matchesCommand(clean, normalized, volDownPhrases)) {
            return deviceController.adjustVolume(increase = false)
        }

        val mutePhrases = setOf(
            "mute the volume", "mute volume", "mute", "silence", "turn off volume", "turn sound off", "quiet",
            "silence phone", "mute sound"
        )
        if (matchesCommand(clean, normalized, mutePhrases)) {
            return deviceController.muteVolume(true)
        }

        val unmutePhrases = setOf(
            "unmute the volume", "unmute volume", "unmute", "turn on volume", "turn sound on", "sound on", "restore volume"
        )
        if (matchesCommand(clean, normalized, unmutePhrases)) {
            return deviceController.muteVolume(false)
        }

        val volStatusPhrases = setOf(
            "volume", "check volume", "check my volume", "current volume", "what is the volume",
            "what is my volume", "volume status", "volume level", "sound level", "what's the volume"
        )
        if (matchesCommand(clean, normalized, volStatusPhrases)) {
            return deviceController.getVolumeStatus()
        }

        val volPattern = Pattern.compile("^(?:set\\s+)?volume(?:\\s+to)?\\s+(\\d+)(?:\\s*%)?$")
        val volMatcher = volPattern.matcher(clean)
        val vMatcher = if (volMatcher.find()) volMatcher else volPattern.matcher(normalized).takeIf { it.find() }
        if (vMatcher != null) {
            val pct = vMatcher.group(1)?.toIntOrNull()
            if (pct != null) {
                return deviceController.setVolumePercent(pct)
            }
        }

        // ---------------------------------------------------------
        // 8. EMERGENCY SOS & SIMPLIFIED COMMANDS HELP GUIDE
        // ---------------------------------------------------------
        val emergencyPhrases = setOf(
            "send an emergency sos", "send emergency sos", "send an sos", "send sos",
            "i need emergency help", "i need help", "emergency sos", "emergency alert",
            "send emergency alert", "trigger emergency", "trigger emergency sos", "call emergency",
            "i am in danger", "emergency", "help me", "help", "sos", "danger"
        )
        if (matchesCommand(clean, normalized, emergencyPhrases)) {
            return deviceController.triggerEmergencySOS()
        }

        val commandsHelpPhrases = setOf(
            "what can you do", "what can i say", "commands", "help commands", "list commands",
            "help with commands", "show commands", "voice commands", "all commands", "how to use",
            "what are the commands", "what commands can i use", "guide"
        )
        if (matchesCommand(clean, normalized, commandsHelpPhrases)) {
            return "Here are simple commands you can say: Time, Date, Battery, Torch on or off, Volume up or down, Where am I, Read screen, Look around, Count money, Read text, Call contact, WhatsApp contact, Open YouTube, or Emergency for help."
        }

        // ---------------------------------------------------------
        // 9. GPS LOCATION & NAVIGATION ("Where Am I?")
        // ---------------------------------------------------------
        if (isLocationCommand(clean) || isLocationCommand(normalized)) {
            return deviceController.getCurrentLocation()
        }

        // 9b. PEDESTRIAN WALKING NAVIGATION
        val navPrefixes = listOf(
            "navigate to ", "walking directions to ", "walk to ", "take me to ",
            "guide me to ", "directions to ", "how do i walk to ", "how to get to ",
            "lead me to ", "route to "
        )
        for (prefix in navPrefixes) {
            val destination = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (destination != null && destination.isNotBlank() && !destination.startsWith("node") && destination != "screen") {
                return deviceController.startWalkingNavigation(destination)
            }
        }

        // Emergency Contact Management
        val setEmergPrefixes = listOf("set emergency contact ", "save emergency contact ")
        for (prefix in setEmergPrefixes) {
            val contactArg = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (contactArg != null && contactArg.isNotBlank()) {
                return deviceController.setEmergencyContact(contactArg)
            }
        }

        val emergContactPhrases = setOf(
            "who is my emergency contact", "check emergency contact", "emergency contact info",
            "my emergency contact", "what is my emergency contact", "emergency contact", "emergency phone number"
        )
        if (matchesCommand(clean, normalized, emergContactPhrases)) {
            return deviceController.getEmergencyContact()
        }

        // ---------------------------------------------------------
        // 10. ALARMS & TIMERS
        // ---------------------------------------------------------
        if (isAlarmCommand(clean) || isAlarmCommand(normalized)) {
            val alarmInput = if (isAlarmCommand(clean)) clean else normalized
            return deviceController.processAlarmCommand(alarmInput)
        }

        val timerPattern = Pattern.compile("(?:set\\s+(?:a\\s+)?)?timer(?:\\s+for)?\\s+(\\d+)\\s*(second|seconds|minute|minutes|hour|hours|min|sec|hr)")
        val timerMatcher = timerPattern.matcher(clean)
        val tMatch = if (timerMatcher.find()) timerMatcher else timerPattern.matcher(normalized).takeIf { it.find() }
        if (tMatch != null) {
            val count = tMatch.group(1)?.toIntOrNull() ?: 1
            val unit = tMatch.group(2) ?: "minute"
            val totalSeconds = when {
                unit.startsWith("sec") -> count
                unit.startsWith("min") -> count * 60
                unit.startsWith("hour") || unit.startsWith("hr") -> count * 3600
                else -> count * 60
            }
            return deviceController.setTimer(totalSeconds, "Blind Assistant Timer")
        }

        // ---------------------------------------------------------
        // 11. VISION & CAMERA DESCRIPTIONS
        // ---------------------------------------------------------
        // Protect removed features so they route directly to AI
        if (clean in listOf("read prescription", "record lecture", "summarize my emails", "summarize emails") ||
            normalized in listOf("read prescription", "record lecture", "summarize my emails", "summarize emails")) {
            return aiClient.ask(trimmed)
        }

        val closeCameraPhrases = setOf("close camera", "dismiss camera", "hide camera", "close camera popup")
        if (matchesCommand(clean, normalized, closeCameraPhrases)) {
            AndroidVoiceService.dismissCameraPopupGlobally()
            return "Camera closed."
        }

        val describeAroundPhrases = setOf(
            "describe around me", "describe my surroundings", "what is around me", "tell me what's around me",
            "tell me what is around me", "what's around me", "describe what you see", "what is in front of me",
            "what's in front of me", "what do you see in front of me", "scan room", "scan surroundings",
            "describe scene", "look around", "what do you see", "describe surroundings", "see for me",
            "describe camera", "describe environment", "describe", "look", "see", "what's in front",
            "what is in front", "camera", "look around me", "view surroundings", "open camera", "show camera",
            "camera preview", "camera popup", "start camera", "take photo", "take picture", "capture image",
            "photo", "picture", "snapshot", "snap"
        )
        if (matchesCommand(clean, normalized, describeAroundPhrases)) {
            return deviceController.describeAroundMe()
        }

        val obstaclePhrases = setOf(
            "is there any obstacle", "watch my step", "any danger", "check for obstacles", "am i safe",
            "hazard detection", "check hazards", "detect danger", "any hazard", "check obstacles",
            "obstacles", "any obstacles", "hazard", "hazards", "is it safe"
        )
        if (matchesCommand(clean, normalized, obstaclePhrases)) {
            return deviceController.describeCameraScene("obstacles")
        }

        // 11b. DOCUMENT & MAIL READING (Checked before generic OCR)
        val docPhrases = setOf(
            "read document", "read this document", "read the document", "read paper", "read the paper",
            "read letter", "read this letter", "read my mail", "read mail", "read bill", "read receipt",
            "document"
        )
        if (matchesCommand(clean, normalized, docPhrases)) {
            return deviceController.readDocument()
        }

        // 11c. OCR & TEXT READING (Signs, Labels, Packages)
        val ocrPhrases = setOf(
            "read text", "read this text", "read the text", "read text in front of me", "read text on camera",
            "read label", "read this label", "read the label", "read product label", "read package",
            "read sign", "read the sign", "read sign board", "read billboard", "read signpost",
            "what does this say", "what does the label say", "what does the sign say", "what is written here",
            "what's written here", "read writing", "ocr", "scan text", "recognize text", "read words",
            "read this"
        )
        if (matchesCommand(clean, normalized, ocrPhrases)) {
            return deviceController.readTextOCR()
        }

        // 11d. CURRENCY & MONEY RECOGNITION
        val currencyPhrases = setOf(
            "count my money", "how much money is this", "how much cash is this", "how much is this note",
            "what note is this", "what bill is this", "read currency", "identify currency", "identify money",
            "check money", "check note", "read banknote", "detect currency", "recognize currency",
            "count money", "how much money", "money", "currency", "cash", "count cash", "check cash"
        )
        if (matchesCommand(clean, normalized, currencyPhrases)) {
            return deviceController.identifyCurrency()
        }

        // 11e. COLOR DETECTOR (Clothes matching, item colors)
        val colorPhrases = setOf(
            "what color is this", "what's the color", "what color is it", "detect color", "check color",
            "identify color", "what color am i holding", "what color is my shirt", "what color is my jacket",
            "what color are my clothes", "match my clothes", "color", "what color"
        )
        if (matchesCommand(clean, normalized, colorPhrases)) {
            return deviceController.detectColor()
        }

        // 11f. PRODUCT & MEDICINE IDENTIFIER
        val productPhrases = setOf(
            "what product is this", "what is this product", "identify product", "what am i holding",
            "what medicine is this", "identify this item", "what is in my hand", "what is this item",
            "identify item", "identify food", "what is this", "product", "medicine", "identify medicine"
        )
        if (matchesCommand(clean, normalized, productPhrases)) {
            return deviceController.identifyProduct()
        }

        // 11g. FIND OBJECT / ITEM FOR BLIND USER
        val findObjectPrefixes = listOf(
            "find my ", "where is my ", "where are my ", "look for my ", "search for my ",
            "find the ", "where is the ", "look for the ", "search for the ",
            "find a ", "locate my ", "locate the "
        )
        for (prefix in findObjectPrefixes) {
            val target = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (target != null && target.isNotBlank() && target !in listOf("way", "phone number", "contact", "options", "volume")) {
                return deviceController.findObject(target)
            }
        }

        // ---------------------------------------------------------
        // 12. SCREEN READING (Any App, WhatsApp, YouTube, Web)
        // ---------------------------------------------------------
        val readScreenPhrases = setOf(
            "read the screen", "read screen", "what is on the screen", "what is on screen",
            "what's on my screen", "what's on the screen", "read page", "read my screen",
            "read text on screen", "what does the screen say", "screen", "read display", "read phone screen"
        )
        if (matchesCommand(clean, normalized, readScreenPhrases)) {
            return deviceController.readScreen()
        }

        // ---------------------------------------------------------
        // 13. GENERIC VOICE OPTIONS (Reusable selection layer)
        // ---------------------------------------------------------
        val genericOptionsPhrases = setOf(
            "what are my options", "what options are there", "list options", "read options",
            "read the options", "give me options", "tell me my options", "options"
        )
        if (matchesCommand(clean, normalized, genericOptionsPhrases)) {
            return deviceController.collectGenericOptions()
        }

        // ---------------------------------------------------------
        // 14. CLICK BUTTON / TAP ELEMENT BY VOICE
        // ---------------------------------------------------------
        val clickPattern = Pattern.compile("^(?:click|tap|press)\\s+(?:on\\s+)?(?:the\\s+)?(.+)$")
        val clickMatcher = clickPattern.matcher(clean)
        val cMatcher = if (clickMatcher.find()) clickMatcher else clickPattern.matcher(normalized).takeIf { it.find() }
        if (cMatcher != null) {
            val target = cMatcher.group(1)?.trim() ?: ""
            if (target.isNotBlank() && target !in listOf("mic", "screen", "this", "it", "button", "item", "element")) {
                return deviceController.clickButton(target)
            }
        }

        // ---------------------------------------------------------
        // 15. TYPE TEXT INTO ACTIVE INPUT FIELD
        // ---------------------------------------------------------
        val typePattern = Pattern.compile("^(?:type|write|enter)\\s+(?:text\\s+)?(.+)$")
        val typeMatcher = typePattern.matcher(clean)
        val typMatcher = if (typeMatcher.find()) typeMatcher else typePattern.matcher(normalized).takeIf { it.find() }
        if (typMatcher != null) {
            val textToType = typMatcher.group(1)?.trim() ?: ""
            if (textToType.isNotBlank()) {
                return deviceController.typeText(textToType)
            }
        }

        // ---------------------------------------------------------
        // 16. SCROLLING (Page up / Page down)
        // ---------------------------------------------------------
        val scrollDownPhrases = setOf("scroll down", "swipe down", "page down", "scroll down screen", "scroll more", "scroll")
        if (matchesCommand(clean, normalized, scrollDownPhrases)) {
            return deviceController.scroll(forward = true)
        }
        val scrollUpPhrases = setOf("scroll up", "swipe up", "page up", "scroll up screen")
        if (matchesCommand(clean, normalized, scrollUpPhrases)) {
            return deviceController.scroll(forward = false)
        }

        // ---------------------------------------------------------
        // 17. SYSTEM GESTURES (Back, Home, Notifications, Exit)
        // ---------------------------------------------------------
        val backPhrases = setOf("go back", "back", "press back", "navigate back", "go back screen", "back button", "return")
        if (matchesCommand(clean, normalized, backPhrases)) {
            return deviceController.performBack()
        }
        val homePhrases = setOf("go home", "home", "press home", "return to home", "open home", "go to home", "home screen", "home button")
        if (matchesCommand(clean, normalized, homePhrases)) {
            return deviceController.performHome()
        }
        val notifPhrases = setOf("open notifications", "show notifications", "read notifications", "notifications panel", "notifications", "notification")
        if (matchesCommand(clean, normalized, notifPhrases)) {
            return deviceController.openNotificationsPanel()
        }
        val exitPhrases = setOf(
            "exit", "exit app", "close app", "quit", "minimize", "exit assistant",
            "normal android", "switch to normal android", "leave assistant", "close assistant", "back to android", "close", "leave"
        )
        if (matchesCommand(clean, normalized, exitPhrases)) {
            return deviceController.exitToNormalAndroid()
        }

        // ---------------------------------------------------------
        // 18. PHONE CALLS & CONTACTS (Exact Contact Matching & Disambiguation)
        // ---------------------------------------------------------
        val callPrefixes = listOf("call ", "dial ", "phone ", "make a call to ", "place a call to ")
        for (prefix in callPrefixes) {
            val rawTarget = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (rawTarget != null && rawTarget.isNotBlank() && !rawTarget.startsWith("node") && !rawTarget.startsWith("option")) {
                var target = rawTarget
                val onSpeaker = target.contains("on speaker") || target.contains("on loudspeaker") || target.contains("loudspeaker") || target.endsWith(" speaker")
                target = target
                    .replace("on speaker", "")
                    .replace("on loudspeaker", "")
                    .replace("on his phone", "")
                    .replace("on her phone", "")
                    .replace("on their phone", "")
                    .replace("on mobile", "")
                    .replace("on cell", "")
                    .replace("loudspeaker", "")
                    .replace("speaker", "")
                    .trim()

                val isPhone = deviceController.contactsAndCallManager.isPhoneNumber(target)
                if (isPhone) {
                    val normalizedNumber = deviceController.contactsAndCallManager.normalizePhoneNumber(target) ?: target
                    return deviceController.makePhoneCall(normalizedNumber, onSpeaker)
                } else {
                    val matches = deviceController.findMatchingContacts(target)
                    if (matches.size > 1) {
                        val exact = matches.firstOrNull { it.first.equals(target, ignoreCase = true) }
                        if (exact != null && matches.count { it.first.equals(target, ignoreCase = true) } == 1) {
                            return deviceController.callContactByName(exact.first, onSpeaker)
                        }
                        pendingCallChoice = PendingCallChoice(matches.take(5), onSpeaker)
                        val choicesStr = matches.take(5).map { it.first }.joinToString(" and ")
                        val prompt = if (matches.size == 2) "Say 1 or 2." else "Say 1, 2, or 3."
                        return "I found $choicesStr. $prompt"
                    } else if (matches.size == 1) {
                        val (displayName, _) = matches.first()
                        return deviceController.callContactByName(displayName, onSpeaker)
                    } else {
                        val capTarget = target.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        return deviceController.callContactByName(capTarget, onSpeaker)
                    }
                }
            }
        }

        // ---------------------------------------------------------
        // 19. WHATSAPP MESSAGING (100% Local Resolution & Send)
        // ---------------------------------------------------------
        val waIntent = extractWhatsAppIntent(clean) ?: extractWhatsAppIntent(normalized)
        if (waIntent != null) {
            val (contactRaw, messageRaw, messageExplicit) = waIntent
            val rawContact = contactRaw.trim().trimEnd(':', ',', '.')
            val message = messageRaw?.trim()?.trimStart(':', ',', ' ')?.trim()

            val isPhone = deviceController.contactsAndCallManager.isPhoneNumber(rawContact)

            if (isPhone) {
                val normalizedPhone = deviceController.contactsAndCallManager.normalizePhoneNumber(rawContact)
                if (normalizedPhone == null) {
                    return "That does not look like a valid phone number."
                }
                if (messageExplicit && !message.isNullOrBlank()) {
                    return deviceController.sendWhatsApp(rawContact, message)
                } else {
                    pendingWhatsAppRecipient = rawContact
                    return "What message should I send to $rawContact?"
                }
            } else {
                val contact = rawContact.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                // Resolve contact locally
                val matches = deviceController.findMatchingContacts(contact)
                if (matches.size > 1 && matches.none { it.first.equals(contact, ignoreCase = true) }) {
                    pendingContactChoice = PendingContactChoice(matches, if (messageExplicit) message else null, contact)
                    val countWord = when (matches.size) {
                        2 -> "two"
                        3 -> "three"
                        4 -> "four"
                        5 -> "five"
                        else -> "${matches.size}"
                    }
                    val optionsStr = matches.take(5).mapIndexed { i, m ->
                        "Option ${i + 1}: ${m.first}"
                    }.joinToString(". ")
                    val promptChoices = if (matches.size == 2) "Say option 1 or option 2." else "Say option 1, 2, or 3."
                    return "I found $countWord contacts named $contact. $optionsStr. $promptChoices"
                }

                val resolvedName = if (matches.isNotEmpty()) {
                    (matches.firstOrNull { it.first.equals(contact, ignoreCase = true) } ?: matches.first()).first
                } else {
                    contact
                }

                if (messageExplicit && !message.isNullOrBlank()) {
                    return deviceController.sendWhatsApp(resolvedName, message)
                } else {
                    pendingWhatsAppRecipient = resolvedName
                    return "What message should I send to $resolvedName?"
                }
            }
        }

        // ---------------------------------------------------------
        // 19b. INCOMING MESSAGES & VOICE NOTE TRANSCRIBER
        // ---------------------------------------------------------
        val readMsgPhrases = setOf(
            "read last message", "read the last message", "read latest message", "what was the last message",
            "who messaged me", "read message", "read messages", "check messages", "any new messages",
            "what's the last message", "messages", "last message", "check message",
            "what is the message", "what's the message", "what did they say", "read it", "tell me the message",
            "speak the message", "read whatsapp message", "read whatsapp"
        )
        if (matchesCommand(clean, normalized, readMsgPhrases)) {
            return deviceController.getLastMessageInfo()
        }

        val playVoiceNotePhrases = setOf(
            "play voice note", "play the voice note", "listen to voice note", "play audio message",
            "play voice message", "listen to voice message", "voice note", "play audio",
            "play it", "play", "play the message", "listen to message", "play voice", "play latest voice note"
        )
        if (matchesCommand(clean, normalized, playVoiceNotePhrases)) {
            return deviceController.playVoiceNote()
        }

        val transcribeVoiceNotePhrases = setOf(
            "transcribe voice note", "transcribe the voice note", "summarize voice note", "read voice note",
            "what does the voice note say", "transcribe audio", "summarize the voice note", "transcribe audio message"
        )
        if (matchesCommand(clean, normalized, transcribeVoiceNotePhrases)) {
            return deviceController.transcribeVoiceNote()
        }

        // ---------------------------------------------------------
        // 19c. SMS MESSAGING
        // ---------------------------------------------------------
        val smsPattern = Pattern.compile("^(?:send\\s+(?:an?\\s+)?sms(?:\\s+message)?|send\\s+(?:a\\s+)?text(?:\\s+message)?|text)\\s+(?:to\\s+)?([^:]+?)(?:\\s*:\\s*|\\s+saying\\s+|\\s+that\\s+|\\s+message\\s+)(.+)$", Pattern.CASE_INSENSITIVE)
        val smsMatcher = smsPattern.matcher(clean)
        val sMatcher = if (smsMatcher.find()) smsMatcher else smsPattern.matcher(normalized).takeIf { it.find() }
        if (sMatcher != null) {
            val contact = sMatcher.group(1)?.trim() ?: ""
            val msg = sMatcher.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return deviceController.sendSMS(contact, msg)
            }
        }

        // ---------------------------------------------------------
        // 20. APP LAUNCHING
        // ---------------------------------------------------------
        val openPattern = Pattern.compile("^(?:open|launch|start|go to)\\s+(.+)$")
        val openMatcher = openPattern.matcher(clean)
        val oMatcher = if (openMatcher.find()) openMatcher else openPattern.matcher(normalized).takeIf { it.find() }
        if (oMatcher != null) {
            val appTarget = oMatcher.group(1)?.trim() ?: ""
            if (appTarget.isNotBlank()) {
                if (appTarget.contains("accessibility")) {
                    return deviceController.openAccessibilitySettings()
                }
                if (appTarget.contains("wifi") || appTarget.contains("wi-fi")) {
                    return deviceController.openWifiSettings()
                }
                if (appTarget.contains("bluetooth")) {
                    return deviceController.openBluetoothSettings()
                }
                return deviceController.launchApp(appTarget)
            }
        }

        // ---------------------------------------------------------
        // 21. REAL-TIME WEB SEARCH & DEEP RESEARCH
        // ---------------------------------------------------------
        val researchPrefixes = listOf("deep research on ", "deep research about ", "deep research ", "research on ", "research about ", "research ")
        for (prefix in researchPrefixes) {
            val topic = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (topic != null && topic.isNotBlank()) {
                return deviceController.conductDeepResearch(topic)
            }
        }

        val searchPrefixes = listOf(
            "search the web for ", "search online for ", "search web for ", "web search for ",
            "search the internet for ", "search for ", "google "
        )
        for (prefix in searchPrefixes) {
            val query = when {
                clean.startsWith(prefix) -> clean.removePrefix(prefix).trim()
                normalized.startsWith(prefix) -> normalized.removePrefix(prefix).trim()
                else -> null
            }
            if (query != null && query.isNotBlank() && !query.startsWith("youtube") && !query.startsWith("my ")) {
                return deviceController.searchLiveWeb(query)
            }
        }

        val weatherPhrases = setOf(
            "weather", "what is the weather", "what's the weather", "current weather", "weather forecast",
            "check weather", "weather report", "what's the weather today", "how is the weather", "weather today",
            "forecast", "how's the weather"
        )
        if (matchesCommand(clean, normalized, weatherPhrases)) {
            return deviceController.getLiveWeather()
        }

        // ---------------------------------------------------------
        // 22. MULTI-LANGUAGE TRANSLATION
        // ---------------------------------------------------------
        val translatePattern = Pattern.compile("^translate\\s+(.+)\\s+(?:in|into|to)\\s+([a-zA-Z]+)$")
        val translateMatcher = translatePattern.matcher(clean)
        val tMatcher = if (translateMatcher.find()) translateMatcher else translatePattern.matcher(normalized).takeIf { it.find() }
        if (tMatcher != null) {
            val phrase = tMatcher.group(1)?.trim() ?: ""
            val targetLang = tMatcher.group(2)?.trim() ?: "English"
            return deviceController.translatePhrase(phrase, targetLang)
        }

        // ---------------------------------------------------------
        // 23. STORAGE & CONNECTIVITY
        // ---------------------------------------------------------
        val storagePhrases = setOf(
            "storage", "check storage", "check my storage", "how much storage", "how much storage do i have",
            "free storage", "internal storage", "disk space", "memory status", "memory", "storage info", "disk space left"
        )
        if (matchesCommand(clean, normalized, storagePhrases)) {
            return deviceController.getStorageInfo()
        }

        val wifiPhrases = setOf(
            "check wifi", "is wifi connected", "wifi status", "internet status", "check internet",
            "network status", "are we connected", "wifi", "internet", "network", "is internet on", "check network"
        )
        if (matchesCommand(clean, normalized, wifiPhrases)) {
            return deviceController.getConnectivityStatus()
        }

        val deviceInfoPhrases = setOf(
            "device info", "what phone is this", "phone model", "android version", "about device",
            "device information", "phone info", "about phone"
        )
        if (matchesCommand(clean, normalized, deviceInfoPhrases)) {
            return deviceController.getDeviceInfo()
        }

        // ---------------------------------------------------------
        // 24. DETERMINISTIC LOCAL MATH CALCULATOR
        // ---------------------------------------------------------
        val mathResult = tryEvaluateMath(clean) ?: tryEvaluateMath(normalized)
        if (mathResult != null) {
            return mathResult
        }

        // ---------------------------------------------------------
        // 25. CLOUD AI (Google Gemini 3.6 Flash)
        // ---------------------------------------------------------
        return aiClient.ask(trimmed)
    }

    private fun parseOptionSelection(input: String): Int? {
        val clean = input.trimEnd('?', '.', '!', ' ').trim().lowercase()
        val allNumberWords = (wordNumbers.keys + ordinalWords.keys).joinToString("|")
        val ordinalKeyWords = ordinalWords.keys.joinToString("|")

        // 1. Action verb + optional noun + number: "play option 2", "play 2", "play two", "play option number 2", "play number 2", "play video 2", "play song 2", "select 2", "choose 2", "open 2", "go to 2"
        val actionPattern = Pattern.compile(
            "^(?:select|choose|pick|open|play|call|watch|go to)\\s+(?:the\\s+)?(?:option\\s+number|option|number|item|result|video|song|track)?\\s*(\\d{1,2}|$allNumberWords)(?:\\s+(?:option|video|song|track|one))*$",
            Pattern.CASE_INSENSITIVE
        )
        val actionMatcher = actionPattern.matcher(clean)
        if (actionMatcher.find()) {
            val token = actionMatcher.group(1)?.lowercase()
            val num = resolveSpokenNumber(token)
            if (num != null) return num
        }

        // 2. Noun + number without leading verb: "option 1", "option number 2", "number 2", "video 2", "song 2", "track 2", "item 3", "result 2"
        val nounPattern = Pattern.compile(
            "^(?:the\\s+)?(?:option\\s+number|option|number|item|result|video|song|track)\\s+(\\d{1,2}|$allNumberWords)(?:\\s+(?:option|video|song|track|one))*$",
            Pattern.CASE_INSENSITIVE
        )
        val nounMatcher = nounPattern.matcher(clean)
        if (nounMatcher.find()) {
            val token = nounMatcher.group(1)?.lowercase()
            val num = resolveSpokenNumber(token)
            if (num != null) return num
        }

        // 3. Ordinal phrases: "first", "second", "the first one", "the second one", "the first video", "the second video", "second video", "play the second video", "play the first video", "call the first one"
        val ordinalPattern = Pattern.compile(
            "^(?:play\\s+|open\\s+|select\\s+|choose\\s+|call\\s+|watch\\s+|go to\\s+)?(?:the\\s+)?($ordinalKeyWords)(?:\\s+(?:option|video|song|track|result|one))*$",
            Pattern.CASE_INSENSITIVE
        )
        val ordMatcher = ordinalPattern.matcher(clean)
        if (ordMatcher.find()) {
            val token = ordMatcher.group(1)?.lowercase()
            val num = ordinalWords[token]
            if (num != null) return num
        }

        // 4. Bare numbers / words ONLY if voice options or disambiguation is active: "1", "2", "three", "four", "five"
        if (deviceController.hasActiveVoiceOptions() || pendingContactChoice != null || pendingCallChoice != null) {
            val barePattern = Pattern.compile("^(\\d{1,2}|$allNumberWords)$", Pattern.CASE_INSENSITIVE)
            val bareMatcher = barePattern.matcher(clean)
            if (bareMatcher.find()) {
                val token = bareMatcher.group(1)?.lowercase()
                return resolveSpokenNumber(token)
            }
        }
        return null
    }

    private fun resolveSpokenNumber(token: String?): Int? {
        if (token == null) return null
        return token.toIntOrNull() ?: ordinalWords[token] ?: wordNumbers[token]
    }

    private fun isSendConfirmationPhrase(input: String): Boolean {
        val phrases = setOf(
            "send", "send it", "send message", "send this", "send the message",
            "send now", "press send", "yes", "yes send", "confirm", "go ahead", "do it",
            "ok", "okay", "sure", "yeah", "yep", "proceed", "send confirmation"
        )
        val clean = input.trimEnd('?', '.', '!', ' ').trim()
        return clean in phrases || stripConversationalFillers(clean) in phrases
    }

    private fun isSendCancellationPhrase(input: String): Boolean {
        val phrases = setOf(
            "cancel", "no", "stop", "don't send", "don't send it", "do not send",
            "do not send it", "never mind", "abort", "cancel it",
            "cancel send", "cancel message", "cancel call", "don't", "dont", "quit", "discard", "nah"
        )
        val clean = input.trimEnd('?', '.', '!', ' ').trim()
        return clean in phrases || stripConversationalFillers(clean) in phrases
    }

    private fun isAlarmCommand(input: String): Boolean {
        val clean = input.trimEnd('?', '.', '!', ' ')
        val alarmKeywords = listOf(
            "alarm", "wake me", "wake me up", "list alarms", "list my alarms", "show alarms", "my alarms", "what alarms do i have",
            "cancel alarm", "cancel my alarm", "delete alarm", "set alarm", "set an alarm"
        )
        return alarmKeywords.any { clean == it || clean.startsWith("$it ") || clean.contains("alarm for") || clean.contains("alarm at") }
    }

    private fun isLocalTimeCommand(input: String): Boolean {
        val clean = input.trimEnd('?', '.', '!', ' ')
        val timePhrases = setOf(
            "time", "what time", "what time is it", "what time is it now",
            "what is the time", "what's the time", "tell me the time",
            "tell me time", "current time", "the time", "clock time", "clock",
            "what is current time", "what is the current time", "time now", "check time",
            "what time is it right now"
        )
        return clean in timePhrases
    }

    private fun isLocalDateCommand(input: String): Boolean {
        val clean = input.trimEnd('?', '.', '!', ' ')
        val datePhrases = setOf(
            "date", "day", "what date", "what date is today", "what date is it",
            "what is the date", "what's the date", "what is today's date",
            "what's today's date", "what is the date today", "what day is today",
            "what day is it", "today's date", "todays date", "current date", "today",
            "date today", "check date", "tell me the date"
        )
        return clean in datePhrases
    }

    private fun isTimeAndDateCommand(input: String): Boolean {
        val clean = input.trimEnd('?', '.', '!', ' ')
        val phrases = setOf(
            "time and date", "date and time", "what is the time and date", "what is the date and time",
            "tell me time and date", "tell me date and time", "tell time and date"
        )
        return clean in phrases
    }

    private fun isBatteryCommand(input: String): Boolean {
        val clean = input.trimEnd('?', '.', '!', ' ')
        val batteryPhrases = setOf(
            "battery", "battery status", "battery level", "battery percentage",
            "charge level", "charging level", "power level", "power", "charge", "how much battery",
            "how much battery is left", "how much battery do i have", "check battery",
            "check my battery", "what is my battery", "what is my battery level", "what is the battery",
            "what is the battery percentage", "what is my battery percentage", "what's my battery",
            "what's my battery level", "how much charge is left", "battery percent", "my battery",
            "phone battery", "check charge", "is phone charged"
        )
        return clean in batteryPhrases
    }

    private fun isLocationCommand(input: String): Boolean {
        val clean = input.trimEnd('?', '.', '!', ' ')
        val locPhrases = setOf(
            "where am i", "where i am", "what is my location", "my location", "current location", "location", "address",
            "what is my current location", "where am i right now", "my address", "tell me my location",
            "what is my address", "where is this place", "tell me where i am", "where am i now",
            "what's my location", "what's my address", "check location", "find my location", "gps location", "gps"
        )
        return clean in locPhrases
    }

    private fun stripConversationalFillers(text: String): String {
        var s = text.trim()
            .replace(",", " ")
            .replace(";", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val prefixes = listOf(
            "can you please tell me ", "could you please tell me ", "please can you tell me ", "would you please tell me ",
            "can you please check ", "could you please check ", "can you please show me ", "could you please show me ",
            "can you please ", "could you please ", "would you please ", "please can you ",
            "can you ", "could you ", "would you ", "will you ",
            "please ", "hey assistant ", "ok assistant ", "okay assistant ", "assistant ",
            "hey ", "ok ", "okay ", "tell me ", "check ", "show me ",
            "i want to ", "i want you to ", "i'd like to ", "how about "
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (s.startsWith(p) && s.length > p.length) {
                    val candidate = s.removePrefix(p).trim()
                    if (candidate.isNotBlank()) {
                        s = candidate
                        changed = true
                    }
                }
            }
        }
        val suffixes = listOf(" please", " now", " for me", " thanks", " thank you", " right now")
        for (suffix in suffixes) {
            if (s.endsWith(suffix) && s.length > suffix.length) {
                val candidate = s.removeSuffix(suffix).trim()
                if (candidate.isNotBlank()) {
                    s = candidate
                }
            }
        }
        return s
    }

    private fun matchesCommand(clean: String, normalized: String, phrases: Set<String>): Boolean {
        return clean in phrases || normalized in phrases
    }

    private fun tryEvaluateMath(input: String): String? {
        val lower = input.trim().lowercase()
        val isMathPrefix = lower.startsWith("calculate ") || lower.startsWith("calc ") ||
                lower.startsWith("what is ") || lower.startsWith("what's ") ||
                lower.startsWith("how much is ")

        val hasMathOperator = lower.contains(" plus ") || lower.contains(" + ") ||
                lower.contains(" minus ") || lower.contains(" - ") ||
                lower.contains(" times ") || lower.contains(" multiplied by ") || lower.contains(" * ") ||
                lower.contains(" divided by ") || lower.contains(" / ") || lower.contains(" over ")

        if (!isMathPrefix && !hasMathOperator) {
            return null
        }

        val cleaned = lower
            .removePrefix("calculate ")
            .removePrefix("calc ")
            .removePrefix("what is ")
            .removePrefix("what's ")
            .removePrefix("how much is ")
            .replace("?", "")
            .trim()

        val addPattern = Pattern.compile("^(\\d+)\\s*(?:\\+|plus|and)\\s*(\\d+)$")
        val subPattern = Pattern.compile("^(\\d+)\\s*(?:\\-|minus|subtract)\\s*(\\d+)$")
        val mulPattern = Pattern.compile("^(\\d+)\\s*(?:\\*|times|multiplied by|x)\\s*(\\d+)$")
        val divPattern = Pattern.compile("^(\\d+)\\s*(?:\\/|divided by|over)\\s*(\\d+)$")

        val addM = addPattern.matcher(cleaned)
        if (addM.find()) {
            val a = addM.group(1)?.toLongOrNull() ?: 0
            val b = addM.group(2)?.toLongOrNull() ?: 0
            return "$a plus $b equals ${a + b}."
        }
        val subM = subPattern.matcher(cleaned)
        if (subM.find()) {
            val a = subM.group(1)?.toLongOrNull() ?: 0
            val b = subM.group(2)?.toLongOrNull() ?: 0
            return "$a minus $b equals ${a - b}."
        }
        val mulM = mulPattern.matcher(cleaned)
        if (mulM.find()) {
            val a = mulM.group(1)?.toLongOrNull() ?: 0
            val b = mulM.group(2)?.toLongOrNull() ?: 0
            return "$a multiplied by $b equals ${a * b}."
        }
        val divM = divPattern.matcher(cleaned)
        if (divM.find()) {
            val aStr = divM.group(1) ?: "0"
            val bStr = divM.group(2) ?: "1"
            val a = aStr.toDoubleOrNull() ?: 0.0
            val b = bStr.toDoubleOrNull() ?: 1.0
            if (b == 0.0) return "Cannot divide by zero."
            val res = a / b
            val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
            return "$aStr divided by $bStr equals $formatted."
        }
        return null
    }

    private fun extractWhatsAppIntent(input: String): Triple<String, String?, Boolean>? {
        val clean = input.trim()

        // 1. "send <contact> a whatsapp message saying/that/with message/: <msg>"
        val p1 = Pattern.compile("^(?:send\\s+)(.+?)\\s+(?:a\\s+)?whatsapp\\s+(?:message\\s+)?(?:saying|that|with message|tell him|tell her|:)[:\\s]*(.+)$", Pattern.CASE_INSENSITIVE)
        val m1 = p1.matcher(clean)
        if (m1.find()) {
            val contact = m1.group(1)?.trim() ?: ""
            val msg = m1.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank() && !contact.equals("a", ignoreCase = true)) {
                return Triple(contact, msg, true)
            }
        }

        // 2. "send a whatsapp message to <contact> saying/that/: <msg>" / "send whatsapp to <contact> saying <msg>"
        val p2 = Pattern.compile("^(?:send\\s+)?(?:a\\s+)?whatsapp(?:\\s+message)?\\s+to\\s+(.+?)(?:\\s+(?:on|in)\\s+whatsapp)?\\s*(?:saying|that|with message|tell him|tell her|:)[:\\s]*(.+)$", Pattern.CASE_INSENSITIVE)
        val m2 = p2.matcher(clean)
        if (m2.find()) {
            val contact = m2.group(1)?.trim() ?: ""
            val msg = m2.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 3. "send this message to <contact> (on whatsapp): <msg>"
        val p3 = Pattern.compile("^(?:send\\s+)?this\\s+message\\s+to\\s+(.+?)(?:\\s+(?:on|in)\\s+whatsapp)?[:\\s]+(?:saying\\s+|that\\s+|with message\\s+|:\\s*)?(.+)$", Pattern.CASE_INSENSITIVE)
        val m3 = p3.matcher(clean)
        if (m3.find()) {
            val contact = m3.group(1)?.trim() ?: ""
            val msg = m3.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 4. "send a whatsapp message saying <msg> to <contact>"
        val p4 = Pattern.compile("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp\\s+)?(?:message\\s+)?saying\\s+(.+?)\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m4 = p4.matcher(clean)
        if (m4.find()) {
            val msg = m4.group(1)?.trim() ?: ""
            val contact = m4.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 5. "whatsapp <contact> and say/saying/tell him/: <msg>" / "whatsapp <contact>: <msg>"
        val p5 = Pattern.compile("^(?:please\\s+)?whatsapp\\s+(.+?)\\s*(?:and\\s+say|saying|that|with message|and\\s+tell\\s+him|and\\s+tell\\s+her|:)[:\\s]*(.+)$", Pattern.CASE_INSENSITIVE)
        val m5 = p5.matcher(clean)
        if (m5.find()) {
            val contact = m5.group(1)?.trim() ?: ""
            val msg = m5.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 6. "message <contact> on whatsapp and say/saying/tell him/: <msg>" / "message <contact> on whatsapp: <msg>"
        val p6 = Pattern.compile("^(?:message|text)\\s+(.+?)\\s+(?:on|in)\\s+whatsapp\\s*(?:and\\s+say|saying|that|with message|and\\s+tell\\s+him|and\\s+tell\\s+her|:)[:\\s]*(.+)$", Pattern.CASE_INSENSITIVE)
        val m6 = p6.matcher(clean)
        if (m6.find()) {
            val contact = m6.group(1)?.trim() ?: ""
            val msg = m6.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 6b. "send a message to <contact> saying/that/: <msg>" / "send message to <contact> saying <msg>"
        val p6b = Pattern.compile("^(?:send\\s+)?(?:a\\s+)?message\\s+to\\s+(.+?)\\s*(?:saying|that|with message|tell him|tell her|:)[:\\s]*(.+)$", Pattern.CASE_INSENSITIVE)
        val m6b = p6b.matcher(clean)
        if (m6b.find()) {
            val contact = m6b.group(1)?.trim() ?: ""
            val msg = m6b.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 6c. "message <contact> saying/that/: <msg>" / "message <contact>: <msg>"
        val p6c = Pattern.compile("^(?:message)\\s+(.+?)\\s*(?:and\\s+say|saying|that|with message|and\\s+tell\\s+him|and\\s+tell\\s+her|:)[:\\s]*(.+)$", Pattern.CASE_INSENSITIVE)
        val m6c = p6c.matcher(clean)
        if (m6c.find()) {
            val contact = m6c.group(1)?.trim() ?: ""
            val msg = m6c.group(2)?.trim() ?: ""
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return Triple(contact, msg, true)
            }
        }

        // 7. "send a whatsapp message to <contact>" / "send whatsapp to <contact>"
        val p7 = Pattern.compile("^(?:send\\s+)?(?:a\\s+)?whatsapp(?:\\s+message)?\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m7 = p7.matcher(clean)
        if (m7.find()) {
            val contact = m7.group(1)?.trim() ?: ""
            if (contact.isNotBlank()) {
                return Triple(contact, null, false)
            }
        }

        // 8. "whatsapp <contact>" / "whatsapp 03001234567"
        val p8 = Pattern.compile("^(?:please\\s+)?whatsapp\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m8 = p8.matcher(clean)
        if (m8.find()) {
            val contact = m8.group(1)?.trim() ?: ""
            if (contact.isNotBlank()) {
                return Triple(contact, null, false)
            }
        }

        // 9. "message <contact> on whatsapp"
        val p9 = Pattern.compile("^(?:message|text)\\s+(.+?)\\s+(?:on|in)\\s+whatsapp$", Pattern.CASE_INSENSITIVE)
        val m9 = p9.matcher(clean)
        if (m9.find()) {
            val contact = m9.group(1)?.trim() ?: ""
            if (contact.isNotBlank()) {
                return Triple(contact, null, false)
            }
        }

        // 10. "send a message to <contact>" / "send message to <contact>"
        val p10 = Pattern.compile("^(?:send\\s+)?(?:a\\s+)?message\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m10 = p10.matcher(clean)
        if (m10.find()) {
            val contact = m10.group(1)?.trim() ?: ""
            if (contact.isNotBlank() && contact !in listOf("him", "her", "this")) {
                return Triple(contact, null, false)
            }
        }

        // 11. "message <contact>" (e.g. "message Ali")
        val p11 = Pattern.compile("^(?:please\\s+)?message\\s+([a-zA-Z0-9 +-]+)$", Pattern.CASE_INSENSITIVE)
        val m11 = p11.matcher(clean)
        if (m11.find()) {
            val contact = m11.group(1)?.trim() ?: ""
            if (contact.isNotBlank() && contact !in listOf("last", "latest", "reply", "back", "him", "her", "this", "new")) {
                return Triple(contact, null, false)
            }
        }

        return null
    }
}
