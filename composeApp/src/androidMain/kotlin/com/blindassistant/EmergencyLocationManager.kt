package com.blindassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import java.util.Locale

class EmergencyLocationManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("blind_assistant_emergency_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_EMERGENCY_PHONE = "emergency_phone_number"
        private const val KEY_EMERGENCY_NAME = "emergency_contact_name"
        private const val DEFAULT_EMERGENCY_NUMBER = "112"
        private val PLUS_CODE_REGEX = Regex("\\b[2-9CFGHJMPQRVWX]{2,8}\\+[2-9CFGHJMPQRVWX]{2,4}\\b", RegexOption.IGNORE_CASE)
    }

    fun setEmergencyContact(number: String, name: String? = null): String {
        val cleaned = number.replace("[^0-9+]".toRegex(), "")
        if (cleaned.isBlank()) {
            return "Please provide a valid phone number for the emergency contact."
        }
        val contactName = name?.trim() ?: "Emergency Contact"
        prefs.edit()
            .putString(KEY_EMERGENCY_PHONE, cleaned)
            .putString(KEY_EMERGENCY_NAME, contactName)
            .apply()
        return "Emergency contact $contactName set to $cleaned."
    }

    fun getEmergencyContactInfo(): String {
        val phone = prefs.getString(KEY_EMERGENCY_PHONE, null)
        val name = prefs.getString(KEY_EMERGENCY_NAME, null)
        return if (!phone.isNullOrBlank()) {
            "Your emergency contact is ${name ?: "Emergency Contact"} at $phone."
        } else {
            "No custom emergency contact is set. Default emergency number is $DEFAULT_EMERGENCY_NUMBER. Say 'set emergency contact [number]' to save one."
        }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocationAddress(): String {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "Location service is not available on this device."

        try {
            val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                return "Location services are disabled. Please enable GPS in settings."
            }

            var bestLocation: Location? = null

            if (isGpsEnabled) {
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (loc != null) bestLocation = loc
            }

            if (isNetworkEnabled) {
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null && (bestLocation == null || loc.accuracy < bestLocation.accuracy)) {
                    bestLocation = loc
                }
            }

            if (bestLocation == null) {
                return "Acquiring GPS location. Please wait a moment in an open area and ask again."
            }

            return formatLocationAddress(bestLocation)
        } catch (_: SecurityException) {
            return "Location permission is required. Please grant location access."
        } catch (_: Exception) {
            return "Could not determine your current location."
        }
    }

    fun formatLocationAddress(location: Location): String {
        return try {
            val geocoder = Geocoder(context, Locale.US)
            @Suppress("DEPRECATION")
            val addresses: List<Address>? = geocoder.getFromLocation(location.latitude, location.longitude, 1)

            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val humanAddress = cleanHumanReadableAddress(addr)
                if (humanAddress.isNotBlank()) {
                    return "Your current location is $humanAddress."
                }
            }
            val latStr = String.format(Locale.US, "%.4f", location.latitude)
            val lonStr = String.format(Locale.US, "%.4f", location.longitude)
            "You are at latitude $latStr and longitude $lonStr."
        } catch (_: Exception) {
            val latStr = String.format(Locale.US, "%.4f", location.latitude)
            val lonStr = String.format(Locale.US, "%.4f", location.longitude)
            "You are at latitude $latStr and longitude $lonStr."
        }
    }

    private fun cleanHumanReadableAddress(addr: Address): String {
        // Collect meaningful location parts, stripping Plus Codes
        val street = addr.thoroughfare?.replace(PLUS_CODE_REGEX, "")?.trim()
        val subLocality = addr.subLocality?.replace(PLUS_CODE_REGEX, "")?.trim()
        val locality = addr.locality?.replace(PLUS_CODE_REGEX, "")?.trim()
            ?: addr.subAdminArea?.replace(PLUS_CODE_REGEX, "")?.trim()
        val adminArea = addr.adminArea?.replace(PLUS_CODE_REGEX, "")?.trim()
        val country = addr.countryName?.replace(PLUS_CODE_REGEX, "")?.trim()

        val meaningfulParts = mutableListOf<String>()

        if (!street.isNullOrBlank() && !street.contains("Unnamed", ignoreCase = true)) {
            meaningfulParts.add(street)
        }
        if (!subLocality.isNullOrBlank() && !meaningfulParts.contains(subLocality)) {
            meaningfulParts.add(subLocality)
        }
        if (!locality.isNullOrBlank() && !meaningfulParts.contains(locality)) {
            meaningfulParts.add(locality)
        }
        if (!country.isNullOrBlank() && !meaningfulParts.contains(country)) {
            meaningfulParts.add(country)
        }

        if (meaningfulParts.isNotEmpty()) {
            return meaningfulParts.joinToString(", ")
        }

        // Fallback to address line with Plus Code removed
        val addressLine = addr.getAddressLine(0) ?: ""
        val cleanedLine = addressLine.replace(PLUS_CODE_REGEX, "")
            .trim(',', ' ')
            .replace(Regex("\\s+,\\s+"), ", ")
            .replace(Regex(",\\s*,"), ",")

        return if (cleanedLine.isNotBlank()) cleanedLine else ""
    }

    @SuppressLint("MissingPermission")
    fun sendEmergencySOS(): String {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var lat = 0.0
        var lon = 0.0
        var addressText = "Unknown location"

        try {
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                try {
                    val geocoder = Geocoder(context, Locale.US)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val cleaned = cleanHumanReadableAddress(addresses[0])
                        addressText = if (cleaned.isNotBlank()) cleaned else "$lat, $lon"
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        val emergencyNumber = prefs.getString(KEY_EMERGENCY_PHONE, DEFAULT_EMERGENCY_NUMBER) ?: DEFAULT_EMERGENCY_NUMBER
        val emergencyName = prefs.getString(KEY_EMERGENCY_NAME, "Emergency Contact") ?: "Emergency Contact"

        val mapsUrl = if (lat != 0.0 && lon != 0.0) "https://maps.google.com/?q=$lat,$lon" else "Location unavailable"
        val messageBody = "EMERGENCY SOS! I need immediate help. My location: $addressText. Maps: $mapsUrl"

        playEmergencyAlarm()
        strobeFlashlight()

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(emergencyNumber, null, messageBody, null, null)
        } catch (_: Exception) {
            try {
                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$emergencyNumber")
                    putExtra("sms_body", messageBody)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(smsIntent)
            } catch (_: Exception) {}
        }

        val smsThread = Thread {
            repeat(3) {
                Thread.sleep(30_000)
                try {
                    val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(emergencyNumber, null, "EMERGENCY REPEAT ${it + 1}: $messageBody", null, null)
                } catch (_: Exception) {}
            }
        }
        smsThread.isDaemon = true
        smsThread.start()

        val callThread = Thread {
            AndroidVoiceService.speakGlobally("Emergency SOS triggered! Location sent to $emergencyName. Calling emergency contact in 10 seconds. Say cancel to abort.")
            Thread.sleep(10_000)
            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$emergencyNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
            } catch (_: Exception) {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
            }
        }
        callThread.isDaemon = true
        callThread.start()

        return "Emergency SOS triggered! Location sent to $emergencyName. Calling emergency contact in 10 seconds. Say cancel to abort."
    }

    private fun playEmergencyAlarm() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM),
                0
            )
            val ringtone = android.media.RingtoneManager.getRingtone(
                context,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            )
            ringtone?.play()
            Thread { Thread.sleep(30_000); ringtone?.stop() }.also { it.isDaemon = true }.start()
        } catch (_: Exception) {}
    }

    private fun strobeFlashlight() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            val strobeThread = Thread {
                repeat(20) {
                    try {
                        cameraManager.setTorchMode(cameraId, it % 2 == 0)
                        Thread.sleep(250)
                    } catch (_: Exception) {}
                }
                try { cameraManager.setTorchMode(cameraId, false) } catch (_: Exception) {}
            }
            strobeThread.isDaemon = true
            strobeThread.start()
        } catch (_: Exception) {}
    }

    /**
     * Pedestrian walking navigation with Google Maps turn-by-turn voice guidance.
     */
    fun startWalkingNavigation(destination: String): String {
        val trimmed = destination.trim()
        if (trimmed.isBlank()) {
            return "Please tell me where you would like to walk to."
        }
        return try {
            val gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(trimmed) + "&mode=w")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
                "Starting walking navigation to $trimmed with spoken directions."
            } else {
                val fallbackUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(trimmed) + "&travelmode=walking")
                val webIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                "Opening walking directions to $trimmed."
            }
        } catch (e: Exception) {
            "Unable to start navigation: ${e.message ?: "Please try again."}"
        }
    }
}
