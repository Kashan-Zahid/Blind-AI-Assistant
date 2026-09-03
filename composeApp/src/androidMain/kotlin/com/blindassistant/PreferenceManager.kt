package com.blindassistant

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages local user preferences on the Android device.
 * Cloud AI configuration is managed directly via GeminiConfig.
 */
class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("blind_assistant_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SPEECH_RATE = "pref_speech_rate"
        private const val KEY_LANGUAGE = "pref_language"
        private const val KEY_WAKE_WORD = "pref_wake_word"
    }

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "english") ?: "english"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.trim().lowercase()).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, "hey assistant") ?: "hey assistant"
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim().lowercase()).apply()
}
