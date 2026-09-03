package com.example.blindaassistant

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class WhatsAppCallFlowTest {

    private class FakeAudioGateway : RingtoneAudioGateway {
        var ringLevel = 7
        var musicLevel = 10
        var focusRequests = 0
        var focusAbandons = 0
        override fun getRingVolume(): Int = ringLevel
        override fun setRingVolume(volume: Int) { ringLevel = volume }
        override fun getMusicVolume(): Int = musicLevel
        override fun setMusicVolume(volume: Int) { musicLevel = volume }
        override fun requestDuckingFocus() { focusRequests++ }
        override fun abandonDuckingFocus() { focusAbandons++ }
    }

    private class ManualClock(var now: Long = 1000L) {
        val read: () -> Long = { now }
    }

    // ----------------------------------------------------
    // AUDIO DUCKING & RESTORATION
    // ----------------------------------------------------

    @Test
    fun testDuckCapturesPreviousVolumesAndRestoreReturnsThem() {
        val gateway = FakeAudioGateway()
        val session = IncomingCallAudioSession(gateway)

        session.duck()
        assertEquals(1, gateway.ringLevel)
        assertEquals(1, gateway.musicLevel)
        assertTrue(session.isDucked)
        assertEquals(1, gateway.focusRequests)

        session.restore()
        assertEquals(7, gateway.ringLevel)
        assertEquals(10, gateway.musicLevel)
        assertFalse(session.isDucked)
        assertEquals(1, gateway.focusAbandons)
    }

    @Test
    fun testDoubleDuckKeepsOriginalCapturedVolumes() {
        val gateway = FakeAudioGateway()
        val session = IncomingCallAudioSession(gateway)

        session.duck()
        gateway.ringLevel = 4
        session.duck()

        session.restore()
        assertEquals(7, gateway.ringLevel)
        assertEquals(10, gateway.musicLevel)
        assertEquals(1, gateway.focusRequests)
    }

    @Test
    fun testRestoreWithoutDuckIsNoOp() {
        val gateway = FakeAudioGateway()
        val session = IncomingCallAudioSession(gateway)

        session.restore()
        assertEquals(7, gateway.ringLevel)
        assertEquals(10, gateway.musicLevel)
        assertEquals(0, gateway.focusAbandons)
    }

    @Test
    fun testAudioRestoredOnAnswerDeclineAndTimeoutPaths() {
        val gateway = FakeAudioGateway()
        val session = IncomingCallAudioSession(gateway)

        session.duck()
        session.restore()
        assertEquals(7, gateway.ringLevel)

        session.duck()
        session.restore()
        assertEquals(7, gateway.ringLevel)

        session.duck()
        session.restore()
        assertEquals(7, gateway.ringLevel)
        assertEquals(10, gateway.musicLevel)
        assertEquals(gateway.focusRequests, gateway.focusAbandons)
    }

    // ----------------------------------------------------
    // CALLER DISPLAY WORDING
    // ----------------------------------------------------

    @Test
    fun testCallerDisplaySanitizationRemovesOnWhatsAppSuffix() {
        assertEquals("03001234567", WhatsAppCallerDisplay.sanitize("03001234567 on WhatsApp"))
        assertEquals("03001234567", WhatsAppCallerDisplay.sanitize("03001234567 ON WHATSAPP"))
        assertEquals("Ahmed", WhatsAppCallerDisplay.sanitize("Ahmed"))
        assertEquals("", WhatsAppCallerDisplay.sanitize("  "))
    }

    @Test
    fun testCallerAnnouncementWording() {
        assertEquals("Incoming WhatsApp call from Ahmed.", WhatsAppCallerDisplay.announcement("Ahmed", false))
        assertEquals("Incoming WhatsApp video call from Ahmed.", WhatsAppCallerDisplay.announcement("Ahmed", true))
        assertEquals("Incoming WhatsApp call from 03001234567.", WhatsAppCallerDisplay.announcement("03001234567", false))
        assertEquals("Incoming WhatsApp call from an unknown caller.", WhatsAppCallerDisplay.announcement("", false))
    }

    // ----------------------------------------------------
    // STATE MACHINE: DETECTION & SEPARATION
    // ----------------------------------------------------

    @Test
    fun testDetectionIsRejectedWhileCellularCallIsActive() {
        val flow = WhatsAppCallFlow()
        assertFalse(flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = true))
        assertEquals(WhatsAppCallPhase.IDLE, flow.phase)
    }

    @Test
    fun testDetectionIsRejectedWhileAnotherWhatsAppSessionIsActive() {
        val flow = WhatsAppCallFlow()
        assertTrue(flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false))
        assertFalse(flow.onIncomingCallDetected("Ali", isVideo = true, cellularCallActive = false))
        assertEquals("Ahmed", flow.callerDisplay)
    }

    // ----------------------------------------------------
    // STATE MACHINE: TTS-BEFORE-RECOGNITION SEQUENCING
    // ----------------------------------------------------

    @Test
    fun testTtsCompletesBeforeRecognitionIsAllowed() {
        val clock = ManualClock()
        val flow = WhatsAppCallFlow(clock.read)

        flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        assertEquals(WhatsAppCallPhase.STABILIZING, flow.phase)
        assertFalse(flow.canStartRecognition(isSpeaking = false))

        flow.onAnnouncementStarted()
        assertEquals(WhatsAppCallPhase.ANNOUNCING_CALLER, flow.phase)
        assertFalse(flow.canStartRecognition(isSpeaking = false))

        flow.onAnnouncementCompleted()
        assertEquals(WhatsAppCallPhase.WAITING_FOR_CALL_COMMAND, flow.phase)
        assertTrue(flow.canStartRecognition(isSpeaking = false))
    }

    @Test
    fun testRecognitionBlockedWhileTtsIsSpeaking() {
        val flow = WhatsAppCallFlow()
        flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        flow.onAnnouncementStarted()
        flow.onAnnouncementCompleted()

        assertFalse(flow.canStartRecognition(isSpeaking = true))
    }

    // ----------------------------------------------------
    // STATE MACHINE: TIMEOUT & TERMINAL STATES
    // ----------------------------------------------------

    @Test
    fun testDecisionWindowTimesOutAndStopsListening() {
        val clock = ManualClock()
        val flow = WhatsAppCallFlow(clock.read)
        flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        flow.onAnnouncementStarted()
        flow.onAnnouncementCompleted()

        assertTrue(flow.canStartRecognition(isSpeaking = false))

        clock.now += WhatsAppCallFlow.DECISION_WINDOW_MS + 1
        assertTrue(flow.isDecisionExpired())
        assertFalse(flow.canStartRecognition(isSpeaking = false))

        flow.onTimedOut()
        assertEquals(WhatsAppCallPhase.TIMED_OUT, flow.phase)
        assertFalse(flow.isActive)
    }

    @Test
    fun testAnswerDeclineAndDismissEndTheSession() {
        val flow = WhatsAppCallFlow()
        flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        flow.onAnnouncementStarted()
        flow.onAnnouncementCompleted()
        flow.onAnswered()
        assertEquals(WhatsAppCallPhase.ANSWERED, flow.phase)
        assertFalse(flow.isActive)

        val flow2 = WhatsAppCallFlow()
        flow2.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        flow2.onAnnouncementStarted()
        flow2.onAnnouncementCompleted()
        flow2.onDeclined()
        assertEquals(WhatsAppCallPhase.DECLINED, flow2.phase)
        assertFalse(flow2.isActive)

        val flow3 = WhatsAppCallFlow()
        flow3.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        flow3.onAnnouncementStarted()
        flow3.onAnnouncementCompleted()
        flow3.onCallDismissed()
        assertEquals(WhatsAppCallPhase.CALL_ENDED, flow3.phase)
        assertFalse(flow3.isActive)
    }

    @Test
    fun testResetAllowsNewSession() {
        val flow = WhatsAppCallFlow()
        flow.onIncomingCallDetected("Ahmed", isVideo = false, cellularCallActive = false)
        flow.reset()
        assertEquals(WhatsAppCallPhase.IDLE, flow.phase)

        assertTrue(flow.onIncomingCallDetected("Ali", isVideo = true, cellularCallActive = false))
        assertTrue(flow.isVideoCall)
        assertEquals("Ali", flow.callerDisplay)
    }
}
