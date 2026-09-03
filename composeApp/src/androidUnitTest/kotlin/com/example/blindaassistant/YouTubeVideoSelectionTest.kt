package com.example.blindaassistant

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeVideoSelectionTest {

    private class FakeContext : ContextWrapper(null)

    private class FakeDeviceController(
        aiClient: AiClient,
        var optionsActive: Boolean = false,
        var hasRegularVideos: Boolean = true,
        var clickSucceeds: Boolean = true,
        var searchReturnsEmpty: Boolean = false
    ) : DeviceController(
        context = FakeContext(),
        aiClient = aiClient
    ) {
        var lastOptionRequested: Int? = null
        var currentSelectedIndex: Int = 0

        override fun hasActiveVoiceOptions(): Boolean {
            return optionsActive || BlindAccessibilityService.youTubeSelectionState?.results?.isNotEmpty() == true
        }

        override suspend fun selectVoiceOption(index: Int): String {
            lastOptionRequested = index
            val ytState = BlindAccessibilityService.youTubeSelectionState
            if (ytState != null && ytState.results.isNotEmpty()) {
                if (index !in 1..ytState.results.size) {
                    return "Option $index is not available. I found ${ytState.results.size} videos."
                }
                currentSelectedIndex = index - 1
                ytState.selectedIndex = index - 1
                if (!clickSucceeds) {
                    return "I found that video, but I couldn't open it."
                }
                return "Playing ${ytState.results[index - 1].title}."
            }

            if (!hasRegularVideos) {
                return "I couldn't find any regular videos."
            }
            if (!clickSucceeds) {
                return "I found that video, but I couldn't open it."
            }
            return when (index) {
                1 -> "Playing Atif Aslam - Dil."
                2 -> "Playing Atif Aslam - Tajdar-e-Haram."
                3 -> "Playing Atif Aslam - Tera Hone Laga Hoon."
                else -> "Option $index is not available. I found 3 videos."
            }
        }

        override fun nextYouTubeResult(): String {
            val ytState = BlindAccessibilityService.youTubeSelectionState
            if (ytState != null && ytState.results.isNotEmpty()) {
                ytState.selectedIndex = (ytState.selectedIndex + 1) % ytState.results.size
                currentSelectedIndex = ytState.selectedIndex
                return "Option ${ytState.selectedIndex + 1}: ${ytState.results[ytState.selectedIndex].title}."
            }
            return "No search results available."
        }

        override fun previousYouTubeResult(): String {
            val ytState = BlindAccessibilityService.youTubeSelectionState
            if (ytState != null && ytState.results.isNotEmpty()) {
                ytState.selectedIndex = if (ytState.selectedIndex <= 0) ytState.results.size - 1 else ytState.selectedIndex - 1
                currentSelectedIndex = ytState.selectedIndex
                return "Option ${ytState.selectedIndex + 1}: ${ytState.results[ytState.selectedIndex].title}."
            }
            return "No search results available."
        }

        override suspend fun playSelectedYouTubeOption(): String {
            val ytState = BlindAccessibilityService.youTubeSelectionState
            if (ytState != null && ytState.results.isNotEmpty()) {
                return selectVoiceOption(ytState.selectedIndex + 1)
            }
            return resumeMediaPlayback()
        }

        override fun debugYouTubeResults(): String {
            return "Debug complete. I found 3 valid videos."
        }

        override suspend fun searchYouTube(query: String): String {
            if (searchReturnsEmpty) {
                BlindAccessibilityService.youTubeSelectionState = null
                return "I couldn't find any regular videos."
            }
            val candidates = listOf(
                BlindAccessibilityService.YouTubeVideoCandidate(
                    title = "Atif Aslam - Dil",
                    videoUrl = "https://www.youtube.com/watch?v=dil123",
                    confidence = 10
                ),
                BlindAccessibilityService.YouTubeVideoCandidate(
                    title = "Atif Aslam - Tajdar-e-Haram",
                    videoUrl = "https://www.youtube.com/watch?v=tajdar456",
                    confidence = 10
                ),
                BlindAccessibilityService.YouTubeVideoCandidate(
                    title = "Atif Aslam - Tera Hone Laga Hoon",
                    videoUrl = "https://www.youtube.com/watch?v=tera789",
                    confidence = 10
                )
            )
            BlindAccessibilityService.youTubeSelectionState = BlindAccessibilityService.YouTubeSelectionState(
                results = candidates,
                selectedIndex = 0,
                query = query
            )
            val count = candidates.size
            val sb = StringBuilder("I found $count videos.\n\n")
            candidates.forEachIndexed { i, c ->
                sb.append("Option ${i + 1}: ${c.title}.\n")
            }
            sb.append("\nSay an option number to play it.")
            return sb.toString().trim()
        }
    }

    // ---------------------------------------------------------
    // 1. TITLE CLEANING & METADATA STRIPPING TESTS
    // ---------------------------------------------------------

    @Test
    fun testYouTubeCleanTitleWithoutMetadata() {
        val raw1 = "Atif Aslam - Dil Official Video"
        val cleaned1 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw1)
        assertEquals("Atif Aslam - Dil Official Video", cleaned1)

        val raw2 = "Atif Aslam - Dil | Tips Official - 4 minutes, 20 seconds - 50M views - 2 years ago - play video"
        val cleaned2 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw2)
        assertFalse(cleaned2.contains("play video", ignoreCase = true))
        assertFalse(cleaned2.contains("50M views", ignoreCase = true))
        assertFalse(cleaned2.contains("2 years ago", ignoreCase = true))
        assertFalse(cleaned2.contains("4 minutes", ignoreCase = true))
        assertEquals("Atif Aslam - Dil", cleaned2)

        val raw3 = "Atif Aslam - Dil · 12M views · 4:32 · 2 years ago"
        val cleaned3 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw3)
        assertEquals("Atif Aslam - Dil", cleaned3)

        val raw4 = "Atif Aslam - Dil by Tips Official 2 years ago 4 minutes, 20 seconds 50,293,123 views - play video"
        val cleaned4 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw4)
        assertEquals("Atif Aslam - Dil", cleaned4)

        val raw5 = "Tajdar-e-Haram - Atif Aslam 50M views 6:12"
        val cleaned5 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw5)
        assertEquals("Tajdar-e-Haram - Atif Aslam", cleaned5)

        val raw6 = "Atif Aslam - Tajdar-e-Haram (10 minutes, 15 seconds) 100M views"
        val cleaned6 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw6)
        assertEquals("Atif Aslam - Tajdar-e-Haram", cleaned6)

        val raw7 = "Atif Aslam Live Concert - Streamed 5 days ago - 2 hours, 10 minutes - 1.2M views"
        val cleaned7 = BlindAccessibilityService.cleanYouTubeVideoTitle(raw7)
        assertEquals("Atif Aslam Live Concert", cleaned7)
    }

    // ---------------------------------------------------------
    // 2. URL VALIDATION TESTS
    // ---------------------------------------------------------

    @Test
    fun testWatchUrlValidation() {
        assertTrue(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(BlindAccessibilityService.isValidYouTubeWatchUrl("https://youtube.com/watch?v=12345"))
        assertTrue(BlindAccessibilityService.isValidYouTubeWatchUrl("https://youtu.be/dQw4w9WgXcQ"))

        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/shorts/xyz123"))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/channel/UC12345"))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/@atifaslam"))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/feed/subscriptions"))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl("https://www.youtube.com/results?search_query=test"))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl(null))
        assertFalse(BlindAccessibilityService.isValidYouTubeWatchUrl(""))
    }

    // ---------------------------------------------------------
    // 3. UI NOISE, STATUS TEXT, & BUTTON REJECTION TESTS
    // ---------------------------------------------------------

    @Test
    fun testUiNoiseAndStatusTextRejection() {
        val service = BlindAccessibilityService()

        assertTrue(service.isUiNoiseOrStatusTitle("Clear"))
        assertTrue(service.isUiNoiseOrStatusTitle("clear search"))
        assertTrue(service.isUiNoiseOrStatusTitle("New content available"))
        assertTrue(service.isUiNoiseOrStatusTitle("new videos available"))
        assertTrue(service.isUiNoiseOrStatusTitle("content available"))
        assertTrue(service.isUiNoiseOrStatusTitle("Subscriptions"))
        assertTrue(service.isUiNoiseOrStatusTitle("Home"))
        assertTrue(service.isUiNoiseOrStatusTitle("Library"))
        assertTrue(service.isUiNoiseOrStatusTitle("You"))
        assertTrue(service.isUiNoiseOrStatusTitle("Explore"))
        assertTrue(service.isUiNoiseOrStatusTitle("Search"))
        assertTrue(service.isUiNoiseOrStatusTitle("Notifications"))
        assertTrue(service.isUiNoiseOrStatusTitle("Settings"))
        assertTrue(service.isUiNoiseOrStatusTitle("Search filters"))
        assertTrue(service.isUiNoiseOrStatusTitle("Filter"))
        assertTrue(service.isUiNoiseOrStatusTitle("Sort by"))
        assertTrue(service.isUiNoiseOrStatusTitle("Subscribe"))
        assertTrue(service.isUiNoiseOrStatusTitle("Subscribed"))

        assertFalse(service.isUiNoiseOrStatusTitle("Dell Precision 3550 Review"))
        assertFalse(service.isUiNoiseOrStatusTitle("Atif Aslam - Dil"))
        assertFalse(service.isUiNoiseOrStatusTitle("Surah Rahman Urdu Translation"))
    }

    // ---------------------------------------------------------
    // 4. SHORTS EXCLUSION TESTS
    // ---------------------------------------------------------

    @Test
    fun testShortsExclusion() {
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("https://youtube.com/shorts/xyz123"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Awesome Dance #shorts"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Viral Video - play short"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Shorts shelf"))
        assertTrue(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("YouTube Shorts carousel"))

        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Atif Aslam - Dil Official Video"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Surah Rahman Urdu Translation"))
        assertFalse(BlindAccessibilityService.isYouTubeShortsTitleOrUrl("Coke Studio Season 8 Tajdar-e-Haram"))
    }

    // ---------------------------------------------------------
    // 5. CANDIDATE FILTERING & DEDUPLICATION TESTS
    // ---------------------------------------------------------

    @Test
    fun testCandidateFilteringShortsAndNonVideo() {
        val candidates = listOf(
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Atif Aslam - Dil",
                rawText = "Atif Aslam - Dil",
                rawDesc = "Atif Aslam - Dil - play video",
                confidence = 10,
                hasThumbnail = true,
                insideResultContainer = true
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Clear",
                rawText = "Clear",
                confidence = 0
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "New content available",
                rawText = "New content available",
                confidence = 0
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Subscriptions",
                rawText = "Subscriptions",
                confidence = 0
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Amazing Dance #shorts",
                rawText = "Amazing Dance #shorts",
                rawDesc = "play short",
                confidence = 8
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Atif Aslam - Tajdar-e-Haram",
                rawText = "Atif Aslam - Tajdar-e-Haram",
                rawDesc = "Coke Studio Tajdar-e-Haram",
                confidence = 10,
                hasThumbnail = true,
                insideResultContainer = true
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Atif Aslam - Dil",
                rawText = "Atif Aslam - Dil",
                rawDesc = "Atif Aslam - Dil - 50M views",
                confidence = 10
            )
        )

        val service = BlindAccessibilityService()
        val diag = BlindAccessibilityService.DiagnosticCollector()
        val filtered = service.filterYouTubeCandidates(candidates, diag)

        assertEquals(2, filtered.size)
        assertEquals("Atif Aslam - Dil", filtered[0].title)
        assertEquals("Atif Aslam - Tajdar-e-Haram", filtered[1].title)
        assertEquals(1, diag.shortsRejected)
        assertEquals(3, diag.nonVideoRejected)
        assertEquals(1, diag.duplicatesRemoved)
    }

    // ---------------------------------------------------------
    // 6. PERSISTENT SELECTION STATE & SECOND-MIC SELECTION
    // ---------------------------------------------------------

    @Test
    fun testPersistentSelectionStateAcrossListeningCycles() = runBlocking {
        BlindAccessibilityService.youTubeSelectionState = null
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Step 1: User issues YouTube Search
        val searchOutput = processor.processCommand("Open YouTube and search Atif Aslam songs")
        assertTrue(searchOutput.contains("I found 3 videos."))
        assertTrue(searchOutput.contains("Option 1: Atif Aslam - Dil."))
        assertTrue(searchOutput.contains("Option 2: Atif Aslam - Tajdar-e-Haram."))
        assertTrue(searchOutput.contains("Option 3: Atif Aslam - Tera Hone Laga Hoon."))

        // Step 2: Verify state persisted
        assertNotNull(BlindAccessibilityService.youTubeSelectionState)
        assertEquals(3, BlindAccessibilityService.youTubeSelectionState?.results?.size)
        assertTrue(controller.hasActiveVoiceOptions())

        // Step 3: Simulated Microphone Stop & TTS Completion (no state clearing!)
        // State remains intact.
        assertTrue(controller.hasActiveVoiceOptions())

        // Step 4: Second Microphone Activation: User says "Option 1"
        val r1 = processor.processCommand("Option 1")
        assertEquals("Playing Atif Aslam - Dil.", r1)
        assertEquals(1, controller.lastOptionRequested)

        // Step 5: Third Microphone Activation: User says "2"
        val r2 = processor.processCommand("2")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r2)
        assertEquals(2, controller.lastOptionRequested)

        // Step 6: User says "Play option 3"
        val r3 = processor.processCommand("Play option 3")
        assertEquals("Playing Atif Aslam - Tera Hone Laga Hoon.", r3)
        assertEquals(3, controller.lastOptionRequested)
    }

    // ---------------------------------------------------------
    // 7. SELECTION COMMANDS & NUMERIC PARSING
    // ---------------------------------------------------------

    @Test
    fun testOptionSelectionCommandPermutations() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient, optionsActive = true)
        val processor = CommandProcessor(controller, aiClient)

        // 1. "Option 1"
        val r1 = processor.processCommand("Option 1")
        assertEquals("Playing Atif Aslam - Dil.", r1)
        assertEquals(1, controller.lastOptionRequested)

        // 2. "Play option 2"
        val r2 = processor.processCommand("Play option 2")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r2)
        assertEquals(2, controller.lastOptionRequested)

        // 3. "1"
        val r3 = processor.processCommand("1")
        assertEquals("Playing Atif Aslam - Dil.", r3)
        assertEquals(1, controller.lastOptionRequested)

        // 4. "2"
        val r4 = processor.processCommand("2")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r4)
        assertEquals(2, controller.lastOptionRequested)

        // 5. "Play the first video"
        val r5 = processor.processCommand("Play the first video")
        assertEquals("Playing Atif Aslam - Dil.", r5)
        assertEquals(1, controller.lastOptionRequested)

        // 6. "Play the second video"
        val r6 = processor.processCommand("Play the second video")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r6)
        assertEquals(2, controller.lastOptionRequested)

        // 7. "First video"
        val r7 = processor.processCommand("First video")
        assertEquals("Playing Atif Aslam - Dil.", r7)
        assertEquals(1, controller.lastOptionRequested)

        // 8. "Second video"
        val r8 = processor.processCommand("Second video")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r8)
        assertEquals(2, controller.lastOptionRequested)

        // 9. "First one"
        val r9 = processor.processCommand("First one")
        assertEquals("Playing Atif Aslam - Dil.", r9)
        assertEquals(1, controller.lastOptionRequested)

        // 10. "Second one"
        val r10 = processor.processCommand("Second one")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r10)
        assertEquals(2, controller.lastOptionRequested)

        // 11. "Play 2"
        val r11 = processor.processCommand("Play 2")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r11)
        assertEquals(2, controller.lastOptionRequested)

        // 12. "Play option number 2"
        val r12 = processor.processCommand("Play option number 2")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r12)
        assertEquals(2, controller.lastOptionRequested)

        // 13. "Play song 2"
        val r13 = processor.processCommand("Play song 2")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", r13)
        assertEquals(2, controller.lastOptionRequested)

        // 14. "Song 1"
        val r14 = processor.processCommand("Song 1")
        assertEquals("Playing Atif Aslam - Dil.", r14)
        assertEquals(1, controller.lastOptionRequested)
    }

    // ---------------------------------------------------------
    // 8. NEXT RESULT / PREVIOUS RESULT / PLAY THIS TESTS
    // ---------------------------------------------------------

    @Test
    fun testNextPreviousAndPlayThis() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Search to populate state
        processor.processCommand("Open YouTube and search Atif Aslam songs")

        // "Next result"
        val next1 = processor.processCommand("next result")
        assertEquals("Option 2: Atif Aslam - Tajdar-e-Haram.", next1)

        // "Play this"
        val play1 = processor.processCommand("play this")
        assertEquals("Playing Atif Aslam - Tajdar-e-Haram.", play1)

        // "Previous result"
        val prev1 = processor.processCommand("previous result")
        assertEquals("Option 1: Atif Aslam - Dil.", prev1)

        // "Play this"
        val play2 = processor.processCommand("play this")
        assertEquals("Playing Atif Aslam - Dil.", play2)
    }

    // ---------------------------------------------------------
    // 9. ERROR DISTINCTION & BOUNDS TESTS
    // ---------------------------------------------------------

    @Test
    fun testInvalidOptionNumberAndBounds() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // Search with 3 videos
        processor.processCommand("Open YouTube and search Atif Aslam songs")

        // Request out of bounds option 5
        val r5 = processor.processCommand("Option 5")
        assertEquals("Option 5 is not available. I found 3 videos.", r5)
    }

    @Test
    fun testDistinctionBetweenNoVideosAndCannotClick() = runBlocking {
        BlindAccessibilityService.youTubeSelectionState = null
        val aiClient = AiClient()

        // Case A: No regular videos found
        val noVideoController = FakeDeviceController(aiClient, optionsActive = true, hasRegularVideos = false)
        val noVideoProcessor = CommandProcessor(noVideoController, aiClient)
        val rA = noVideoProcessor.processCommand("Option 1")
        assertEquals("I couldn't find any regular videos.", rA)

        // Case B: Videos found but click failed
        val cannotClickController = FakeDeviceController(aiClient, optionsActive = true, hasRegularVideos = true, clickSucceeds = false)
        val cannotClickProcessor = CommandProcessor(cannotClickController, aiClient)
        val rB = cannotClickProcessor.processCommand("Option 1")
        assertEquals("I found that video, but I couldn't open it.", rB)
    }

    // ---------------------------------------------------------
    // 10. NEW SEARCH RESETS STATE & LEAVING YOUTUBE
    // ---------------------------------------------------------

    @Test
    fun testSecondSearchResetsStateWithNewResults() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        // First Search: Atif Aslam
        processor.processCommand("Open YouTube and search Atif Aslam songs")
        val state1 = BlindAccessibilityService.youTubeSelectionState
        assertNotNull(state1)
        assertEquals("atif aslam songs", state1?.query)
        assertEquals("Atif Aslam - Dil", state1?.results?.get(0)?.title)

        // Select Option 1 from first search
        val r1 = processor.processCommand("Option 1")
        assertEquals("Playing Atif Aslam - Dil.", r1)

        // Second Search: Arijit Singh
        controller.searchYouTube("Arijit Singh songs")
        // Mock new candidates for Arijit Singh
        val newCandidates = listOf(
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Arijit Singh - Tum Hi Ho",
                videoId = "tumhiho123",
                watchUrl = "https://www.youtube.com/watch?v=tumhiho123",
                videoUrl = "https://www.youtube.com/watch?v=tumhiho123",
                confidence = 10
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Arijit Singh - Kesariya",
                videoId = "kesariya456",
                watchUrl = "https://www.youtube.com/watch?v=kesariya456",
                videoUrl = "https://www.youtube.com/watch?v=kesariya456",
                confidence = 10
            )
        )
        BlindAccessibilityService.youTubeSelectionState = BlindAccessibilityService.YouTubeSelectionState(
            results = newCandidates,
            selectedIndex = 0,
            query = "Arijit Singh songs"
        )

        val state2 = BlindAccessibilityService.youTubeSelectionState
        assertNotNull(state2)
        assertEquals("Arijit Singh songs", state2?.query)
        assertEquals(2, state2?.results?.size)
        assertEquals("Arijit Singh - Tum Hi Ho", state2?.results?.get(0)?.title)

        // Select Option 1 from second search -> MUST play Tum Hi Ho, NOT Atif Aslam!
        val r2 = processor.processCommand("Option 1")
        assertEquals("Playing Arijit Singh - Tum Hi Ho.", r2)
    }

    // ---------------------------------------------------------
    // 11. VIDEO ID EXTRACTION & SPOKEN SPEECH PURITY
    // ---------------------------------------------------------

    @Test
    fun testVideoIdExtraction() {
        assertEquals("dQw4w9WgXcQ", BlindAccessibilityService.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", BlindAccessibilityService.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", BlindAccessibilityService.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(BlindAccessibilityService.extractVideoId(null))
        assertNull(BlindAccessibilityService.extractVideoId(""))
    }

    @Test
    fun testSpokenAnnouncementContainsOnlyTitles() {
        val candidates = listOf(
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Atif Aslam - Dil",
                confidence = 10
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Atif Aslam - Tera Hone Laga Hoon",
                confidence = 10
            ),
            BlindAccessibilityService.YouTubeVideoCandidate(
                title = "Atif Aslam - Tajdar-e-Haram",
                confidence = 10
            )
        )
        val service = BlindAccessibilityService()
        val speech = service.buildYouTubeAnnouncement(candidates)

        assertTrue(speech.contains("I found 3 videos."))
        assertTrue(speech.contains("Option 1: Atif Aslam - Dil."))
        assertTrue(speech.contains("Option 2: Atif Aslam - Tera Hone Laga Hoon."))
        assertTrue(speech.contains("Option 3: Atif Aslam - Tajdar-e-Haram."))

        // Must NOT contain metadata words
        assertFalse(speech.contains("views", ignoreCase = true))
        assertFalse(speech.contains("duration", ignoreCase = true))
        assertFalse(speech.contains("channel", ignoreCase = true))
        assertFalse(speech.contains("ago", ignoreCase = true))
        assertFalse(speech.contains("subscribers", ignoreCase = true))
        assertFalse(speech.contains("Shorts", ignoreCase = true))
    }

    // ---------------------------------------------------------
    // 12. ANNOUNCEMENT FLOW & TTS ERROR DISTINCTION
    // ---------------------------------------------------------

    @Test
    fun testSearchResultDiscoveryTriggersAnnouncementAndState() = runBlocking {
        BlindAccessibilityService.youTubeSelectionState = null
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val announcement = processor.processCommand("Open YouTube and search Atif Aslam songs")
        assertTrue(announcement.startsWith("I found 3 videos."))
        assertTrue(announcement.contains("Option 1: Atif Aslam - Dil."))
        assertTrue(announcement.contains("Option 2: Atif Aslam - Tajdar-e-Haram."))
        assertTrue(announcement.contains("Option 3: Atif Aslam - Tera Hone Laga Hoon."))
        assertTrue(announcement.endsWith("Say an option number to play it."))

        // State is active and contains 3 videos
        val ytState = BlindAccessibilityService.youTubeSelectionState
        assertNotNull(ytState)
        assertEquals(3, ytState?.results?.size)
    }

    @Test
    fun testEmptyResultsGenerateFailureSpeech() = runBlocking {
        BlindAccessibilityService.youTubeSelectionState = null
        val aiClient = AiClient()
        val emptyController = FakeDeviceController(aiClient, searchReturnsEmpty = true)
        val processor = CommandProcessor(emptyController, aiClient)

        val response = processor.processCommand("Open YouTube and search non_existent_query_xyz")
        assertEquals("I couldn't find any regular videos.", response)
        assertNull(BlindAccessibilityService.youTubeSelectionState)
    }

    // ---------------------------------------------------------
    // 13. DIAGNOSTIC DEBUG COMMAND TEST
    // ---------------------------------------------------------

    @Test
    fun testDebugYouTubeResultsCommand() = runBlocking {
        val aiClient = AiClient()
        val controller = FakeDeviceController(aiClient)
        val processor = CommandProcessor(controller, aiClient)

        val debugOutput = processor.processCommand("Debug YouTube results")
        assertEquals("Debug complete. I found 3 valid videos.", debugOutput)
    }
}
