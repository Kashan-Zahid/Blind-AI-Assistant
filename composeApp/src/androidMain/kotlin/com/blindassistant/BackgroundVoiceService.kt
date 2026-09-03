package com.blindassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Background Service for Blind AI Assistant.
 * Maintains foreground presence, audio focus handling, and notification actions
 * so voice commands remain active when YouTube or other apps are in foreground.
 */
class BackgroundVoiceService : Service() {

    companion object {
        const val ACTION_START_VOICE = "com.blindassistant.START_BACKGROUND_VOICE"
        const val ACTION_STOP_VOICE = "com.blindassistant.STOP_BACKGROUND_VOICE"
        const val ACTION_TRIGGER_VOICE = "com.blindassistant.TRIGGER_VOICE_COMMAND"
        private const val CHANNEL_ID = "blind_assistant_voice_channel"
        private const val NOTIF_ID = 7001

        var isRunning = false
            private set

        var isAppInForeground = true
            private set

        var activeInstance: BackgroundVoiceService? = null
            private set

        fun setAppInForeground(inForeground: Boolean) {
            isAppInForeground = inForeground
        }

        fun start(context: Context) {
            val intent = Intent(context, BackgroundVoiceService::class.java).apply {
                action = ACTION_START_VOICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundVoiceService::class.java).apply {
                action = ACTION_STOP_VOICE
            }
            context.startService(intent)
        }

        fun triggerVoiceCommand(context: Context) {
            val intent = Intent(context, BackgroundVoiceService::class.java).apply {
                action = ACTION_TRIGGER_VOICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        activeInstance = this
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VOICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_VOICE -> {
                AndroidVoiceService.startListeningGlobally()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Blind Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice assistant active for blind users"
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags)

        val triggerIntent = Intent(this, BackgroundVoiceService::class.java).apply {
            action = ACTION_TRIGGER_VOICE
        }
        val triggerPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 1, triggerIntent, flags)
        } else {
            PendingIntent.getService(this, 1, triggerIntent, flags)
        }

        val speakAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            "Tap to Speak",
            triggerPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Blind Assistant Active")
            .setContentText("Hold the center button or double-tap volume to speak anytime.")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(speakAction)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (activeInstance == this) {
            activeInstance = null
        }
    }
}
