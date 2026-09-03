package com.blindassistant

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.util.Locale

class ContactsAndCallManager(private val context: Context) {

    private val audioManager by lazy {
        try { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (_: Exception) { null }
    }
    private val telecomManager by lazy {
        try { context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager } catch (_: Exception) { null }
    }

    @SuppressLint("Range")
    fun findContactPhone(nameQuery: String): Pair<String, String>? {
        val cleanedQuery = nameQuery.trim().lowercase()
        if (cleanedQuery.isBlank()) return null

        return try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            var exactMatch: Pair<String, String>? = null
            var prefixMatch: Pair<String, String>? = null
            var partialMatch: Pair<String, String>? = null

            cursor?.use {
                while (it.moveToNext()) {
                    val displayName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                    val phoneNumber = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    val lowerName = displayName.lowercase().trim()

                    if (lowerName == cleanedQuery) {
                        exactMatch = Pair(displayName, phoneNumber)
                        break
                    } else if (lowerName.startsWith(cleanedQuery) && prefixMatch == null) {
                        prefixMatch = Pair(displayName, phoneNumber)
                    } else if (lowerName.contains(cleanedQuery) && partialMatch == null) {
                        partialMatch = Pair(displayName, phoneNumber)
                    }
                }
            }
            exactMatch ?: prefixMatch ?: partialMatch
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("Range")
    fun findMatchingContacts(nameQuery: String): List<Pair<String, String>> {
        val cleanedQuery = nameQuery.trim().lowercase()
        if (cleanedQuery.isBlank()) return emptyList()

        val directMatches = queryContacts(cleanedQuery)
        if (directMatches.isNotEmpty()) return directMatches

        val stripped = cleanRelationshipPrefixes(cleanedQuery)
        if (stripped.isNotBlank() && stripped != cleanedQuery) {
            val strippedMatches = queryContacts(stripped)
            if (strippedMatches.isNotEmpty()) return strippedMatches
        }

        return emptyList()
    }

    @SuppressLint("Range")
    private fun queryContacts(query: String): List<Pair<String, String>> {
        val cleaned = query.trim().lowercase()
        if (cleaned.isBlank()) return emptyList()

        return try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            val results = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()

            cursor?.use {
                while (it.moveToNext()) {
                    val displayName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                    val phoneNumber = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    val lowerName = displayName.lowercase().trim()

                    if (lowerName == cleaned || lowerName.startsWith(cleaned) || lowerName.contains(cleaned) || cleaned.contains(lowerName)) {
                        val key = "${displayName.lowercase()}_${phoneNumber.replace("[^0-9+]".toRegex(), "")}"
                        if (seen.add(key) && displayName.isNotBlank() && phoneNumber.isNotBlank()) {
                            // If exact match, place at top
                            if (lowerName == cleaned) {
                                results.add(0, Pair(displayName, phoneNumber))
                            } else {
                                results.add(Pair(displayName, phoneNumber))
                            }
                        }
                    }
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun cleanRelationshipPrefixes(name: String): String {
        var cleaned = name.trim()
        val prefixes = listOf(
            "my brother ", "my sister ", "my father ", "my mother ", "my mom ", "my dad ",
            "my friend ", "my son ", "my daughter ", "my wife ", "my husband ", "my boss ",
            "brother ", "sister ", "dr ", "doctor ", "uncle ", "aunt "
        )
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length).trim()
                break
            }
        }
        return cleaned
    }

    /**
     * Converts spoken number words (e.g. "zero three zero zero...") into digits.
     */
    fun convertSpokenWordsToDigits(input: String): String {
        val wordMap = listOf(
            "triple zero" to "000", "triple oh" to "000", "triple one" to "111", "triple two" to "222",
            "triple three" to "333", "triple four" to "444", "triple five" to "555", "triple six" to "666",
            "triple seven" to "777", "triple eight" to "888", "triple nine" to "999",
            "double zero" to "00", "double oh" to "00", "double one" to "11", "double two" to "22",
            "double three" to "33", "double four" to "44", "double five" to "55", "double six" to "66",
            "double seven" to "77", "double eight" to "88", "double nine" to "99",
            "zero" to "0", "oh" to "0",
            "one" to "1",
            "two" to "2",
            "three" to "3",
            "four" to "4",
            "five" to "5",
            "six" to "6",
            "seven" to "7",
            "eight" to "8",
            "nine" to "9",
            "plus" to "+"
        )
        var result = input.lowercase().trim()
        for ((word, digit) in wordMap) {
            result = result.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), digit)
        }
        return result
    }

    /**
     * Checks if the recipient is a phone number rather than a contact name.
     */
    fun isPhoneNumber(input: String): Boolean {
        val converted = convertSpokenWordsToDigits(input)
        val digits = converted.filter { it.isDigit() }
        val nonDigits = converted.replace("[0-9+\\s\\-\\(\\)]".toRegex(), "").trim()
        return digits.length >= 7 && nonDigits.isEmpty()
    }

