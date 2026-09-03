package com.blindassistant

import android.content.Context
import android.content.ContextWrapper
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CommandProcessorTest {

    private class FakeContext : ContextWrapper(null)

    private class FakeDeviceController(
        aiClient: AiClient,
        var optionsActive: Boolean = false
    ) : DeviceController(
        context = FakeContext(),
        aiClient = aiClient
    ) {
        override fun getBatteryStatus(): String = "Battery is at 85 percent."
        override fun getCurrentTime(): String = "The current time is 2:30 PM."
        override fun getCurrentDate(): String = "Today is Monday, August 24, 2026."
        override fun getTimeAndDate(): String = "The current time is 2:30 PM and today is Monday, August 24, 2026."
        override fun toggleFlashlight(turnOn: Boolean): String = if (turnOn) "Flashlight turned on." else "Flashlight turned off."
        override fun callContactByName(name: String, onSpeaker: Boolean): String = if (onSpeaker) "Calling $name on speakerphone." else "Calling $name."
        override fun makePhoneCall(numberOrContact: String, onSpeaker: Boolean): String {
            val isPhone = contactsAndCallManager.isPhoneNumber(numberOrContact)
            return if (isPhone) {
                val norm = contactsAndCallManager.normalizePhoneNumber(numberOrContact) ?: numberOrContact
                if (onSpeaker) "Calling $norm on speakerphone." else "Calling $norm."
            } else {
                if (onSpeaker) "Calling $numberOrContact on speakerphone." else "Calling $numberOrContact."
            }
        }
        var mockCallState: CallState = CallState.INCOMING_CELLULAR_CALL
        var mockWhatsAppCaller: String = ""
        var mockCellularCaller: String = "Ahmed"

        override fun getCallState(): CallState = mockCallState
        override fun getWhatsAppCaller(): String = mockWhatsAppCaller
        override fun getCellularCaller(): String = mockCellularCaller

        override fun whoIsCalling(): String {
            return when (mockCallState) {
                CallState.INCOMING_WHATSAPP_CALL -> {
                    val caller = if (mockWhatsAppCaller.isNotBlank()) mockWhatsAppCaller else "Someone"
                    "$caller is calling you on WhatsApp."
                }
                CallState.INCOMING_CELLULAR_CALL -> {
                    val caller = if (mockCellularCaller.isNotBlank()) mockCellularCaller else "Someone"
                    "$caller is calling you."
                }
                CallState.ACTIVE_WHATSAPP_CALL -> {
                    val caller = if (mockWhatsAppCaller.isNotBlank()) mockWhatsAppCaller else "Someone"
                    "You are on a WhatsApp call with $caller."
                }
                CallState.ACTIVE_CELLULAR_CALL -> {
                    val caller = if (mockCellularCaller.isNotBlank()) mockCellularCaller else "Someone"
                    "You are on a phone call with $caller."
                }
                else -> "No one is calling right now."
            }
        }

        override suspend fun answerIncomingCall(): String {
            return when (mockCallState) {
                CallState.INCOMING_WHATSAPP_CALL -> {
                    mockCallState = CallState.ACTIVE_WHATSAPP_CALL
                    "WhatsApp call answered."
                }
                CallState.INCOMING_CELLULAR_CALL -> {
                    mockCallState = CallState.ACTIVE_CELLULAR_CALL
                    "Call answered on speakerphone."
                }
                else -> "No incoming call to answer."
            }
        }

        override suspend fun declineIncomingCall(): String {
            return when (mockCallState) {
                CallState.INCOMING_WHATSAPP_CALL, CallState.ACTIVE_WHATSAPP_CALL -> {
                    mockCallState = CallState.IDLE
                    mockWhatsAppCaller = ""
                    "WhatsApp call declined."
                }
                CallState.INCOMING_CELLULAR_CALL, CallState.ACTIVE_CELLULAR_CALL -> {
                    mockCallState = CallState.IDLE
                    mockCellularCaller = ""
                    "Call declined."
                }
                else -> "No incoming call to decline."
            }
        }

        override fun answerCallOnLoudspeaker(): String = "Call answered on speakerphone."
        override fun declineCall(): String = "Call declined."
        override fun setSpeakerphone(enabled: Boolean): String = if (enabled) "Speakerphone turned on." else "Speakerphone turned off."
        override fun getCurrentLocation(): String = "Your current location is Malir, Karachi, Pakistan."
        override fun triggerEmergencySOS(): String = "Emergency SOS triggered! Location sent to Emergency Contact. Calling emergency contact in 10 seconds. Say cancel to abort."
        override fun adjustVolume(increase: Boolean): String = if (increase) "Volume increased to 60 percent." else "Volume decreased to 40 percent."
        override fun muteVolume(mute: Boolean): String = if (mute) "Media volume muted." else "Media volume unmuted."
        override fun getVolumeStatus(): String = "Media volume is at 50 percent."
        override fun launchApp(appNameQuery: String): String = "Opening $appNameQuery."
        override suspend fun searchYouTube(query: String): String = "Searching YouTube for $query."
        override fun skipAd(): String = "Ad skipped."
        override fun navigateNextNode(): String = "Next button."
        override fun navigatePreviousNode(): String = "Previous button."
        override fun clickFocusedNode(): String = "Clicked."
        override fun getYouTubeCurrentlyPlaying(): String = "You are watching Atif Aslam Live Performance."
        override fun getYouTubeVideoTitle(): String = "The title of this video is Atif Aslam Live Performance."
        var mockContactMatches: Map<String, List<Pair<String, String>>> = emptyMap()
        override fun findMatchingContacts(nameQuery: String): List<Pair<String, String>> {
            val lower = nameQuery.lowercase().trim()
            if (mockContactMatches.containsKey(lower)) {
                return mockContactMatches[lower] ?: emptyList()
            }
            val stripped = contactsAndCallManager.cleanRelationshipPrefixes(nameQuery)
            val searchKey = if (stripped.isNotBlank()) stripped else nameQuery
            val capitalized = searchKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            return listOf(Pair(capitalized, "+923001234567"))
        }

        override suspend fun sendWhatsApp(contactOrPhone: String, messageText: String): String = "Message sent to $contactOrPhone."
        override suspend fun selectVoiceOption(index: Int): String = "Selected option $index: Video $index."
        override fun hasActiveVoiceOptions(): Boolean = optionsActive
        override fun collectGenericOptions(): String = "I found 3 options."
        override fun pauseMediaPlayback(): String = "Video paused."
        override fun resumeMediaPlayback(): String = "Video resumed."
        override fun toggleMediaPlayPause(): String = "Toggled playback."
        override suspend fun playNextYouTubeVideo(): String = "Playing next video: Video 2."
        override suspend fun playPreviousYouTubeVideo(): String = "Playing previous video: Video 1."
        override fun stopMediaPlayback(): String = "Playback stopped."
        override fun setYouTubeCaptions(enable: Boolean): String = if (enable) "Subtitles turned on." else "Subtitles turned off."
        override fun readYouTubeSubtitles(): String = "Surah Rahman Urdu translation subtitles."
        override fun replayYouTubeVideo(): String = "Video replayed."
        override fun seekForwardYouTube(seconds: Int): String = "Seeked forward $seconds seconds."
        override fun seekBackwardYouTube(seconds: Int): String = "Seeked backward $seconds seconds."
        override fun openYouTubeComments(): String = "Comments opened."
        override fun readYouTubeComments(): String = "Comments: 1. Mashallah 2. Beautiful recitation"
        override fun closeYouTubeComments(): String = "Comments closed."
        override fun debugYouTubeResults(): String = "RAW CANDIDATES: 5. SHORTS REJECTED: 1. NON-VIDEO REJECTED: 1. DUPLICATES REMOVED: 0. REGULAR VIDEOS: 3. Video 1: 'Atif Aslam - Dil', actionable=true."
        override suspend fun readYouTubeResults(): String = "I found 5 videos."
        override fun nextYouTubeResult(): String = "Option 2: Video 2."
        override fun previousYouTubeResult(): String = "Option 1: Video 1."
        override fun readScreen(): String = "Screen contains: Blind AI Assistant."
        override fun scroll(forward: Boolean): String = if (forward) "Scrolled down." else "Scrolled up."
        override suspend fun describeAroundMe(): String = "In front of you at two meters is a table, on your right is a chair, and on your left is a door."

        override fun processAlarmCommand(input: String): String {
            val lower = input.lowercase().trim()
            if (lower == "wake me at 5" || lower == "set alarm for 5") {
                return "Did you mean 5 AM or 5 PM?"
            }
            if (lower.contains("5 am") || lower.contains("5:00 am")) {
                return "Alarm set for 5:00 AM."
            }
            if (lower.contains("5:30 pm")) {
                return "Alarm set for 5:30 PM."
            }
            return "Alarm processed."
        }

        override suspend fun sendSMS(contactOrPhone: String, messageText: String): String = "SMS sent to $contactOrPhone."
        override fun playVoiceNote(): String = "Playing voice note."
        override suspend fun transcribeVoiceNote(): String = "Voice note from ${BlindAccessibilityService.latestIncomingMessage?.sender ?: "Sarah"}: 'Hey, I will be reaching the station in 10 minutes.' (Transcribed)"
        override fun getLastMessageInfo(): String {
            val msg = BlindAccessibilityService.latestIncomingMessage
            return if (msg != null) {
                val typeStr = if (msg.isVoiceNote) "voice message" else "message"
                "Last $typeStr from ${msg.sender}: '${msg.text}'. Say reply to answer."
            } else {
                "You have no recent incoming messages."
            }
        }
        override suspend fun readTextOCR(): String = "Scanned text: Aspirin 100mg take twice daily."
        override suspend fun identifyCurrency(): String = "Detected currency: 500 Pakistani Rupees."
        override suspend fun detectColor(): String = "Detected color: Navy blue with white stripes."
        override suspend fun findObject(target: String): String = "Found $target: It is at your 12 o'clock on the desk."
        override suspend fun readDocument(): String = "Document: Electric Bill for August amount due 45 dollars."
        override suspend fun identifyProduct(): String = "Product: Heinz Tomato Ketchup 500ml."
        override fun startWalkingNavigation(destination: String): String = "Starting walking navigation to $destination with spoken directions."
    }

    @Test
    fun testBlankInputReturnsFriendlyResponse() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val result = processor.processCommand("   ")
        assertEquals("I did not hear anything. Please speak a command.", result)
    }

    @Test
    fun testOpenYouTubeCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val result = processor.processCommand("Open YouTube")
        assertEquals("Opening youtube.", result)
    }

    @Test
    fun testYouTubeSearchCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val result = processor.processCommand("Search YouTube for Atif Aslam")
        assertEquals("Searching YouTube for atif aslam.", result)

        val result2 = processor.processCommand("Open YouTube and search for cricket highlights")
        assertEquals("Searching YouTube for cricket highlights.", result2)

        val result3 = processor.processCommand("Find Atif Aslam songs on YouTube")
        assertEquals("Searching YouTube for atif aslam songs.", result3)

        val result4 = processor.processCommand("Look for today's news on YouTube")
        assertEquals("Searching YouTube for today's news.", result4)
    }

    @Test
    fun testYouTubeWhatIsPlayingAndTitle() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val playing = processor.processCommand("What's playing?")
        assertEquals("You are watching Atif Aslam Live Performance.", playing)

        val title = processor.processCommand("What is the title?")
        assertEquals("The title of this video is Atif Aslam Live Performance.", title)
    }

    @Test
    fun testStopPlaybackCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Playback stopped.", processor.processCommand("Stop"))
        assertEquals("Playback stopped.", processor.processCommand("stop the video"))
        assertEquals("Playback stopped.", processor.processCommand("stop playing"))
    }

    @Test
    fun testVoiceOptionSelectionCommands() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Selected option 2: Video 2.", processor.processCommand("Option 2"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("option two"))
        assertEquals("Selected option 3: Video 3.", processor.processCommand("Number 3"))
        assertEquals("Selected option 3: Video 3.", processor.processCommand("number three"))
        assertEquals("Selected option 1: Video 1.", processor.processCommand("select option 1"))
        assertEquals("Selected option 5: Video 5.", processor.processCommand("choose option five"))
        assertEquals("Selected option 4: Video 4.", processor.processCommand("play option 4"))
        assertEquals("Selected option 1: Video 1.", processor.processCommand("pick option one"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("play the second video"))
        assertEquals("Selected option 1: Video 1.", processor.processCommand("the first video"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("second"))
        assertEquals("Selected option 1: Video 1.", processor.processCommand("the first one"))
    }

    @Test
    fun testBareNumberSelectsOnlyWhenOptionsActive() = runBlocking {
        val aiClient = AiClient()

        val withOptions = CommandProcessor(FakeDeviceController(aiClient, optionsActive = true), aiClient)
        assertEquals("Selected option 2: Video 2.", withOptions.processCommand("2"))
        assertEquals("Selected option 3: Video 3.", withOptions.processCommand("three"))

        val withoutOptions = CommandProcessor(FakeDeviceController(aiClient, optionsActive = false), aiClient)
        assertEquals("Gemini API key is not configured.", withoutOptions.processCommand("7"))
    }

    @Test
    fun testGenericOptionsCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("I found 3 options.", processor.processCommand("What are my options?"))
        assertEquals("I found 3 options.", processor.processCommand("list options"))
    }

    @Test
    fun testSetAlarmCommands() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val result5Am = processor.processCommand("Set an alarm for 5 AM")
        assertEquals("Alarm set for 5:00 AM.", result5Am)

        val result530Pm = processor.processCommand("Set an alarm for 5:30 PM")
        assertEquals("Alarm set for 5:30 PM.", result530Pm)

        val resultAmbiguous = processor.processCommand("Wake me at 5")
        assertEquals("Did you mean 5 AM or 5 PM?", resultAmbiguous)
    }

    @Test
    fun testDescribeAroundMeCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val describe = processor.processCommand("Describe around me")
        assertEquals("In front of you at two meters is a table, on your right is a chair, and on your left is a door.", describe)

        val surroundings = processor.processCommand("Describe my surroundings")
        assertEquals("In front of you at two meters is a table, on your right is a chair, and on your left is a door.", surroundings)

        val whatAround = processor.processCommand("What is around me?")
        assertEquals("In front of you at two meters is a table, on your right is a chair, and on your left is a door.", whatAround)
    }

    @Test
    fun testSkipAdCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val result = processor.processCommand("Skip ad")
        assertEquals("Ad skipped.", result)

        val result2 = processor.processCommand("Skip the ad")
        assertEquals("Ad skipped.", result2)
    }

    @Test
    fun testAccessibilityNextAndPrevious() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val next = processor.processCommand("Next")
        assertEquals("Next button.", next)

        val prev = processor.processCommand("Previous")
        assertEquals("Previous button.", prev)

        val click = processor.processCommand("Click this")
        assertEquals("Clicked.", click)
    }

    @Test
    fun testBatteryCommandRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Battery is at 85 percent.", processor.processCommand("How much battery do I have?"))
        assertEquals("Battery is at 85 percent.", processor.processCommand("What is my battery percentage?"))
        assertEquals("Battery is at 85 percent.", processor.processCommand("Check my battery."))
        assertEquals("Battery is at 85 percent.", processor.processCommand("How much charge is left?"))
        assertEquals("Battery is at 85 percent.", processor.processCommand("battery level"))
    }

    @Test
    fun testCallContactRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Calling Ali.", processor.processCommand("Call Ali."))
        assertEquals("Calling Ali.", processor.processCommand("Phone Ali."))
        assertEquals("Calling Ali.", processor.processCommand("Call Ali on his phone."))
        assertEquals("Calling Ali on speakerphone.", processor.processCommand("Call Ali on speaker."))
    }

    @Test
    fun testAnswerAndDeclineCallRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        controller.mockCallState = CallState.INCOMING_CELLULAR_CALL
        assertEquals("Call answered on speakerphone.", processor.processCommand("Answer the call."))

        controller.mockCallState = CallState.INCOMING_CELLULAR_CALL
        assertEquals("Call answered on speakerphone.", processor.processCommand("Pick up the call."))

        controller.mockCallState = CallState.INCOMING_CELLULAR_CALL
        assertEquals("Call declined.", processor.processCommand("Reject the call."))

        controller.mockCallState = CallState.ACTIVE_CELLULAR_CALL
        assertEquals("Call declined.", processor.processCommand("Hang up."))

        controller.mockCallState = CallState.ACTIVE_CELLULAR_CALL
        assertEquals("Call declined.", processor.processCommand("End the call."))
    }

    @Test
    fun testSpeakerphoneControls() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Speakerphone turned on.", processor.processCommand("Turn on speakerphone."))
        assertEquals("Speakerphone turned off.", processor.processCommand("Turn off speakerphone."))
    }

    @Test
    fun testFlashlightRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Flashlight turned on.", processor.processCommand("Turn on the flashlight."))
        assertEquals("Flashlight turned on.", processor.processCommand("Switch on the flashlight."))
        assertEquals("Flashlight turned on.", processor.processCommand("Flashlight on."))
        assertEquals("Flashlight turned on.", processor.processCommand("Turn the torch on."))

        assertEquals("Flashlight turned off.", processor.processCommand("Turn off the flashlight."))
        assertEquals("Flashlight turned off.", processor.processCommand("Switch off the flashlight."))
        assertEquals("Flashlight turned off.", processor.processCommand("Flashlight off."))
    }

    @Test
    fun testTimeAndDateRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("The current time is 2:30 PM.", processor.processCommand("What time is it?"))
        assertEquals("The current time is 2:30 PM.", processor.processCommand("Tell me the time."))
        assertEquals("The current time is 2:30 PM.", processor.processCommand("What is the current time?"))

        assertEquals("Today is Monday, August 24, 2026.", processor.processCommand("What is today's date?"))
        assertEquals("Today is Monday, August 24, 2026.", processor.processCommand("What's today's date?"))
    }

    @Test
    fun testLocationRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val loc = processor.processCommand("Where am I?")
        assertEquals("Your current location is Malir, Karachi, Pakistan.", loc)

        val loc2 = processor.processCommand("What is my current location?")
        assertEquals("Your current location is Malir, Karachi, Pakistan.", loc2)

        val loc3 = processor.processCommand("Tell me where I am.")
        assertEquals("Your current location is Malir, Karachi, Pakistan.", loc3)
    }

    @Test
    fun testEmergencySOSRoutesLocally() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Emergency SOS triggered! Location sent to Emergency Contact. Calling emergency contact in 10 seconds. Say cancel to abort.", processor.processCommand("Send an emergency SOS."))
        assertEquals("Emergency SOS triggered! Location sent to Emergency Contact. Calling emergency contact in 10 seconds. Say cancel to abort.", processor.processCommand("I need emergency help."))
        assertEquals("Emergency SOS triggered! Location sent to Emergency Contact. Calling emergency contact in 10 seconds. Say cancel to abort.", processor.processCommand("Send SOS."))
    }

    @Test
    fun testVolumeControls() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Volume increased to 60 percent.", processor.processCommand("Increase the volume."))
        assertEquals("Volume decreased to 40 percent.", processor.processCommand("Decrease the volume."))
        assertEquals("Media volume muted.", processor.processCommand("Mute the volume."))
        assertEquals("Media volume unmuted.", processor.processCommand("Unmute the volume."))
    }

    @Test
    fun testLocalMathCalculator() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val calcMul = processor.processCommand("Calculate 25 times 40")
        assertEquals("25 multiplied by 40 equals 1000.", calcMul)

        val calcAdd = processor.processCommand("Calculate 100 plus 50")
        assertEquals("100 plus 50 equals 150.", calcAdd)

        val calcSub = processor.processCommand("Calculate 100 minus 30")
        assertEquals("100 minus 30 equals 70.", calcSub)

        val calcDiv = processor.processCommand("Calculate 100 divided by 4")
        assertEquals("100 divided by 4 equals 25.", calcDiv)
    }

    @Test
    fun testGeneralQuestionRoutesToAi() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Gemini API key is not configured.", processor.processCommand("What is a black hole?"))
        assertEquals("Gemini API key is not configured.", processor.processCommand("Explain artificial intelligence."))
        assertEquals("Gemini API key is not configured.", processor.processCommand("Who is the current president of Pakistan?"))
        assertEquals("Gemini API key is not configured.", processor.processCommand("How does GPS work?"))
    }

    @Test
    fun testWhatsAppExplicitMessagingSendsDirectly() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val r1 = processor.processCommand("Send Ali a WhatsApp message saying I'll be home at six.")
        assertEquals("Message sent to Ali.", r1)

        val r2 = processor.processCommand("Send a WhatsApp message to Ali saying I'm running late.")
        assertEquals("Message sent to Ali.", r2)

        val r3 = processor.processCommand("WhatsApp Ali and say I'll call you later.")
        assertEquals("Message sent to Ali.", r3)

        val r4 = processor.processCommand("Message Ali on WhatsApp and tell him I'm coming home.")
        assertEquals("Message sent to Ali.", r4)

        val r5 = processor.processCommand("Send this message to Ali on WhatsApp: I'll reach home at six.")
        assertEquals("Message sent to Ali.", r5)

        val r6 = processor.processCommand("WhatsApp Ali: I'm coming home")
        assertEquals("Message sent to Ali.", r6)

        val r7 = processor.processCommand("Send a WhatsApp to 03001234567 saying hello.")
        assertEquals("Message sent to 03001234567.", r7)

        val r8 = processor.processCommand("Send WhatsApp to +923001234567 saying I'll call later.")
        assertEquals("Message sent to +923001234567.", r8)

        val r9 = processor.processCommand("WhatsApp 0300 1234567 and say I'll be home soon.")
        assertEquals("Message sent to 0300 1234567.", r9)
    }

    @Test
    fun testWhatsAppTwoStepFlow() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Step 1: User says WhatsApp Ali
        val askMsg = processor.processCommand("WhatsApp Ali.")
        assertEquals("What message should I send to Ali?", askMsg)

        // Step 2: User gives message
        val confirmPrompt = processor.processCommand("I'll be home soon.")
        assertEquals("Ready to send to Ali: 'I'll be home soon'. Say send to confirm or cancel.", confirmPrompt)

        // Step 3: User says Send
        val sent = processor.processCommand("Send.")
        assertEquals("Message sent to Ali.", sent)

        // Pending state is cleared
        val after = processor.processCommand("Send")
        assertEquals("There is no pending message to send.", after)
    }

    @Test
    fun testWhatsAppSendConfirmationCancellation() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val prompt = processor.processCommand("Send WhatsApp to Mom")
        assertEquals("What message should I send to Mom?", prompt)

        val cancelled = processor.processCommand("Cancel")
        assertEquals("Message cancelled.", cancelled)
    }

    @Test
    fun testWhatsAppMultipleContactsDisambiguation() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        controller.mockContactMatches = mapOf(
            "ali" to listOf(Pair("Ali Hussain", "03001111111"), Pair("Ali Raza", "03002222222"))
        )
        val processor = CommandProcessor(controller, aiClient)

        // Multiple contacts match -> asks for option choice
        val disambiguatePrompt = processor.processCommand("WhatsApp Ali")
        assertEquals("I found two contacts named Ali. Option 1: Ali Hussain. Option 2: Ali Raza. Say option 1 or option 2.", disambiguatePrompt)

        // User picks Option 1
        val askMsg = processor.processCommand("Option 1")
        assertEquals("What message should I send to Ali Hussain?", askMsg)

        // User speaks message
        val confirmPrompt = processor.processCommand("I'll be home soon")
        assertEquals("Ready to send to Ali Hussain: 'I'll be home soon'. Say send to confirm or cancel.", confirmPrompt)

        // User confirms send
        val sent = processor.processCommand("Send")
        assertEquals("Message sent to Ali Hussain.", sent)
    }

    @Test
    fun testPhoneNumberNormalization() {
        val manager = ContactsAndCallManager(FakeContext())

        assertEquals("+923001234567", manager.normalizePhoneNumber("03001234567"))
        assertEquals("+923001234567", manager.normalizePhoneNumber("0300 1234567"))
        assertEquals("+923001234567", manager.normalizePhoneNumber("0300-1234567"))
        assertEquals("+923001234567", manager.normalizePhoneNumber("+923001234567"))
        assertEquals("+923001234567", manager.normalizePhoneNumber("00923001234567"))
        assertEquals("+923001234567", manager.normalizePhoneNumber("zero three zero zero one two three four five six seven"))
        assertEquals("+923001234567", manager.normalizePhoneNumber("plus nine two three zero zero one two three four five six seven"))
        assertEquals("+14155552671", manager.normalizePhoneNumber("+14155552671"))
        assertEquals(null, manager.normalizePhoneNumber("123"))
    }

    @Test
    fun testRemovedFeatureCommandsRouteToAi() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Commands for removed features should route to general AI rather than local handlers
        val rxResult = processor.processCommand("Read prescription")
        assertEquals("Gemini API key is not configured.", rxResult)

        val lectureResult = processor.processCommand("Record lecture")
        assertEquals("Gemini API key is not configured.", lectureResult)

        val emailSummaryResult = processor.processCommand("Summarize my emails")
        assertEquals("Gemini API key is not configured.", emailSummaryResult)
    }

    @Test
    fun testYouTubeShortsFilteringSignals() {
        // Shorts MUST be excluded
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam #shorts"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam #short"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("https://youtube.com/shorts/abc123xyz"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam 45 seconds - play short"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Shorts - Atif Aslam Live Performance"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Shorts, Atif Aslam Live, 1M views"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Shorts shelf: trending viral clips"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("YouTube Shorts: Atif Aslam Hits"))

        // Normal videos containing 'short' or normal title metadata MUST NOT be excluded
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam - Tajdar e Haram 10 minutes - play video"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Short film about space 15 minutes - play video"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam Tajdar e Haram - short version"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("The Short Story of Science"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Short clip documentary on nature"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Coke Studio - Pasoori"))
    }

    @Test
    fun testYouTubeTitleCleaning() {
        val raw1 = "Atif Aslam - Tajdar-e-Haram 120M views 4 years ago 10 minutes, 20 seconds - play video"
        assertEquals("Atif Aslam - Tajdar-e-Haram", BlindAccessibilityService.cleanYouTubeTitle(raw1))

        val raw2 = "Short Film: Journey by Director 50K views 1 year ago 15 minutes - play video"
        assertEquals("Short Film: Journey by Director", BlindAccessibilityService.cleanYouTubeTitle(raw2))

        val raw3 = "Atif Aslam Live Concert 2026 sponsored verified channel 2M views 3 months ago 45 minutes - play video"
        assertEquals("Atif Aslam Live Concert 2026", BlindAccessibilityService.cleanYouTubeTitle(raw3))
    }

    @Test
    fun testYouTubeVoiceOptionSelection() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        controller.optionsActive = true
        val processor = CommandProcessor(controller, aiClient)

        assertEquals("Selected option 1: Video 1.", processor.processCommand("first"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("second"))
        assertEquals("Selected option 3: Video 3.", processor.processCommand("third"))
        assertEquals("Selected option 4: Video 4.", processor.processCommand("fourth"))
        assertEquals("Selected option 5: Video 5.", processor.processCommand("fifth"))

        assertEquals("Selected option 1: Video 1.", processor.processCommand("the first video"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("the second video"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("play the second video"))
        assertEquals("Selected option 1: Video 1.", processor.processCommand("the first one"))

        assertEquals("Selected option 1: Video 1.", processor.processCommand("Option 1"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("Option 2"))
        assertEquals("Selected option 3: Video 3.", processor.processCommand("Option 3"))

        assertEquals("Selected option 1: Video 1.", processor.processCommand("one"))
        assertEquals("Selected option 2: Video 2.", processor.processCommand("two"))
        assertEquals("Selected option 3: Video 3.", processor.processCommand("three"))
        assertEquals("Selected option 4: Video 4.", processor.processCommand("four"))
        assertEquals("Selected option 5: Video 5.", processor.processCommand("five"))
    }

    @Test
    fun testYouTubePlaybackControlCommands() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Play / Pause / Resume
        assertEquals("Video resumed.", processor.processCommand("play the video"))
        assertEquals("Video paused.", processor.processCommand("pause the video"))
        assertEquals("Video resumed.", processor.processCommand("resume the video"))

        // Next / Previous
        assertEquals("Playing next video: Video 2.", processor.processCommand("go to the next video"))
        assertEquals("Playing next video: Video 2.", processor.processCommand("next video"))
        assertEquals("Playing previous video: Video 1.", processor.processCommand("go to the previous video"))
        assertEquals("Playing previous video: Video 1.", processor.processCommand("previous video"))

        // Stop
        assertEquals("Playback stopped.", processor.processCommand("stop the video"))
    }

    @Test
    fun testVoiceCallingByContactNameAndPhoneNumber() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Contact by name
        assertEquals("Calling Ahmed.", processor.processCommand("Call Ahmed"))
        assertEquals("Calling Ali.", processor.processCommand("Call Ali"))
        assertEquals("Calling Ahmed.", processor.processCommand("Call my brother Ahmed"))

        // Phone numbers
        assertEquals("Calling +923001234567.", processor.processCommand("Call 03001234567"))
        assertEquals("Calling +923001234567.", processor.processCommand("Call +923001234567"))
        assertEquals("Calling +923001234567.", processor.processCommand("Call 92 300 1234567"))

        // Spoken digits
        assertEquals("Calling +923001234567.", processor.processCommand("Call zero three zero zero one two three four five six seven"))
    }

    @Test
    fun testCallDisambiguationMultiMatchAndSelection() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        controller.mockContactMatches = mapOf(
            "ahmed" to listOf(
                Pair("Ahmed Khan", "+923001111111"),
                Pair("Ahmed Raza", "+923002222222")
            )
        )
        val processor = CommandProcessor(controller, aiClient)

        val prompt = processor.processCommand("Call Ahmed")
        assertEquals("I found Ahmed Khan and Ahmed Raza. Say 1 or 2.", prompt)

        // Select Option 1 via "Call option 1"
        val callResp1 = processor.processCommand("Call option 1")
        assertEquals("Calling Ahmed Khan.", callResp1)

        // Disambiguate again with "2"
        processor.processCommand("Call Ahmed")
        val callResp2 = processor.processCommand("2")
        assertEquals("Calling Ahmed Raza.", callResp2)

        // Disambiguate again and cancel
        processor.processCommand("Call Ahmed")
        val cancelResp = processor.processCommand("cancel")
        assertEquals("Call cancelled.", cancelResp)
    }

    @Test
    fun testYouTubeSubtitlesCaptionsCommentsAndNavigation() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Subtitles / Captions
        assertEquals("Subtitles turned on.", processor.processCommand("Turn subtitles on"))
        assertEquals("Subtitles turned on.", processor.processCommand("Turn captions on"))
        assertEquals("Subtitles turned off.", processor.processCommand("Turn subtitles off"))
        assertEquals("Subtitles turned off.", processor.processCommand("Turn captions off"))
        assertEquals("Surah Rahman Urdu translation subtitles.", processor.processCommand("Read subtitles"))
        assertEquals("Surah Rahman Urdu translation subtitles.", processor.processCommand("Read captions"))

        // Replay and Seek
        assertEquals("Video replayed.", processor.processCommand("Replay"))
        assertEquals("Seeked forward 10 seconds.", processor.processCommand("Seek forward 10 seconds"))
        assertEquals("Seeked backward 10 seconds.", processor.processCommand("Seek backward 10 seconds"))

        // Comments
        assertEquals("Comments opened.", processor.processCommand("Open comments"))
        assertEquals("Comments: 1. Mashallah 2. Beautiful recitation", processor.processCommand("Read comments"))
        assertEquals("Comments closed.", processor.processCommand("Close comments"))

        // Result navigation
        assertEquals("I found 5 videos.", processor.processCommand("Read results"))
        assertEquals("Option 2: Video 2.", processor.processCommand("Next result"))
        assertEquals("Option 1: Video 1.", processor.processCommand("Previous result"))
    }

    @Test
    fun testAllSection16RequirementsMapping() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        controller.optionsActive = true
        val processor = CommandProcessor(controller, aiClient)

        // 1. "Call Ahmed" -> CALL_CONTACT
        assertEquals("Calling Ahmed.", processor.processCommand("Call Ahmed"))

        // 2. "Call 03001234567" -> CALL_NUMBER
        assertEquals("Calling +923001234567.", processor.processCommand("Call 03001234567"))

        // 3. "Call +923001234567" -> CALL_NUMBER
        assertEquals("Calling +923001234567.", processor.processCommand("Call +923001234567"))

        // 4. "Call zero three zero zero one two three four five six seven" -> CALL_NUMBER
        assertEquals("Calling +923001234567.", processor.processCommand("Call zero three zero zero one two three four five six seven"))

        // 5. "Open YouTube" -> OPEN_YOUTUBE
        assertEquals("Opening youtube.", processor.processCommand("Open YouTube"))

        // 6. "Search YouTube for Surah Rahman Urdu translation" -> YOUTUBE_SEARCH
        assertEquals("Searching YouTube for surah rahman urdu translation.", processor.processCommand("Search YouTube for Surah Rahman Urdu translation"))

        // 7. "Play option 1" -> YOUTUBE_PLAY_OPTION
        assertEquals("Selected option 1: Video 1.", processor.processCommand("Play option 1"))

        // 8. "Pause" -> YOUTUBE_PAUSE
        assertEquals("Video paused.", processor.processCommand("Pause"))

        // 9. "Resume" -> YOUTUBE_RESUME
        assertEquals("Video resumed.", processor.processCommand("Resume"))

        // 10. "Next video" -> YOUTUBE_NEXT
        assertEquals("Playing next video: Video 2.", processor.processCommand("Next video"))

        // 11. "Previous video" -> YOUTUBE_PREVIOUS
        assertEquals("Playing previous video: Video 1.", processor.processCommand("Previous video"))

        // 12. "Turn subtitles on" -> YOUTUBE_CAPTIONS_ON
        assertEquals("Subtitles turned on.", processor.processCommand("Turn subtitles on"))

        // 13. "Turn subtitles off" -> YOUTUBE_CAPTIONS_OFF
        assertEquals("Subtitles turned off.", processor.processCommand("Turn subtitles off"))

        // 14. "Read subtitles" -> YOUTUBE_READ_CAPTIONS
        assertEquals("Surah Rahman Urdu translation subtitles.", processor.processCommand("Read subtitles"))

        // 15. "Skip ad" -> YOUTUBE_SKIP_AD
        assertEquals("Ad skipped.", processor.processCommand("Skip ad"))

        // 16. "Read screen" -> SCREEN_READ
        assertEquals("Screen contains: Blind AI Assistant.", processor.processCommand("Read screen"))

        // 17. "Next" -> ACCESSIBILITY_NEXT
        assertEquals("Next button.", processor.processCommand("Next"))

        // 18. "Previous" -> ACCESSIBILITY_PREVIOUS
        assertEquals("Previous button.", processor.processCommand("Previous"))

        // 19. "What is 2 plus 2?" -> AI_ROUTE (Gemini API key is not configured message from unconfigured client)
        val aiResp = processor.processCommand("What is 2 plus 2?")
        assertTrue(aiResp.contains("Gemini API key is not configured") || aiResp.contains("4"))

        // 20. "What is my battery level?" -> LOCAL_BATTERY
        assertEquals("Battery is at 85 percent.", processor.processCommand("What is my battery level?"))
    }

    @Test
    fun testComplete15StepYouTubeAndVoiceWorkflowSequence() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        controller.optionsActive = true
        val processor = CommandProcessor(controller, aiClient)

        // Step 1: Open YouTube
        val step1 = processor.processCommand("Open YouTube")
        assertEquals("Opening youtube.", step1)

        // Step 2: Search YouTube for Surah Rahman Urdu translation
        val step2 = processor.processCommand("Search YouTube for Surah Rahman Urdu translation")
        assertEquals("Searching YouTube for surah rahman urdu translation.", step2)

        // Step 3: Read results
        val step3 = processor.processCommand("Read results")
        assertEquals("I found 5 videos.", step3)

        // Step 4: Next result
        val step4 = processor.processCommand("Next result")
        assertEquals("Option 2: Video 2.", step4)

        // Step 5: Previous result
        val step5 = processor.processCommand("Previous result")
        assertEquals("Option 1: Video 1.", step5)

        // Step 6: Play option 2
        val step6 = processor.processCommand("Play option 2")
        assertEquals("Selected option 2: Video 2.", step6)

        // Step 7: Pause
        val step7 = processor.processCommand("Pause")
        assertEquals("Video paused.", step7)

        // Step 8: Resume
        val step8 = processor.processCommand("Resume")
        assertEquals("Video resumed.", step8)

        // Step 9: Seek forward 10 seconds
        val step9 = processor.processCommand("Seek forward 10 seconds")
        assertEquals("Seeked forward 10 seconds.", step9)

        // Step 10: Seek backward 10 seconds
        val step10 = processor.processCommand("Seek backward 10 seconds")
        assertEquals("Seeked backward 10 seconds.", step10)

        // Step 11: Replay
        val step11 = processor.processCommand("Replay")
        assertEquals("Video replayed.", step11)

        // Step 12: Next video
        val step12 = processor.processCommand("Next video")
        assertEquals("Playing next video: Video 2.", step12)

        // Step 13: Previous video
        val step13 = processor.processCommand("Previous video")
        assertEquals("Playing previous video: Video 1.", step13)

        // Step 14: Read screen
        val step14 = processor.processCommand("Read screen")
        assertEquals("Screen contains: Blind AI Assistant.", step14)

        // Step 15: What's my battery?
        val step15 = processor.processCommand("What's my battery?")
        assertEquals("Battery is at 85 percent.", step15)
    }

    @Test
    fun testYouTubeCleanTitleExtraction() {
        val raw1 = "Atif Aslam - Dil, Tips Official, 4 minutes, 20 seconds, 50M views 2 years ago - play video"
        val cleaned1 = BlindAccessibilityService.cleanYouTubeTitle(raw1)
        assertFalse(cleaned1.contains("play video", ignoreCase = true))
        assertFalse(cleaned1.contains("50M views", ignoreCase = true))
        assertFalse(cleaned1.contains("2 years ago", ignoreCase = true))
        assertFalse(cleaned1.contains("4 minutes", ignoreCase = true))
        assertTrue(cleaned1.startsWith("Atif Aslam - Dil"))

        val raw2 = "Atif Aslam - Tera Hone Laga Hoon (4 minutes, 30 seconds) 100M views"
        val cleaned2 = BlindAccessibilityService.cleanYouTubeTitle(raw2)
        assertEquals("Atif Aslam - Tera Hone Laga Hoon", cleaned2)

        val raw3 = "Surah Rahman Mishary Rashid (15 minutes) - play video"
        val cleaned3 = BlindAccessibilityService.cleanYouTubeTitle(raw3)
        assertEquals("Surah Rahman Mishary Rashid", cleaned3)
    }

    @Test
    fun testYouTubeShortsAndExcludedContentDetection() {
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("https://youtube.com/shorts/abc123xyz"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Amazing singing #shorts"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Dance cover - play short"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Shorts shelf"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam - Dil Official Video"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Surah Rahman Urdu translation"))
    }

    @Test
    fun testWhatsAppIncomingCallVoiceControl() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // 1. WhatsApp incoming call with known contact
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        controller.mockWhatsAppCaller = "Ahmed"

        assertEquals("Ahmed is calling you on WhatsApp.", processor.processCommand("Who is calling?"))
        assertEquals("Ahmed is calling you on WhatsApp.", processor.processCommand("Who's calling"))

        assertEquals("WhatsApp call answered.", processor.processCommand("Answer"))
        assertEquals(CallState.ACTIVE_WHATSAPP_CALL, controller.mockCallState)

        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call answered.", processor.processCommand("Answer the call"))

        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call answered.", processor.processCommand("Accept"))

        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call answered.", processor.processCommand("Pick up"))

        // 2. WhatsApp incoming call decline / reject
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        controller.mockWhatsAppCaller = "Ahmed"
        assertEquals("WhatsApp call declined.", processor.processCommand("Decline"))
        assertEquals(CallState.IDLE, controller.mockCallState)

        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call declined.", processor.processCommand("Reject"))

        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call declined.", processor.processCommand("Reject the call"))
    }

    @Test
    fun testWhatsAppIncomingCallUnknownCallerAndCellularSeparation() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // 1. Unknown WhatsApp caller number (stored sanitized, no doubled "on WhatsApp")
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        controller.mockWhatsAppCaller = "03001234567"
        assertEquals("03001234567 is calling you on WhatsApp.", processor.processCommand("Who is calling?"))

        // 2. Cellular call separation
        controller.mockCallState = CallState.INCOMING_CELLULAR_CALL
        controller.mockCellularCaller = "Ali"
        assertEquals("Ali is calling you.", processor.processCommand("Who is calling?"))
        assertEquals("Call answered on speakerphone.", processor.processCommand("Answer"))
        assertEquals(CallState.ACTIVE_CELLULAR_CALL, controller.mockCallState)

        controller.mockCallState = CallState.INCOMING_CELLULAR_CALL
        controller.mockCellularCaller = "Ali"
        assertEquals("Call declined.", processor.processCommand("Decline"))
        assertEquals(CallState.IDLE, controller.mockCallState)

        // 3. Idle call state
        controller.mockCallState = CallState.IDLE
        assertEquals("No one is calling right now.", processor.processCommand("Who is calling?"))
        assertEquals("No incoming call to answer.", processor.processCommand("Answer"))
        assertEquals("No incoming call to decline.", processor.processCommand("Decline"))
    }

    @Test
    fun testWhatsAppIncomingCallCommandVocabulary() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        controller.mockWhatsAppCaller = "Ahmed"

        // Answer vocabulary — all must route locally to answerIncomingCall
        assertEquals("WhatsApp call answered.", processor.processCommand("Accept the call"))
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call answered.", processor.processCommand("Pick up the call"))
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call answered.", processor.processCommand("answer the call"))

        // Decline vocabulary — all must route locally to declineIncomingCall
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call declined.", processor.processCommand("Reject the call"))
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call declined.", processor.processCommand("Decline the call"))
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call declined.", processor.processCommand("End"))
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        assertEquals("WhatsApp call declined.", processor.processCommand("Don't answer"))

        // Caller query vocabulary
        controller.mockCallState = CallState.INCOMING_WHATSAPP_CALL
        controller.mockWhatsAppCaller = "Ahmed"
        assertEquals("Ahmed is calling you on WhatsApp.", processor.processCommand("Who is it?"))
        assertEquals("Ahmed is calling you on WhatsApp.", processor.processCommand("Who's calling me?"))
    }

    @Test
    fun testIncomingMessageAndReplyFlow() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Simulate incoming message from Sarah
        BlindAccessibilityService.latestIncomingMessage = BlindAccessibilityService.IncomingMessage(
            sender = "Sarah",
            text = "Are we meeting at 5 PM?",
            isVoiceNote = false,
            packageName = "com.whatsapp"
        )

        // 1. Read last message
        val readMsg = processor.processCommand("Read last message")
        assertEquals("Last message from Sarah: 'Are we meeting at 5 PM?'. Say reply to answer.", readMsg)

        // 2. Reply command
        val replyPrompt = processor.processCommand("Reply")
        assertEquals("What message should I send to Sarah?", replyPrompt)

        // 3. Spoken message input
        val confirmPrompt = processor.processCommand("Yes, see you at 5")
        assertEquals("Ready to send to Sarah: 'Yes, see you at 5'. Say send to confirm or cancel.", confirmPrompt)

        // 4. Confirmation
        val sendResult = processor.processCommand("Send")
        assertEquals("Message sent to Sarah.", sendResult)
    }

    @Test
    fun testVoiceNoteTranscribeAndPlay() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Simulate voice message
        BlindAccessibilityService.latestIncomingMessage = BlindAccessibilityService.IncomingMessage(
            sender = "Sarah",
            text = "Voice message (0:15)",
            isVoiceNote = true,
            packageName = "com.whatsapp"
        )

        val transcribeResult = processor.processCommand("Transcribe voice note")
        assertTrue(transcribeResult.contains("Voice note from Sarah:"))

        val playResult = processor.processCommand("Play voice note")
        assertTrue(playResult.contains("voice note", ignoreCase = true))

        val playItResult = processor.processCommand("Play it")
        assertTrue(playItResult.contains("voice note", ignoreCase = true))

        val whatMsgResult = processor.processCommand("What is the message")
        assertTrue(whatMsgResult.contains("Sarah"))
    }

    @Test
    fun testAssistiveVisionAndNavigationCommands() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // 1. OCR / Read text
        val ocrRes = processor.processCommand("read text")
        assertTrue(ocrRes.contains("Aspirin 100mg"))

        val labelRes = processor.processCommand("read product label")
        assertTrue(labelRes.contains("Aspirin 100mg"))

        val whatSays = processor.processCommand("what does this say")
        assertTrue(whatSays.contains("Aspirin 100mg"))

        // 2. Currency recognition
        val currRes = processor.processCommand("how much money is this")
        assertTrue(currRes.contains("500 Pakistani Rupees"))

        val noteRes = processor.processCommand("count my money")
        assertTrue(noteRes.contains("500 Pakistani Rupees"))

        // 3. Color detector
        val colorRes = processor.processCommand("what color is this")
        assertTrue(colorRes.contains("Navy blue"))

        val clothesRes = processor.processCommand("what color is my shirt")
        assertTrue(clothesRes.contains("Navy blue"))

        // 4. Object Finder
        val findKeys = processor.processCommand("where are my keys")
        assertTrue(findKeys.contains("Found keys"))

        val findGlasses = processor.processCommand("find my glasses")
        assertTrue(findGlasses.contains("Found glasses"))

        // 5. Document Reader
        val docRes = processor.processCommand("read document")
        assertTrue(docRes.contains("Electric Bill"))

        val mailRes = processor.processCommand("read my mail")
        assertTrue(mailRes.contains("Electric Bill"))

        // 6. Product Identifier
        val prodRes = processor.processCommand("what product is this")
        assertTrue(prodRes.contains("Heinz Tomato Ketchup"))

        // 7. Pedestrian Walking Navigation
        val navRes = processor.processCommand("navigate to Central Park")
        assertTrue(navRes.contains("walking navigation to Central Park", ignoreCase = true))

        val walkRes = processor.processCommand("walk to nearest pharmacy")
        assertTrue(walkRes.contains("walking navigation to nearest pharmacy", ignoreCase = true))
    }

    @Test
    fun testSimplifiedQuickCommands() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Ultra-short 1-word and 2-word commands designed for blind user speed
        val timeRes = processor.processCommand("time")
        assertEquals("The current time is 2:30 PM.", timeRes)

        val dateRes = processor.processCommand("date")
        assertEquals("Today is Monday, August 24, 2026.", dateRes)

        val batteryRes = processor.processCommand("battery")
        assertEquals("Battery is at 85 percent.", batteryRes)

        val chargeRes = processor.processCommand("charge")
        assertEquals("Battery is at 85 percent.", chargeRes)

        val torchOnRes = processor.processCommand("torch")
        assertEquals("Flashlight turned on.", torchOnRes)

        val torchOffRes = processor.processCommand("torch off")
        assertEquals("Flashlight turned off.", torchOffRes)

        val lightOnRes = processor.processCommand("light")
        assertEquals("Flashlight turned on.", lightOnRes)

        val lightOffRes = processor.processCommand("light off")
        assertEquals("Flashlight turned off.", lightOffRes)

        val louderRes = processor.processCommand("louder")
        assertEquals("Volume increased to 60 percent.", louderRes)

        val quieterRes = processor.processCommand("quieter")
        assertEquals("Volume decreased to 40 percent.", quieterRes)

        val muteRes = processor.processCommand("mute")
        assertEquals("Media volume muted.", muteRes)

        val unmuteRes = processor.processCommand("unmute")
        assertEquals("Media volume unmuted.", unmuteRes)

        val whereAmI = processor.processCommand("where am i")
        assertEquals("Your current location is Malir, Karachi, Pakistan.", whereAmI)

        val screenRes = processor.processCommand("screen")
        assertEquals("Screen contains: Blind AI Assistant.", screenRes)

        val readScreen = processor.processCommand("read screen")
        assertEquals("Screen contains: Blind AI Assistant.", readScreen)

        val moneyRes = processor.processCommand("money")
        assertEquals("Detected currency: 500 Pakistani Rupees.", moneyRes)

        val cashRes = processor.processCommand("cash")
        assertEquals("Detected currency: 500 Pakistani Rupees.", cashRes)

        val textRes = processor.processCommand("read text")
        assertEquals("Scanned text: Aspirin 100mg take twice daily.", textRes)

        val colorRes = processor.processCommand("color")
        assertEquals("Detected color: Navy blue with white stripes.", colorRes)

        val describeRes = processor.processCommand("describe")
        assertEquals("In front of you at two meters is a table, on your right is a chair, and on your left is a door.", describeRes)

        val lookRes = processor.processCommand("look")
        assertEquals("In front of you at two meters is a table, on your right is a chair, and on your left is a door.", lookRes)

        val obstacleRes = processor.processCommand("obstacles")
        assertEquals("I couldn't capture an image from the camera. Please hold the device steady and try again.", obstacleRes)

        val commandsRes = processor.processCommand("commands")
        assertTrue(commandsRes.contains("Here are simple commands you can say"))

        val whatCanYouDo = processor.processCommand("what can you do")
        assertTrue(whatCanYouDo.contains("Here are simple commands you can say"))
    }

    @Test
    fun testConversationalFillerStripping() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val timeRes = processor.processCommand("Please can you tell me the time please?")
        assertEquals("The current time is 2:30 PM.", timeRes)

        val batteryRes = processor.processCommand("Can you please check my battery for me?")
        assertEquals("Battery is at 85 percent.", batteryRes)

        val torchRes = processor.processCommand("Could you please turn on flashlight?")
        assertEquals("Flashlight turned on.", torchRes)

        val torchOffRes = processor.processCommand("Turn off the torch now")
        assertEquals("Flashlight turned off.", torchOffRes)

        val skipAdRes = processor.processCommand("Can you skip the ad please")
        assertEquals("Ad skipped.", skipAdRes)

        val louderRes = processor.processCommand("Please make it louder")
        assertEquals("Volume increased to 60 percent.", louderRes)

        val locRes = processor.processCommand("Could you tell me where I am, please?")
        assertEquals("Your current location is Malir, Karachi, Pakistan.", locRes)

        val callRes = processor.processCommand("Can you call Ali please")
        assertEquals("Calling Ali.", callRes)

        val pauseRes = processor.processCommand("Can you pause the video for me")
        assertEquals("Video paused.", pauseRes)

        val resumeRes = processor.processCommand("Please resume the video")
        assertEquals("Video resumed.", resumeRes)
    }
}
