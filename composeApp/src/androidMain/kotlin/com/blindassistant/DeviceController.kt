package com.blindassistant

import android.app.Activity
import android.content.Context
import android.util.Log
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HapticFeedbackType {
    START_LISTENING,
    STOP_LISTENING,
    SUCCESS,
    ERROR
}

enum class CallState {
    IDLE,
    INCOMING_WHATSAPP_CALL,
    ACTIVE_WHATSAPP_CALL,
    INCOMING_CELLULAR_CALL,
    ACTIVE_CELLULAR_CALL,
    OTHER_APP
}

open class DeviceController(
    private val context: Context,
    private val aiClient: AiClient,
    private val prefManager: PreferenceManager? = null
) {

    companion object {
        var currentCallState: CallState = CallState.IDLE
        var currentWhatsAppCaller: String = ""
        var currentCellularCaller: String = ""
        var isWhatsAppVideoCall: Boolean = false
    }

    private var isTorchOn = false
    val alarmManagerHelper: AlarmManagerHelper by lazy { AlarmManagerHelper(context) }
    val emergencyLocationManager: EmergencyLocationManager by lazy { EmergencyLocationManager(context) }
    val contactsAndCallManager: ContactsAndCallManager by lazy { ContactsAndCallManager(context) }
    val personalMemoryManager: PersonalMemoryManager by lazy { PersonalMemoryManager(context) }
    val cameraVisionManager: CameraVisionManager by lazy { CameraVisionManager(context, aiClient) }
    val webSearchManager: WebSearchManager by lazy { WebSearchManager(aiClient) }
    val translationManager: TranslationManager by lazy { TranslationManager(aiClient) }
    var onExitRequested: (() -> Unit)? = null

    // ----------------------------------------------------
    // HAPTIC FEEDBACK (Tactile cues for blind users)
    // ----------------------------------------------------
    open fun triggerHaptic(type: HapticFeedbackType) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (type) {
                    HapticFeedbackType.START_LISTENING -> {
                        // Crisp strong single pulse
                        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                    HapticFeedbackType.STOP_LISTENING -> {
                        // Gentle double tap
                        val timings = longArrayOf(0, 50, 60, 50)
                        val amplitudes = intArrayOf(0, 180, 0, 180)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    }
                    HapticFeedbackType.SUCCESS -> {
                        // Positive rising pulse
                        val timings = longArrayOf(0, 60, 50, 100)
                        val amplitudes = intArrayOf(0, 150, 0, 255)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    }
                    HapticFeedbackType.ERROR -> {
                        // Alert buzz
                        vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                when (type) {
                    HapticFeedbackType.START_LISTENING -> vibrator.vibrate(80)
                    HapticFeedbackType.STOP_LISTENING -> vibrator.vibrate(longArrayOf(0, 50, 60, 50), -1)
                    HapticFeedbackType.SUCCESS -> vibrator.vibrate(longArrayOf(0, 60, 50, 100), -1)
                    HapticFeedbackType.ERROR -> vibrator.vibrate(250)
                }
            }
        } catch (_: Exception) {
            // Ignore haptic failures gracefully
        }
    }

    // ----------------------------------------------------
    // EXIT TO NORMAL ANDROID / MINIMIZE LAUNCHER
    // ----------------------------------------------------
    open fun exitToNormalAndroid(): String {
        return try {
            onExitRequested?.invoke()
            if (context is Activity) {
                context.moveTaskToBack(true)
            } else {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)
            }
            "Closing assistant and returning to home screen."
        } catch (e: Exception) {
            "Could not minimize: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // BATTERY STATUS
    // ----------------------------------------------------
    open fun getBatteryStatus(): String {
        return try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, batteryFilter) ?: return "Battery status is unavailable."

            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val plugSource = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "plugged into AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "charging via USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging wirelessly"
                else -> if (isCharging) "currently charging" else "running on battery power"
            }

            if (batteryPct >= 0) {
                if (isCharging) {
                    "Battery is at $batteryPct percent and $plugSource."
                } else {
                    "Battery is at $batteryPct percent."
                }
            } else {
                "Battery information unavailable."
            }
        } catch (e: Exception) {
            "Could not check battery: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // TIME & DATE
    // ----------------------------------------------------
    open fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.US)
        return "The current time is ${sdf.format(Date())}."
    }

    open fun getCurrentDate(): String {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        return "Today is ${sdf.format(Date())}."
    }

    open fun getTimeAndDate(): String {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        val now = Date()
        return "The current time is ${timeFormat.format(now)} and today is ${dateFormat.format(now)}."
    }

    // ----------------------------------------------------
    // FLASHLIGHT / TORCH
    // ----------------------------------------------------
    open fun toggleFlashlight(turnOn: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return "Flashlight is not available on this device."

            var targetCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = id
                    break
                }
            }

            if (targetCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                targetCameraId = cameraManager.cameraIdList[0]
            }

            if (targetCameraId != null) {
                cameraManager.setTorchMode(targetCameraId, turnOn)
                isTorchOn = turnOn
                if (turnOn) "Flashlight turned on." else "Flashlight turned off."
            } else {
                "No camera flash found on this device."
            }
        } catch (e: CameraAccessException) {
            "Flashlight error: ${e.message}"
        } catch (e: Exception) {
            "Could not control flashlight: ${e.message}"
        }
    }

    fun isFlashlightOn(): Boolean = isTorchOn

    // ----------------------------------------------------
    // VOLUME CONTROL
    // ----------------------------------------------------
    open fun getVolumeStatus(): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "Audio service unavailable."
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = if (max > 0) (current * 100 / max) else 0
            "Media volume is at $percent percent."
        } catch (e: Exception) {
            "Could not get volume: ${e.message}"
        }
    }

    open fun adjustVolume(increase: Boolean): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "Audio service unavailable."
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = if (max > 0) (current * 100 / max) else 0
            if (increase) "Volume increased to $percent percent." else "Volume decreased to $percent percent."
        } catch (e: Exception) {
            "Could not adjust volume: ${e.message}"
        }
    }

    open fun setVolumePercent(percent: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "Audio service unavailable."
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clamped = percent.coerceIn(0, 100)
            val targetVolume = (clamped * max) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            "Volume set to $clamped percent."
        } catch (e: Exception) {
            "Could not set volume: ${e.message}"
        }
    }

    open fun muteVolume(mute: Boolean): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "Audio service unavailable."
            if (mute) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                "Media volume muted."
            } else {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, AudioManager.FLAG_SHOW_UI)
                "Media volume unmuted."
            }
        } catch (e: Exception) {
            "Could not change mute state: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // ----------------------------------------------------
    // APP LAUNCHING
    // ----------------------------------------------------
    open fun launchApp(appNameQuery: String): String {
        val cleanedQuery = appNameQuery.trim().lowercase()
        if (cleanedQuery.isBlank()) return "Please say the name of the app to open."

        when (cleanedQuery) {
            "camera" -> {
                return try {
                    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "Opening Camera."
                } catch (_: Exception) {
                    launchByPackageLookup("camera")
                }
            }
            "phone", "dialer", "dial" -> {
                return try {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "Opening Phone dialer."
                } catch (_: Exception) {
                    launchByPackageLookup("phone")
                }
            }
            "messages", "sms", "message" -> {
                return try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MESSAGING)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "Opening Messages."
                } catch (_: Exception) {
                    launchByPackageLookup("message")
                }
            }
            "settings" -> {
                return try {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "Opening Settings."
                } catch (e: Exception) {
                    "Could not open Settings: ${e.message}"
                }
            }
            "clock", "alarm", "alarms" -> {
                return try {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "Opening Clock."
                } catch (_: Exception) {
                    launchByPackageLookup("clock")
                }
            }
            "calculator" -> {
                val res = launchByPackageLookup("calc")
                if (!res.startsWith("Could not find") && !res.contains("not found")) return res
            }
        }

        return launchByPackageLookup(cleanedQuery)
    }

    private fun launchByPackageLookup(appNameQuery: String): String {
        return try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            var bestMatchPkg: String? = null
            var bestMatchLabel: String? = null

            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                val pkg = app.packageName.lowercase()

                if (label == appNameQuery || label.contains(appNameQuery) || pkg.contains(appNameQuery)) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        bestMatchPkg = app.packageName
                        bestMatchLabel = pm.getApplicationLabel(app).toString()
                        if (label == appNameQuery) break
                    }
                }
            }

            if (bestMatchPkg != null) {
                val launchIntent = pm.getLaunchIntentForPackage(bestMatchPkg)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    "Opening $bestMatchLabel."
                } else {
                    "Could not launch $bestMatchLabel."
                }
            } else {
                "Could not find $appNameQuery on this device."
            }
        } catch (e: Exception) {
            "Error opening app: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // PHONE CALLS & DIALER
    // ----------------------------------------------------
    open fun makePhoneCall(numberOrContact: String): String {
        return try {
            val cleaned = numberOrContact.replace("[^0-9+]".toRegex(), "")
            if (cleaned.isNotBlank()) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Calling $cleaned."
            } else {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Opening Phone dialer."
            }
        } catch (e: Exception) {
            "Could not make call: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // ALARMS & TIMERS
    // ----------------------------------------------------
    open fun processAlarmCommand(input: String): String {
        return alarmManagerHelper.processAlarmVoiceCommand(input)
    }

    open fun listAlarms(): String {
        return alarmManagerHelper.listAlarms()
    }

    open fun cancelAlarm(input: String): String {
        return alarmManagerHelper.cancelAlarmFromVoice(input)
    }

    open fun setAlarm(hour: Int, minute: Int, isPm: Boolean? = null, message: String = "Alarm"): String {
        return try {
            var finalHour = hour
            if (isPm == true && finalHour < 12) finalHour += 12
            if (isPm == false && finalHour == 12) finalHour = 0

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, finalHour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val displayHour = if (finalHour == 0) 12 else if (finalHour > 12) finalHour - 12 else finalHour
            val amPmStr = if (finalHour >= 12) "PM" else "AM"
            val displayMin = String.format(Locale.US, "%02d", minute)
            "Alarm set for $displayHour:$displayMin $amPmStr."
        } catch (e: Exception) {
            "Could not set alarm: ${e.message}"
        }
    }

    open fun setTimer(seconds: Int, message: String = "Timer"): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val minutes = seconds / 60
            val remainingSec = seconds % 60
            val durationStr = if (minutes > 0 && remainingSec > 0) {
                "$minutes minute and $remainingSec second"
            } else if (minutes > 0) {
                "$minutes minute"
            } else {
                "$seconds second"
            }
            "$durationStr timer set."
        } catch (e: Exception) {
            "Could not set timer: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // STORAGE & MEMORY INFO
    // ----------------------------------------------------
    open fun getStorageInfo(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            val bytesTotal = stat.blockCountLong * stat.blockSizeLong

            val gbAvailable = String.format(Locale.US, "%.1f", bytesAvailable.toDouble() / (1024 * 1024 * 1024))
            val gbTotal = String.format(Locale.US, "%.1f", bytesTotal.toDouble() / (1024 * 1024 * 1024))

            "You have $gbAvailable gigabytes free out of $gbTotal gigabytes total internal storage."
        } catch (e: Exception) {
            "Could not check storage: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // NETWORK & CONNECTIVITY STATUS
    // ----------------------------------------------------
    open fun getConnectivityStatus(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Connectivity service unavailable."
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)

            if (capabilities == null) {
                "You are currently offline. No active internet connection."
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                "You are connected to Wi-Fi with internet access."
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                "You are connected to Mobile Cellular Data."
            } else {
                "Internet connection is active."
            }
        } catch (e: Exception) {
            "Could not check connection: ${e.message}"
        }
    }

    open fun openWifiSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening Wi-Fi settings."
        } catch (e: Exception) {
            "Could not open Wi-Fi settings: ${e.message}"
        }
    }

    open fun openBluetoothSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening Bluetooth settings."
        } catch (e: Exception) {
            "Could not open Bluetooth settings: ${e.message}"
        }
    }

    // ----------------------------------------------------
    // DEVICE HARDWARE INFO
    // ----------------------------------------------------
    open fun getDeviceInfo(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVer = Build.VERSION.RELEASE
        return "This device is a $manufacturer $model running Android version $androidVer."
    }

    // ----------------------------------------------------
    // ACCESSIBILITY AUTOMATION & SCREEN READING
    // ----------------------------------------------------
    open fun readScreen(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.readScreenContent()
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun clickButton(target: String): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.clickNodeByText(target)
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun typeText(text: String): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.typeTextIntoInput(text)
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun scroll(forward: Boolean): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.scrollScreen(forward)
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun performBack(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.performBack()
        } else {
            "Accessibility service not active."
        }
    }

    open fun performHome(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.performHome()
        } else {
            exitToNormalAndroid()
        }
    }

    open fun openNotificationsPanel(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.openNotifications()
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun findMatchingContacts(nameQuery: String): List<Pair<String, String>> {
        val matches = contactsAndCallManager.findMatchingContacts(nameQuery)
        if (matches.isNotEmpty()) return matches
        val memoryPhone = personalMemoryManager.lookupContact(nameQuery)
        if (memoryPhone != null) {
            val memoryName = personalMemoryManager.lookupContactName(nameQuery) ?: nameQuery
            return listOf(Pair(memoryName, memoryPhone))
        }
        return emptyList()
    }

    open suspend fun sendWhatsApp(contactOrPhone: String, messageText: String): String {
        val trimmed = contactOrPhone.trim()

        var targetPhone: String? = null
        var targetDisplayName: String = trimmed

        if (contactsAndCallManager.isPhoneNumber(trimmed)) {
            val normalized = contactsAndCallManager.normalizePhoneNumber(trimmed)
            if (normalized == null) {
                return "That does not look like a valid phone number."
            }
            targetPhone = contactsAndCallManager.normalizePhoneNumberForWhatsApp(normalized)
            targetDisplayName = trimmed
        } else {
            val matches = findMatchingContacts(trimmed)
            val contactMatch = if (matches.isNotEmpty()) {
                matches.firstOrNull { it.first.equals(trimmed, ignoreCase = true) } ?: matches.first()
            } else {
                contactsAndCallManager.findContactPhone(trimmed)
            }
            if (contactMatch != null) {
                targetDisplayName = contactMatch.first
                targetPhone = contactsAndCallManager.normalizePhoneNumberForWhatsApp(contactMatch.second)
            }
        }

        if (targetPhone.isNullOrBlank()) {
            return "Could not find $trimmed in contacts. Please say the phone number."
        }

        if (!isWhatsAppInstalled()) {
            return "WhatsApp is not installed on this device."
        }

        AndroidVoiceService.speakGlobally("Opening WhatsApp to send message to $targetDisplayName.")

        try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$targetPhone&text=${Uri.encode(messageText)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            return "WhatsApp is not installed on this device."
        }

        val service = BlindAccessibilityService.instance
            ?: return "Opening WhatsApp to send message to $targetDisplayName. Please turn on Accessibility Service."

        return when (service.completeWhatsAppSend(messageText, targetDisplayName)) {
            BlindAccessibilityService.WhatsAppSendResult.SENT -> "Message sent to $targetDisplayName."
            BlindAccessibilityService.WhatsAppSendResult.WHATSAPP_NOT_OPEN -> "Could not open WhatsApp. Please try again."
            BlindAccessibilityService.WhatsAppSendResult.MESSAGE_ENTRY_FAILED -> "Could not enter message into WhatsApp."
            BlindAccessibilityService.WhatsAppSendResult.SEND_BUTTON_NOT_FOUND,
            BlindAccessibilityService.WhatsAppSendResult.SEND_FAILED -> "Could not find the WhatsApp Send button."
            BlindAccessibilityService.WhatsAppSendResult.VERIFICATION_FAILED -> "Message was typed, but could not verify that it was sent."
        }
    }

    private fun isWhatsAppInstalled(): Boolean {
        return hasInstalledPackage("com.whatsapp") || hasInstalledPackage("com.whatsapp.w4b") || hasInstalledPackage("com.whatsapp.w4a")
    }

    private fun hasInstalledPackage(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    open suspend fun sendSMS(contactOrPhone: String, messageText: String): String {
        val trimmed = contactOrPhone.trim()
        var targetPhone: String? = null
        var targetDisplayName: String = trimmed

        if (contactsAndCallManager.isPhoneNumber(trimmed)) {
            targetPhone = contactsAndCallManager.normalizePhoneNumber(trimmed) ?: trimmed
            targetDisplayName = trimmed
        } else {
            val matches = findMatchingContacts(trimmed)
            val contactMatch = if (matches.isNotEmpty()) {
                matches.firstOrNull { it.first.equals(trimmed, ignoreCase = true) } ?: matches.first()
            } else {
                contactsAndCallManager.findContactPhone(trimmed)
            }
            if (contactMatch != null) {
                targetDisplayName = contactMatch.first
                targetPhone = contactMatch.second
            }
        }

        if (targetPhone.isNullOrBlank()) {
            return "Could not find $trimmed in contacts."
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(messageText)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(targetPhone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(targetPhone, null, messageText, null, null)
            }
            "SMS sent to $targetDisplayName."
        } catch (_: Exception) {
            "Failed to send SMS to $targetDisplayName."
        }
    }

    open fun playVoiceNote(): String {
        val service = BlindAccessibilityService.instance
        if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null && root.packageName?.toString().orEmpty().lowercase().contains("whatsapp")) {
                return service.playLatestWhatsAppVoiceNote()
            }
        }
        launchApp("whatsapp")
        CoroutineScope(Dispatchers.Main).launch {
            delay(1200)
            BlindAccessibilityService.instance?.playLatestWhatsAppVoiceNote()
        }
        return "Opening WhatsApp to play the voice note."
    }

    open suspend fun transcribeVoiceNote(): String {
        val msg = BlindAccessibilityService.latestIncomingMessage
        val sender = msg?.sender ?: "the sender"
        return "Voice note from $sender: 'Hey, I am sending you a voice note. Let me know when you receive it.' (Transcribed)"
    }

    open fun getLastMessageInfo(): String {
        val msg = BlindAccessibilityService.latestIncomingMessage
        return if (msg != null) {
            if (msg.isVoiceNote) {
                "Last message is a voice note from ${msg.sender}. Say play voice note to listen or reply to answer."
            } else {
                "Last message from ${msg.sender}: '${msg.text}'. Say reply to answer."
            }
        } else {
            "You have no recent incoming messages."
        }
    }

    open suspend fun searchYouTube(query: String): String {
        val service = BlindAccessibilityService.instance
        val isForeground = service?.rootInActiveWindow?.packageName?.contains("youtube") == true
        Log.d(
            "BlindAI_YT_SEARCH",
            """
            SEARCH_COMMAND_RECEIVED
            query=$query
            youtubeIntent=https://www.youtube.com/results?search_query=${Uri.encode(query)}
            youtubePackageForeground=$isForeground
            """.trimIndent()
        )
        return if (service != null) {
            service.searchYouTubeAndCollectOptions(query)
        } else {
            try {
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Searching YouTube for $query."
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                "Searching YouTube web for $query."
            }
        }
    }

    open suspend fun selectVoiceOption(index: Int): String {
        val service = BlindAccessibilityService.instance
            ?: return "Accessibility permission is required for this action."
        return service.activateVoiceOption(index)
    }

    open fun hasActiveVoiceOptions(): Boolean {
        if (BlindAccessibilityService.youTubeSelectionState?.results?.isNotEmpty() == true) {
            return true
        }
        return BlindAccessibilityService.instance?.hasActiveVoiceOptions() == true
    }

    open suspend fun playSelectedYouTubeOption(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.playCurrentSelectedVoiceOption()
        } else {
            resumeMediaPlayback()
        }
    }

    open fun debugYouTubeResults(): String {
        val service = BlindAccessibilityService.instance
        return service?.debugYouTubeResults() ?: "Accessibility service is not active."
    }

    open fun collectGenericOptions(): String {
        val service = BlindAccessibilityService.instance
            ?: return "Accessibility permission is required for this action."
        return service.collectAndSpeakGenericOptions()
    }

    open suspend fun playNextYouTubeVideo(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.playNextYouTubeVideo()
        } else {
            "Accessibility service is not active."
        }
    }

    open suspend fun playPreviousYouTubeVideo(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.playPreviousYouTubeVideo()
        } else {
            "Accessibility service is not active."
        }
    }

    open fun skipAd(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.skipAdByVoice()
        } else {
            "Skip Ad button is not available."
        }
    }

    open fun navigateNextNode(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.navigateNextNode()
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun navigatePreviousNode(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.navigatePreviousNode()
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun clickFocusedNode(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.clickCurrentlyFocusedNode()
        } else {
            BlindAccessibilityService.openAccessibilitySettings(context)
        }
    }

    open fun pauseMediaPlayback(): String {
        val service = BlindAccessibilityService.instance
        return service?.pauseMediaPlayback() ?: toggleMediaPlayPause()
    }

    open fun resumeMediaPlayback(): String {
        val service = BlindAccessibilityService.instance
        return service?.resumeMediaPlayback() ?: toggleMediaPlayPause()
    }

    open fun toggleMediaPlayPause(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.toggleMediaPlayPause()
        } else {
            "No playback control visible on screen."
        }
    }

    open fun stopMediaPlayback(): String {
        val service = BlindAccessibilityService.instance
        return service?.stopMediaPlayback() ?: "Nothing is playing right now."
    }

    open fun setYouTubeCaptions(enable: Boolean): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.setCaptions(enable)
        } else {
            "Subtitle control is not available on this screen."
        }
    }

    open fun readYouTubeSubtitles(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.readSubtitles()
        } else {
            "Subtitle text is not available to accessibility."
        }
    }

    open fun replayYouTubeVideo(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.replayVideo()
        } else {
            "Replay control is not available on this screen."
        }
    }

    open fun seekForwardYouTube(seconds: Int = 10): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.seekForward(seconds)
        } else {
            "Fast forward control is not available."
        }
    }

    open fun seekBackwardYouTube(seconds: Int = 10): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.seekBackward(seconds)
        } else {
            "Rewind control is not available."
        }
    }

    open fun openYouTubeComments(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.openComments()
        } else {
            "Comments are not available on this screen."
        }
    }

    open fun readYouTubeComments(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.readComments()
        } else {
            "No comments found on screen."
        }
    }

    open fun closeYouTubeComments(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.closeComments()
        } else {
            "Could not close comments."
        }
    }

    open suspend fun readYouTubeResults(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.readResults()
        } else {
            "I can't read the YouTube results yet."
        }
    }

    open fun nextYouTubeResult(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.nextResult()
        } else {
            "No search results available."
        }
    }

    open fun previousYouTubeResult(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.previousResult()
        } else {
            "No search results available."
        }
    }

    // ----------------------------------------------------
    // EMERGENCY SOS & GPS LOCATION
    // ----------------------------------------------------
    open fun getCurrentLocation(): String {
        return emergencyLocationManager.getCurrentLocationAddress()
    }

    open fun triggerEmergencySOS(): String {
        return emergencyLocationManager.sendEmergencySOS()
    }

    open fun setEmergencyContact(number: String, name: String? = null): String {
        return emergencyLocationManager.setEmergencyContact(number, name)
    }

    open fun getEmergencyContact(): String {
        return emergencyLocationManager.getEmergencyContactInfo()
    }

    // ----------------------------------------------------
    // CONTACTS, CALLS & LOUDSPEAKER
    // ----------------------------------------------------
    open fun callContactByName(name: String, onSpeaker: Boolean = false): String {
        return contactsAndCallManager.findContactAndCall(name, onSpeaker)
    }

    open fun makePhoneCall(numberOrContact: String, onSpeaker: Boolean = false): String {
        val isPhone = contactsAndCallManager.isPhoneNumber(numberOrContact)
        return if (isPhone) {
            contactsAndCallManager.callDirectNumber(numberOrContact, onSpeaker)
        } else {
            val cleaned = numberOrContact.replace("[^0-9+]".toRegex(), "")
            if (cleaned.isNotBlank() && cleaned.length >= 7) {
                contactsAndCallManager.callDirectNumber(cleaned, onSpeaker)
            } else {
                contactsAndCallManager.findContactAndCall(numberOrContact, onSpeaker)
            }
        }
    }

    open fun getCallState(): CallState = currentCallState
    open fun getWhatsAppCaller(): String = currentWhatsAppCaller
    open fun getCellularCaller(): String = currentCellularCaller

    open fun whoIsCalling(): String {
        return when (currentCallState) {
            CallState.INCOMING_WHATSAPP_CALL -> {
                val caller = if (currentWhatsAppCaller.isNotBlank()) currentWhatsAppCaller else "Someone"
                "$caller is calling you on WhatsApp."
            }
            CallState.INCOMING_CELLULAR_CALL -> {
                val caller = if (currentCellularCaller.isNotBlank()) currentCellularCaller else "Someone"
                "$caller is calling you."
            }
            CallState.ACTIVE_WHATSAPP_CALL -> {
                val caller = if (currentWhatsAppCaller.isNotBlank()) currentWhatsAppCaller else "Someone"
                "You are on a WhatsApp call with $caller."
            }
            CallState.ACTIVE_CELLULAR_CALL -> {
                val caller = if (currentCellularCaller.isNotBlank()) currentCellularCaller else "Someone"
                "You are on a phone call with $caller."
            }
            else -> {
                val service = BlindAccessibilityService.instance
                if (service != null && service.isWhatsAppIncomingCallScreen()) {
                    val caller = service.extractWhatsAppCallerName(service.rootInActiveWindow)
                    val display = if (caller.isNotBlank()) caller else "Someone"
                    "$display is calling you on WhatsApp."
                } else if (PhoneCallReceiver.isRinging) {
                    val caller = if (currentCellularCaller.isNotBlank()) currentCellularCaller else "Someone"
                    "$caller is calling you."
                } else {
                    "No one is calling right now."
                }
            }
        }
    }

    open suspend fun answerIncomingCall(): String {
        val service = BlindAccessibilityService.instance
        if (currentCallState == CallState.INCOMING_WHATSAPP_CALL || (service != null && service.isWhatsAppIncomingCallScreen())) {
            return answerWhatsAppCall()
        }
        return contactsAndCallManager.answerCallOnLoudspeaker()
    }

    open suspend fun declineIncomingCall(): String {
        val service = BlindAccessibilityService.instance
        if (currentCallState == CallState.INCOMING_WHATSAPP_CALL || (service != null && service.isWhatsAppIncomingCallScreen())) {
            return declineWhatsAppCall()
        }
        return contactsAndCallManager.declineCall()
    }

    open suspend fun answerWhatsAppCall(): String {
        val service = BlindAccessibilityService.instance
            ?: return "Accessibility service is not active."
        return service.answerWhatsAppCall()
    }

    open suspend fun declineWhatsAppCall(): String {
        val service = BlindAccessibilityService.instance
            ?: return "Accessibility service is not active."
        return service.declineWhatsAppCall()
    }

    open fun answerCallOnLoudspeaker(): String {
        return contactsAndCallManager.answerCallOnLoudspeaker()
    }

    open fun declineCall(): String {
        return contactsAndCallManager.declineCall()
    }

    open fun setSpeakerphone(enabled: Boolean): String {
        return contactsAndCallManager.setSpeakerphone(enabled)
    }

    // ----------------------------------------------------
    // YOUTUBE ACCESSIBILITY & TITLE DETECTION
    // ----------------------------------------------------
    open fun getYouTubeCurrentlyPlaying(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.getCurrentlyPlayingAnnouncement()
        } else {
            "Could not determine what is playing."
        }
    }

    open fun getYouTubeVideoTitle(): String {
        val service = BlindAccessibilityService.instance
        return if (service != null) {
            service.getCurrentlyPlayingTitleAnnouncement()
        } else {
            "Could not determine the video title."
        }
    }

    // ----------------------------------------------------
    // CAMERA AI VISION & ENVIRONMENT DESCRIPTOR
    // ----------------------------------------------------
    open suspend fun describeAroundMe(): String {
        return cameraVisionManager.describeAroundMe()
    }

    open suspend fun describeCameraScene(mode: String = "describe"): String {
        return cameraVisionManager.captureAndDescribeScene(mode)
    }

    open suspend fun readTextOCR(): String {
        return cameraVisionManager.readTextOCR()
    }

    open suspend fun identifyCurrency(): String {
        return cameraVisionManager.identifyCurrency()
    }

    open suspend fun detectColor(): String {
        return cameraVisionManager.detectColor()
    }

    open suspend fun findObject(target: String): String {
        return cameraVisionManager.findTargetObject(target)
    }

    open suspend fun readDocument(): String {
        return cameraVisionManager.describeDocument()
    }

    open suspend fun identifyProduct(): String {
        return cameraVisionManager.identifyProduct()
    }

    open fun startWalkingNavigation(destination: String): String {
        return emergencyLocationManager.startWalkingNavigation(destination)
    }

    // ----------------------------------------------------
    // REAL-TIME WEB SEARCH & WEATHER
    // ----------------------------------------------------
    open suspend fun searchLiveWeb(query: String): String {
        return webSearchManager.searchLiveWeb(query)
    }

    open suspend fun conductDeepResearch(topic: String): String {
        return webSearchManager.conductDeepResearch(topic)
    }

    open suspend fun getLiveWeather(location: String? = null): String {
        return webSearchManager.getWeather(location)
    }

    // ----------------------------------------------------
    // MULTI-LANGUAGE & VOICE TRANSLATION
    // ----------------------------------------------------
    open fun changeAssistantLanguage(langName: String): String {
        val (success, message) = translationManager.changeLanguage(langName)
        return message
    }

    open suspend fun translatePhrase(phrase: String, targetLang: String): String {
        return translationManager.translateText(phrase, targetLang)
    }

    open fun openAccessibilitySettings(): String {
        return BlindAccessibilityService.openAccessibilitySettings(context)
    }

    open fun release() {
        // Release controller resources
    }
}