    /**
     * Normalizes a phone number into an international format (+923001234567).
     * Handles local numbers (03001234567), international format (+923001234567, 00923001234567),
     * spoken numbers, and spaces/dashes.
     * Returns null if invalid.
     */
    fun normalizePhoneNumber(input: String): String? {
        val converted = convertSpokenWordsToDigits(input)
        val cleaned = converted.replace("[^0-9+]".toRegex(), "")
        if (cleaned.isBlank()) return null

        val digitsOnly = cleaned.filter { it.isDigit() }
        if (digitsOnly.length < 7 || digitsOnly.length > 15) return null

        if (cleaned.startsWith("+")) {
            return "+" + digitsOnly
        }
        if (cleaned.startsWith("00")) {
            return "+" + digitsOnly.removePrefix("00")
        }
        // Local number starting with 0 (e.g. 03001234567)
        if (cleaned.startsWith("0") && digitsOnly.length == 11) {
            return "+92" + digitsOnly.substring(1)
        }
        if (cleaned.startsWith("92") && digitsOnly.length == 12) {
            return "+92" + digitsOnly.substring(2)
        }
        if (cleaned.startsWith("0") && digitsOnly.length >= 10) {
            val prefix = getDefaultCountryPhonePrefix()
            return "+$prefix" + digitsOnly.substring(1)
        }
        return "+" + digitsOnly
    }

    /**
     * Normalizes a phone number for international WhatsApp messaging.
     * Converts local numbers with leading 0 into international format without dashes or spaces.
     */
    fun normalizePhoneNumberForWhatsApp(rawNumber: String): String {
        val normalized = normalizePhoneNumber(rawNumber)
        if (normalized != null) {
            return normalized.removePrefix("+")
        }
        val trimmed = rawNumber.trim()
        val digitsOnly = trimmed.replace("[^0-9+]".toRegex(), "")

        if (digitsOnly.startsWith("+")) {
            return digitsOnly.removePrefix("+")
        }
        if (digitsOnly.startsWith("00")) {
            return digitsOnly.removePrefix("00")
        }

        // Local number starting with 0 (e.g., 03001234567)
        if (digitsOnly.startsWith("0") && digitsOnly.length >= 10) {
            val countryPrefix = getDefaultCountryPhonePrefix()
            return countryPrefix + digitsOnly.substring(1)
        }

        return digitsOnly
    }

    private fun getDefaultCountryPhonePrefix(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simCountry = tm?.simCountryIso?.uppercase(Locale.ROOT)
                ?: tm?.networkCountryIso?.uppercase(Locale.ROOT)
                ?: Locale.getDefault().country.uppercase(Locale.ROOT)

            when (simCountry) {
                "PK" -> "92"
                "IN" -> "91"
                "US", "CA" -> "1"
                "GB", "UK" -> "44"
                "AE" -> "971"
                "SA" -> "966"
                "BD" -> "880"
                "AU" -> "61"
                "DE" -> "49"
                "FR" -> "33"
                else -> "92" // Default Pakistan prefix
            }
        } catch (_: Exception) {
            "92"
        }
    }

    @SuppressLint("Range")
    fun findContactAndCall(nameQuery: String, onSpeaker: Boolean = false): String {
        val cleanedQuery = nameQuery.trim().lowercase()
        if (cleanedQuery.isBlank()) {
            return "Meharbani karke contact ka naam bolain jise call karni hai."
        }

        try {
            val contactMatch = findContactPhone(cleanedQuery)

            if (contactMatch != null) {
                val (matchedName, matchedNumber) = contactMatch
                if (onSpeaker) {
                    setSpeakerphone(true)
                }
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$matchedNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return try {
                    context.startActivity(callIntent)
                    if (onSpeaker) "Calling $matchedName on speakerphone." else "Calling $matchedName."
                } catch (_: SecurityException) {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$matchedNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                    "Opening dialer for $matchedName at $matchedNumber."
                }
            } else {
                return "Could not find $nameQuery in contacts."
            }
        } catch (e: SecurityException) {
            return "Contacts permission is required to search phonebook."
        } catch (e: Exception) {
            return "Could not search contacts."
        }
    }

    fun callDirectNumber(number: String, onSpeaker: Boolean = false): String {
        val converted = convertSpokenWordsToDigits(number)
        val normalized = normalizePhoneNumber(converted) ?: converted.replace("[^0-9+]".toRegex(), "")
        if (normalized.isBlank()) return "Please say a valid phone number to call."

        if (onSpeaker) {
            setSpeakerphone(true)
        }

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalized")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val displayNum = if (normalized.isNotBlank()) normalized else number
        return try {
            context.startActivity(callIntent)
            if (onSpeaker) "Calling $displayNum on speakerphone." else "Calling $displayNum."
        } catch (_: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$normalized")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            "Opening dialer for $displayNum."
        }
    }

    @SuppressLint("MissingPermission")
    fun answerCallOnLoudspeaker(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager?.acceptRingingCall()
            }
            setSpeakerphone(true)
            "Call answered on speakerphone."
        } catch (e: SecurityException) {
            "Phone permission is required to answer calls."
        } catch (_: Exception) {
            "Could not answer call."
        }
    }

    @SuppressLint("MissingPermission")
    fun declineCall(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager?.endCall()
            }
            "Call declined."
        } catch (e: SecurityException) {
            "Phone permission is required to decline calls."
        } catch (_: Exception) {
            "Could not decline call."
        }
    }

    fun setSpeakerphone(enabled: Boolean): String {
        return try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = enabled
                if (enabled) "Speakerphone turned on." else "Speakerphone turned off."
            } ?: "Audio service unavailable."
        } catch (_: Exception) {
            "Could not set speakerphone."
        }
    }

    @SuppressLint("Range")
    fun getContactNameFromNumber(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            var contactName: String? = null
            cursor?.use {
                if (it.moveToFirst()) {
                    contactName = it.getString(it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
                }
            }
            contactName
        } catch (_: Exception) {
            null
        }
    }
}
