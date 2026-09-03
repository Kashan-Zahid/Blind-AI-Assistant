package com.example.blindaassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BroadcastReceiver triggered by AlarmManager at exact alarm time.
 * Plays sound, vibrates, and announces time via TTS for blind user.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.example.blindaassistant.ALARM_TRIGGER"
        const val ACTION_DISMISS_ALARM = "com.example.blindaassistant.DISMISS_ALARM"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_TIME_DISPLAY = "extra_time_display"
        private const val CHANNEL_ID = "blind_assistant_alarm_channel"
        private const val NOTIF_ID = 9001

        private var activePlayer: MediaPlayer? = null

        fun stopActiveAlarm() {
            try {
                activePlayer?.stop()
                activePlayer?.release()
                activePlayer = null
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == ACTION_DISMISS_ALARM) {
            stopActiveAlarm()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_ID)
            AndroidVoiceService.speakGlobally("Alarm stopped.")
            return
        }

        if (action == ACTION_ALARM_TRIGGER || action == Intent.ACTION_BOOT_COMPLETED) {
            val timeDisplay = intent.getStringExtra(EXTRA_TIME_DISPLAY)
                ?: SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

            val spokenMessage = "Alarm. It is $timeDisplay."

            // 1. Play Alarm Sound
            playAlarmSound(context)

            // 2. Vibrate
            vibrateAlarm(context)

            // 3. Spoken Announcement
            AndroidVoiceService.speakGlobally(spokenMessage)

            // 4. Show Notification with Dismiss button
            showAlarmNotification(context, timeDisplay)
        }
    }

    private fun playAlarmSound(context: Context) {
        try {
            stopActiveAlarm()
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            activePlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun vibrateAlarm(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            val timings = longArrayOf(0, 500, 200, 500, 200, 1000)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    private fun showAlarmNotification(context: Context, timeDisplay: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Blind Assistant Alarm Alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val dismissIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val dismissPending = PendingIntent.getBroadcast(context, 9002, dismissIntent, flags)

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPending = PendingIntent.getActivity(context, 9003, fullScreenIntent, flags)

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm: $timeDisplay")
            .setContentText("Alarm is ringing. Tap Dismiss to stop.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss Alarm", dismissPending)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}
