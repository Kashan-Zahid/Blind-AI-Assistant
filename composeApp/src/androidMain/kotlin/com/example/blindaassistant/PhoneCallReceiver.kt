package com.example.blindaassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

class PhoneCallReceiver : BroadcastReceiver() {

    companion object {
        var isRinging = false
            private set
        private var originalRingVolume: Int = -1
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                isRinging = true
                
                // Lower ringtone volume so it doesn't interfere with voice command
                try {
                    audioManager?.let { am ->
                        if (originalRingVolume == -1) {
                            originalRingVolume = am.getStreamVolume(AudioManager.STREAM_RING)
                        }
                        // Set to lowest audible level (1) instead of 0 to avoid DND mode issues
                        am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
                    }
                } catch (_: Exception) {}

                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                val contactsManager = ContactsAndCallManager(context)
                val callerName = if (incomingNumber.isNotBlank()) {
                    contactsManager.getContactNameFromNumber(incomingNumber)
                } else {
                    null
                }

                val callerInfo = if (!callerName.isNullOrBlank()) callerName else if (incomingNumber.isNotBlank()) incomingNumber else "Unknown caller"
                DeviceController.currentCallState = CallState.INCOMING_CELLULAR_CALL
                DeviceController.currentCellularCaller = callerInfo

                val announcement = "Incoming call from $callerInfo. Say 'Answer' or 'Decline'."

                AndroidVoiceService.speakGlobally(announcement)

                // Start interactive voice listener after announcement to listen for "Answer" or "Decline"
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isRinging) {
                        AndroidVoiceService.activeInstance?.startListening()
                    }
                }, 3500) // Slightly longer delay to ensure TTS finished
            } else if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                isRinging = false
                DeviceController.currentCallState = CallState.ACTIVE_CELLULAR_CALL
                
                // Restore original ringtone volume
                try {
                    audioManager?.let { am ->
                        if (originalRingVolume != -1) {
                            am.setStreamVolume(AudioManager.STREAM_RING, originalRingVolume, 0)
                            originalRingVolume = -1
                        }
                    }
                } catch (_: Exception) {}
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                isRinging = false
                DeviceController.currentCallState = CallState.IDLE
                DeviceController.currentCellularCaller = ""
                
                // Restore original ringtone volume
                try {
                    audioManager?.let { am ->
                        if (originalRingVolume != -1) {
                            am.setStreamVolume(AudioManager.STREAM_RING, originalRingVolume, 0)
                            originalRingVolume = -1
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
