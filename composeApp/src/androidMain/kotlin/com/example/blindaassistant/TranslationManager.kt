package com.example.blindaassistant

import java.util.Locale

class TranslationManager(private val aiClient: AiClient) {

    private val supportedLanguages = mapOf(
        "english" to Locale.ENGLISH,
        "urdu" to Locale("ur", "PK"),
        "hindi" to Locale("hi", "IN"),
        "arabic" to Locale("ar", "SA"),
        "spanish" to Locale("es", "ES"),
        "french" to Locale.FRENCH,
        "german" to Locale.GERMAN,
        "portuguese" to Locale("pt", "BR"),
        "italian" to Locale.ITALIAN,
        "chinese" to Locale.CHINESE,
        "japanese" to Locale.JAPANESE
    )

    fun changeLanguage(languageName: String): Pair<Boolean, String> {
        val cleaned = languageName.trim().lowercase()
        val locale = supportedLanguages[cleaned]

        return if (locale != null) {
            AndroidVoiceService.activeInstance?.setTtsLanguage(locale)
            val langDisplay = cleaned.replaceFirstChar { it.uppercase() }
            Pair(true, "Language set to $langDisplay.")
        } else {
            Pair(false, "Language '$languageName' is not supported. Supported languages include English, Urdu, Hindi, Arabic, Spanish, French, German, and Portuguese.")
        }
    }

    suspend fun translateText(phrase: String, targetLanguage: String): String {
        val cleanedPhrase = phrase.trim()
        val cleanedLang = targetLanguage.trim().lowercase()

        if (cleanedPhrase.isBlank() || cleanedLang.isBlank()) {
            return "Please tell me what phrase to translate and into which language."
        }

        // Fast offline common phrase translations
        val quickTranslation = getOfflineQuickTranslation(cleanedPhrase, cleanedLang)
        if (quickTranslation != null) {
            return quickTranslation
        }

        // Use AI client for context-aware accurate translation
        val prompt = "Translate this phrase into $targetLanguage accurately and naturally for a blind listener. Return ONLY the translated text and nothing else: '$cleanedPhrase'"
        val result = aiClient.ask(prompt)
        return if (result.isNotBlank() && !result.startsWith("API Key is missing") && !result.startsWith("Error:")) {
            "In $targetLanguage: $result"
        } else {
            "Could not translate '$cleanedPhrase' to $targetLanguage right now."
        }
    }

    private fun getOfflineQuickTranslation(phrase: String, targetLang: String): String? {
        val p = phrase.lowercase()
        val l = targetLang.lowercase()

        return when {
            p.contains("hello") || p.contains("hi") -> when {
                l == "urdu" || l == "hindi" -> "In $targetLang: As-salamu alaykum / Namaste."
                l == "spanish" -> "In Spanish: Hola."
                l == "french" -> "In French: Bonjour."
                l == "arabic" -> "In Arabic: Marhaban."
                l == "german" -> "In German: Hallo."
                else -> null
            }
            p.contains("thank you") || p.contains("thanks") -> when {
                l == "urdu" -> "In Urdu: Shukriya."
                l == "hindi" -> "In Hindi: Dhanyavaad."
                l == "spanish" -> "In Spanish: Gracias."
                l == "french" -> "In French: Merci."
                l == "arabic" -> "In Arabic: Shukran."
                l == "german" -> "In German: Danke."
                else -> null
            }
            p.contains("where is") -> when {
                l == "spanish" -> "In Spanish: ¿Dónde está?"
                l == "french" -> "In French: Où est...?"
                l == "urdu" -> "In Urdu: Kahan hai?"
                else -> null
            }
            p.contains("help") -> when {
                l == "spanish" -> "In Spanish: Ayuda."
                l == "french" -> "In French: Au secours."
                l == "arabic" -> "In Arabic: Musa'ada."
                l == "urdu" -> "In Urdu: Madad."
                else -> null
            }
            else -> null
        }
    }
}
