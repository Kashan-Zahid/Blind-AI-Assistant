package com.example.blindaassistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Local Alarm Manager handling persistent exact alarms using Android AlarmManager.
 * Does not require internet or cloud AI.
 */
class AlarmManagerHelper(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val prefs: SharedPreferences = context.getSharedPreferences("blind_assistant_alarms", Context.MODE_PRIVATE)

    data class StoredAlarm(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val isPm: Boolean,
        val timeDisplay: String,
        val triggerEpochMs: Long,
        val label: String
    )

    /**
     * Parses voice input for alarm commands.
     * Supports:
     * - "Set an alarm for 5 AM"
     * - "Set an alarm for 5:30 PM"
     * - "Wake me at 5" -> detects ambiguity -> "Did you mean 5 AM or 5 PM?"
     * - "Set alarm for tomorrow at 7 AM"
     */
    fun processAlarmVoiceCommand(input: String): String {
        val lower = input.lowercase().trim()

        // 1. List alarms
        if (lower in listOf("list alarms", "list my alarms", "show alarms", "what alarms do i have", "my alarms", "check alarms")) {
            return listAlarms()
        }

        // 2. Cancel alarm
        if (lower.startsWith("cancel alarm") || lower.startsWith("cancel my alarm") || lower.startsWith("delete alarm") || lower.startsWith("remove alarm")) {
            return cancelAlarmFromVoice(lower)
        }

        // 3. Set alarm
        return parseAndSetAlarm(lower)
    }

    private fun parseAndSetAlarm(input: String): String {
        var cleaned = input.removePrefix("set an alarm for ")
            .removePrefix("set alarm for ")
            .removePrefix("set an alarm at ")
            .removePrefix("set alarm at ")
            .removePrefix("wake me up at ")
            .removePrefix("wake me at ")
            .removePrefix("alarm for ")
            .removePrefix("alarm at ")
            .trim()

        val isTomorrow = cleaned.contains("tomorrow")
        cleaned = cleaned.replace("tomorrow", "").trim()

        val hasAm = cleaned.contains("am") || cleaned.contains("a.m.") || cleaned.contains("in the morning")
        val hasPm = cleaned.contains("pm") || cleaned.contains("p.m.") || cleaned.contains("in the evening") || cleaned.contains("at night") || cleaned.contains("afternoon")

        // Clean text to numbers and colon
        val timeText = cleaned.replace("am", "").replace("pm", "").replace("a.m.", "").replace("p.m.", "")
            .replace("in the morning", "").replace("in the evening", "").replace("at night", "").replace("afternoon", "")
            .replace("o'clock", "").replace("oclock", "").replace("?", "").replace(".", "").trim()

        val parts = timeText.split(":", " ", "-").filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return "Please specify the time for the alarm, for example, 'Set alarm for 5 AM'."
        }

        val hourParsed = parts[0].toIntOrNull()
        if (hourParsed == null || hourParsed < 0 || hourParsed > 23) {
            return "Could not understand the alarm time. Please say 'Set alarm for 5 AM' or 'Set alarm for 5:30 PM'."
        }

        val minuteParsed = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        if (minuteParsed < 0 || minuteParsed > 59) {
            return "Invalid minutes for alarm. Please say a valid time like '5:30 AM'."
        }

        // Check ambiguity if 12-hour format with no AM/PM provided
        if (!hasAm && !hasPm && hourParsed in 1..12) {
            val minStr = if (minuteParsed > 0) ":${String.format(Locale.getDefault(), "%02d", minuteParsed)}" else ""
            return "Did you mean $hourParsed$minStr AM or $hourParsed$minStr PM?"
        }

        // Determine 24-hour hour and AM/PM
        val isPmFinal = if (hasPm) true else if (hasAm) false else (hourParsed >= 12)
        var hour24 = hourParsed
        if (hasPm && hour24 < 12) {
            hour24 += 12
        } else if (hasAm && hour24 == 12) {
            hour24 = 0
        }

        val displayHour = if (hour24 == 0) 12 else if (hour24 > 12) hour24 - 12 else hour24
        val amPmStr = if (hour24 >= 12) "PM" else "AM"
        val minDisplay = String.format(Locale.getDefault(), "%02d", minuteParsed)
        val timeDisplay = if (minuteParsed == 0) "$displayHour $amPmStr" else "$displayHour:$minDisplay $amPmStr"

        // Schedule exact trigger time
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minuteParsed)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (isTomorrow || timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val alarmId = hour24 * 100 + minuteParsed
        val stored = StoredAlarm(
            id = alarmId,
            hour = hour24,
            minute = minuteParsed,
            isPm = hour24 >= 12,
            timeDisplay = timeDisplay,
            triggerEpochMs = calendar.timeInMillis,
            label = "Alarm $timeDisplay"
        )

        return setExactAlarm(stored)
    }

    fun setExactAlarm(alarm: StoredAlarm): String {
        if (alarmManager == null) {
            return "Alarm service is not available on this device."
        }

        return try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_ALARM_TRIGGER
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                putExtra(AlarmReceiver.EXTRA_TIME_DISPLAY, alarm.timeDisplay)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, alarm.id, intent, flags)

            // Android 12+ check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    // Fallback to allowWhileIdle
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerEpochMs, pendingIntent)
                } else {
                    val showIntent = PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java).apply { setFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                        flags
                    )
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(alarm.triggerEpochMs, showIntent)
                    alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply { setFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                    flags
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(alarm.triggerEpochMs, showIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarm.triggerEpochMs, pendingIntent)
            }

            saveAlarm(alarm)
            "Alarm set for ${alarm.timeDisplay}."
        } catch (e: Exception) {
            "Could not schedule alarm: ${e.message}"
        }
    }

    fun cancelAlarmFromVoice(input: String): String {
        val alarms = getSavedAlarms()
        if (alarms.isEmpty()) {
            return "No active alarms to cancel."
        }

        // Check if user specified a time e.g. "cancel my 5 AM alarm"
        for (alarm in alarms) {
            val keyWords = listOf(
                alarm.timeDisplay.lowercase(),
                "${alarm.hour}",
                "${alarm.timeDisplay.replace(" ", "").lowercase()}"
            )
            for (kw in keyWords) {
                if (input.contains(kw)) {
                    cancelAlarm(alarm.id)
                    return "Alarm for ${alarm.timeDisplay} cancelled."
                }
            }
        }

        // If only 1 alarm exists, cancel it
        if (alarms.size == 1) {
            val alarm = alarms.first()
            cancelAlarm(alarm.id)
            return "Alarm for ${alarm.timeDisplay} cancelled."
        }

        return "You have multiple alarms: ${alarms.joinToString(", ") { it.timeDisplay }}. Please specify which one to cancel, for example 'Cancel my 5 AM alarm'."
    }

    fun cancelAlarm(alarmId: Int) {
        try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_ALARM_TRIGGER
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, alarmId, intent, flags)
            alarmManager?.cancel(pendingIntent)
            removeAlarm(alarmId)
        } catch (_: Exception) {}
    }

    fun listAlarms(): String {
        val alarms = getSavedAlarms()
        if (alarms.isEmpty()) {
            return "You have no active alarms set."
        }

        val count = alarms.size
        val listStr = alarms.joinToString(", ") { it.timeDisplay }
        return if (count == 1) {
            "You have 1 active alarm set for $listStr."
        } else {
            "You have $count active alarms set for: $listStr."
        }
    }

    fun rescheduleAllOnBoot() {
        val alarms = getSavedAlarms()
        val now = System.currentTimeMillis()
        for (alarm in alarms) {
            var triggerTime = alarm.triggerEpochMs
            if (triggerTime <= now) {
                // Advance by 1 day
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarm.hour)
                    set(Calendar.MINUTE, alarm.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                triggerTime = cal.timeInMillis
            }
            setExactAlarm(alarm.copy(triggerEpochMs = triggerTime))
        }
    }

    private fun getSavedAlarms(): List<StoredAlarm> {
        val jsonStr = prefs.getString("alarms_list", null) ?: return emptyList()
        val list = mutableListOf<StoredAlarm>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    StoredAlarm(
                        id = obj.getInt("id"),
                        hour = obj.getInt("hour"),
                        minute = obj.getInt("minute"),
                        isPm = obj.getBoolean("isPm"),
                        timeDisplay = obj.getString("timeDisplay"),
                        triggerEpochMs = obj.getLong("triggerEpochMs"),
                        label = obj.optString("label", "Alarm")
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedBy { it.triggerEpochMs }
    }

    private fun saveAlarm(alarm: StoredAlarm) {
        val current = getSavedAlarms().filter { it.id != alarm.id }.toMutableList()
        current.add(alarm)
        saveList(current)
    }

    private fun removeAlarm(alarmId: Int) {
        val current = getSavedAlarms().filter { it.id != alarmId }
        saveList(current)
    }

    private fun saveList(alarms: List<StoredAlarm>) {
        val array = JSONArray()
        for (a in alarms) {
            val obj = JSONObject().apply {
                put("id", a.id)
                put("hour", a.hour)
                put("minute", a.minute)
                put("isPm", a.isPm)
                put("timeDisplay", a.timeDisplay)
                put("triggerEpochMs", a.triggerEpochMs)
                put("label", a.label)
            }
            array.put(obj)
        }
        prefs.edit().putString("alarms_list", array.toString()).apply()
    }
}
