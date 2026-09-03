package com.blindassistant

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs

/**
 * Detects device hardware capabilities and sets quality profiles
 * to ensure smooth operation on low-end Android 10+ devices.
 */
class DeviceCapabilityChecker(private val context: Context) {

    enum class QualityProfile {
        HIGH,   // ≥ 4GB RAM, recent CPU
        MEDIUM, // 2–4GB RAM
        LOW     // < 2GB RAM, older device
    }

    val profile: QualityProfile by lazy { detectProfile() }

    private fun detectProfile(): QualityProfile {
        val ramMb = getTotalRamMb()
        return when {
            ramMb >= 4000 -> QualityProfile.HIGH
            ramMb >= 2000 -> QualityProfile.MEDIUM
            else -> QualityProfile.LOW
        }
    }

    private fun getTotalRamMb(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(info)
            (info.totalMem / 1024 / 1024)
        } catch (_: Exception) {
            2000L // assume medium
        }
    }

    fun getBatteryPercent(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        } catch (_: Exception) {
            100
        }
    }

    fun isLowBattery(): Boolean = getBatteryPercent() < 15

    fun isCriticalBattery(): Boolean = getBatteryPercent() < 5

    fun getRecommendedCameraWidth(): Int = when (profile) {
        QualityProfile.HIGH -> 1280
        QualityProfile.MEDIUM -> 640
        QualityProfile.LOW -> 320
    }

    fun getRecommendedCameraHeight(): Int = when (profile) {
        QualityProfile.HIGH -> 720
        QualityProfile.MEDIUM -> 480
        QualityProfile.LOW -> 240
    }

    fun getMaxSpeechTimeoutMs(): Int = when (profile) {
        QualityProfile.HIGH -> 7000
        QualityProfile.MEDIUM -> 6000
        QualityProfile.LOW -> 5000
    }

    fun getContinuousVisionIntervalMs(): Long = when {
        isLowBattery() -> 3000L  // slow down on low battery
        profile == QualityProfile.LOW -> 2000L
        profile == QualityProfile.MEDIUM -> 1500L
        else -> 1000L
    }

    fun getFreeStorageMb(): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            (stat.availableBytes / 1024 / 1024)
        } catch (_: Exception) {
            500L
        }
    }

    fun getSummary(): String {
        val ram = getTotalRamMb()
        val battery = getBatteryPercent()
        val storage = getFreeStorageMb()
        val cpu = Runtime.getRuntime().availableProcessors()
        return "Device profile: ${profile.name}. RAM: ${ram}MB, CPU cores: $cpu, Battery: $battery%, Free storage: ${storage}MB, Android ${Build.VERSION.RELEASE}."
    }
}
