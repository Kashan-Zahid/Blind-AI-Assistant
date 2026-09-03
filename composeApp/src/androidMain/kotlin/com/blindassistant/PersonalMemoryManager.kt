package com.blindassistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent personal memory for the blind user.
 * Stores: trusted contacts, frequent places, preferences, wake word.
 * All data stored locally — zero cloud dependency.
 */
class PersonalMemoryManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("blind_assistant_memory", Context.MODE_PRIVATE)

    // ----------------------------------------------------------------
    // CONTACTS
    // ----------------------------------------------------------------

    fun rememberContact(nickname: String, phoneNumber: String, relation: String? = null): String {
        val cleaned = phoneNumber.replace("[^0-9+]".toRegex(), "")
        if (cleaned.isBlank()) return "Please provide a valid phone number."
        val contacts = getContactsJson()
        val entry = JSONObject().apply {
            put("nickname", nickname.trim().lowercase())
            put("phone", cleaned)
            put("relation", relation?.trim() ?: "")
        }
        // Remove existing entry with same nickname
        val updated = JSONArray()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            if (c.getString("nickname") != nickname.trim().lowercase()) {
                updated.put(c)
            }
        }
        updated.put(entry)
        prefs.edit().putString("contacts", updated.toString()).apply()
        val rel = if (!relation.isNullOrBlank()) " ($relation)" else ""
        return "Got it. I will remember $nickname$rel at $cleaned."
    }

    fun lookupContact(nickname: String): String? {
        val contacts = getContactsJson()
        val query = nickname.trim().lowercase()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            if (c.getString("nickname").contains(query) || query.contains(c.getString("nickname"))) {
                return c.getString("phone")
            }
        }
        return null
    }

    fun lookupContactName(nickname: String): String? {
        val contacts = getContactsJson()
        val query = nickname.trim().lowercase()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            if (c.getString("nickname").contains(query)) {
                val rel = c.optString("relation", "")
                return if (rel.isNotBlank()) "${c.getString("nickname")} (${rel})" else c.getString("nickname")
            }
        }
        return null
    }

    fun forgetContact(nickname: String): String {
        val contacts = getContactsJson()
        val updated = JSONArray()
        var removed = false
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            if (c.getString("nickname") == nickname.trim().lowercase()) {
                removed = true
            } else {
                updated.put(c)
            }
        }
        prefs.edit().putString("contacts", updated.toString()).apply()
        return if (removed) "Contact $nickname removed from memory." else "I don't have $nickname in memory."
    }

    fun listContacts(): String {
        val contacts = getContactsJson()
        if (contacts.length() == 0) return "No contacts saved in memory yet. Say 'Remember Ali's number is 0300...'"
        val names = mutableListOf<String>()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            val rel = c.optString("relation", "")
            val label = if (rel.isNotBlank()) "${c.getString("nickname")} ($rel)" else c.getString("nickname")
            names.add(label)
        }
        return "I remember ${names.size} contact${if (names.size != 1) "s" else ""}: ${names.joinToString(", ")}."
    }

    // ----------------------------------------------------------------
    // PLACES
    // ----------------------------------------------------------------

    fun rememberPlace(name: String, description: String): String {
        val places = getPlacesJson()
        val entry = JSONObject().apply {
            put("name", name.trim().lowercase())
            put("description", description.trim())
        }
        val updated = JSONArray()
        for (i in 0 until places.length()) {
            val p = places.getJSONObject(i)
            if (p.getString("name") != name.trim().lowercase()) updated.put(p)
        }
        updated.put(entry)
        prefs.edit().putString("places", updated.toString()).apply()
        return "Saved. I'll remember that your $name is at $description."
    }

    fun lookupPlace(name: String): String? {
        val places = getPlacesJson()
        val query = name.trim().lowercase()
        for (i in 0 until places.length()) {
            val p = places.getJSONObject(i)
            if (p.getString("name").contains(query) || query.contains(p.getString("name"))) {
                return p.getString("description")
            }
        }
        return null
    }

    fun listPlaces(): String {
        val places = getPlacesJson()
        if (places.length() == 0) return "No places saved. Say 'My office is at DHA Phase 5' to save a place."
        val names = (0 until places.length()).map { places.getJSONObject(it).getString("name") }
        return "Saved places: ${names.joinToString(", ")}."
    }

    // ----------------------------------------------------------------
    // PREFERENCES
    // ----------------------------------------------------------------

    fun setPreference(key: String, value: String): String {
        prefs.edit().putString("pref_$key", value).apply()
        return "Preference saved: $key set to $value."
    }

    fun getPreference(key: String, default: String = ""): String {
        return prefs.getString("pref_$key", default) ?: default
    }

    fun getWakeWord(): String {
        return prefs.getString("pref_wake_word", "hey assistant") ?: "hey assistant"
    }

    fun setWakeWord(word: String): String {
        prefs.edit().putString("pref_wake_word", word.trim().lowercase()).apply()
        return "Wake word preference saved as '${word.trim()}'."
    }

    fun getSpeechRate(): Float {
        return prefs.getFloat("pref_speech_rate", 0.95f)
    }

    fun setSpeechRate(rate: Float): String {
        prefs.edit().putFloat("pref_speech_rate", rate.coerceIn(0.5f, 2.0f)).apply()
        AndroidVoiceService.activeInstance?.updateSpeechRate(rate)
        return when {
            rate < 0.8f -> "Speech slowed down."
            rate > 1.1f -> "Speech speed increased."
            else -> "Speech speed set to normal."
        }
    }

    fun isAlwaysLoudspeaker(): Boolean {
        return prefs.getBoolean("pref_always_loudspeaker", false)
    }

    fun setAlwaysLoudspeaker(enabled: Boolean): String {
        prefs.edit().putBoolean("pref_always_loudspeaker", enabled).apply()
        return if (enabled) "Loudspeaker will always be used for calls." else "Loudspeaker preference cleared."
    }

    fun getPreferredLanguage(): String {
        return prefs.getString("pref_language", "english") ?: "english"
    }

    fun setPreferredLanguage(lang: String): String {
        prefs.edit().putString("pref_language", lang.trim().lowercase()).apply()
        return "Default language set to $lang."
    }

    // ----------------------------------------------------------------
    // RECALL (general)
    // ----------------------------------------------------------------

    fun recall(query: String): String {
        val q = query.trim().lowercase()

        // Check contacts
        val contactPhone = lookupContact(q)
        if (contactPhone != null) {
            return "I remember $q's number as $contactPhone."
        }

        // Check places
        val place = lookupPlace(q)
        if (place != null) {
            return "Your $q is at $place."
        }

        // Check preferences
        val prefKeys = listOf("wake word", "language", "speech rate", "speed")
        for (k in prefKeys) {
            if (q.contains(k)) {
                return when {
                    q.contains("wake word") -> "Your wake word is '${getWakeWord()}'."
                    q.contains("language") -> "Your preferred language is ${getPreferredLanguage()}."
                    q.contains("speed") || q.contains("rate") -> "Speech rate is ${getSpeechRate()}."
                    else -> "No saved preference found for $q."
                }
            }
        }

        return "I don't have anything saved about '$query'. You can say 'Remember Ali's number is...' or 'My office is at...' to save."
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------
    private fun getContactsJson(): JSONArray {
        val raw = prefs.getString("contacts", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun getPlacesJson(): JSONArray {
        val raw = prefs.getString("places", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }
}
