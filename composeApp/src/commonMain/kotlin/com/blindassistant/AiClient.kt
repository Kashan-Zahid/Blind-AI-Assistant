package com.blindassistant

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cloud AI Client connecting directly to Google Gemini Developer API.
 * Provider: Google Gemini Developer API
 * Model: gemini-3.6-flash
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent
 * Authentication: x-goog-api-key: GEMINI_API_KEY
 */
class AiClient(
    cloudKey: String = "",
    cloudModel: String = DEFAULT_MODEL
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private var apiKey: String = cloudKey.trim()
    private var model: String = if (cloudModel.isNotBlank()) cloudModel.trim() else DEFAULT_MODEL

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(jsonParser)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    fun configure(apiKey: String, model: String = DEFAULT_MODEL) {
        this.apiKey = apiKey.trim()
        this.model = if (model.isNotBlank()) model.trim() else DEFAULT_MODEL
    }

    fun hasApiKey(): Boolean {
        val key = apiKey.trim()
        return key.isNotBlank() &&
                key != "REPLACE_WITH_GEMINI_KEY" &&
                key != "YOUR_GEMINI_KEY_HERE" &&
                key != "YOUR_KEY_HERE" &&
                key.length >= 10
    }

    /**
     * Sends a one-shot conversational question to Google Gemini Developer API.
     */
    suspend fun ask(question: String): String {
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            return "I did not hear a question. Please ask again."
        }

        if (!hasApiKey()) {
            return "Gemini API key is not configured."
        }

        return try {
            val response = withTimeoutOrNull(60_000) {
                sendGeminiRequest(
                    userPrompt = trimmed,
                    systemInstruction = "You are Blind AI Assistant, an empathetic, concise, and helpful voice assistant for blind and visually impaired users. Provide natural, clear, conversational spoken English answers suitable for text-to-speech without markdown, bold/italics, bullet points, long visual tables, or code formatting."
                )
            }
            response ?: "Request timed out. Please check your internet connection."
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Multimodal analysis of an image captured from device camera.
     */
    suspend fun askWithVision(prompt: String, base64Image: String): String {
        val trimmedPrompt = prompt.trim().ifBlank { "Describe this image clearly and concisely in natural English for a blind person, highlighting any safety hazards or obstacles first." }
        val trimmedImage = base64Image.trim()

        if (trimmedImage.isBlank()) {
            return "Camera image is not available."
        }

        if (!hasApiKey()) {
            return "Gemini API key is not configured."
        }

        return try {
            val response = withTimeoutOrNull(60_000) {
                sendGeminiVisionRequest(
                    userPrompt = trimmedPrompt,
                    base64Image = trimmedImage
                )
            }
            response ?: "Vision request timed out. Please try again."
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Processes text with a specific system instruction (e.g. web search / deep research summarization).
     */
    suspend fun askWithContext(systemPrompt: String, userPrompt: String): String {
        val trimmedUser = userPrompt.trim()
        if (trimmedUser.isBlank()) {
            return "No text provided to process."
        }

        if (!hasApiKey()) {
            return "Gemini API key is not configured."
        }

        return try {
            val fullSystem = if (systemPrompt.isNotBlank()) "$systemPrompt. ALWAYS respond in natural, clear, concise spoken English." else "ALWAYS respond in natural, clear, concise spoken English."
            val response = withTimeoutOrNull(90_000) {
                sendGeminiRequest(
                    userPrompt = trimmedUser,
                    systemInstruction = fullSystem.trim()
                )
            }
            response ?: "Request timed out. Please try again."
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Executes single API diagnostic check.
     */
    suspend fun testConnection(): Pair<Boolean, String> {
        if (!hasApiKey()) {
            return Pair(false, "Gemini API key is not configured.")
        }
        return try {
            val res = sendGeminiRequest(
                userPrompt = "Reply with exactly OK.",
                systemInstruction = ""
            )
            val isSuccess = res.isNotBlank() &&
                    !res.startsWith("Gemini", ignoreCase = true) &&
                    !res.startsWith("Unable", ignoreCase = true) &&
                    !res.startsWith("Failed", ignoreCase = true)
            Pair(isSuccess, res)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Connection test failed")
        }
    }

    // ----------------------------------------------------
    // GEMINI DEVELOPER API HTTP REQUESTS
    // ----------------------------------------------------

    private suspend fun sendGeminiRequest(
        userPrompt: String,
        systemInstruction: String
    ): String {
        val targetModel = model.ifBlank { DEFAULT_MODEL }
        val endpointUrl = "$GEMINI_BASE_URL/$targetModel:generateContent"

        // Build combined prompt if systemInstruction is provided
        val fullPrompt = if (systemInstruction.isNotBlank()) {
            "$systemInstruction\n\n$userPrompt"
        } else {
            userPrompt
        }

        // Exact requested structure:
        // { "contents": [ { "parts": [ { "text": "USER_PROMPT" } ] } ] }
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive(fullPrompt))
                        })
                    })
                })
            })
        }

        val httpResponse = httpClient.post(endpointUrl) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("x-goog-api-key", apiKey)
            setBody(payload.toString())
        }

        val responseBody = httpResponse.bodyAsText()

        return if (httpResponse.status == HttpStatusCode.OK) {
            parseGeminiResponse(responseBody)
        } else {
            parseGeminiError(httpResponse.status, responseBody)
        }
    }

    private suspend fun sendGeminiVisionRequest(
        userPrompt: String,
        base64Image: String
    ): String {
        val targetModel = model.ifBlank { DEFAULT_MODEL }
        val endpointUrl = "$GEMINI_BASE_URL/$targetModel:generateContent"

        // Clean base64 data prefix if present
        val cleanBase64 = if (base64Image.contains(",")) {
            base64Image.substringAfter(",")
        } else {
            base64Image
        }

        // Multimodal structure for Gemini Developer API:
        // { "contents": [ { "parts": [ { "text": "..." }, { "inlineData": { "mimeType": "image/jpeg", "data": "..." } } ] } ] }
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive(userPrompt))
                        })
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", JsonPrimitive("image/jpeg"))
                                put("data", JsonPrimitive(cleanBase64))
                            })
                        })
                    })
                })
            })
        }

        val httpResponse = httpClient.post(endpointUrl) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("x-goog-api-key", apiKey)
            setBody(payload.toString())
        }

        val responseBody = httpResponse.bodyAsText()

        return if (httpResponse.status == HttpStatusCode.OK) {
            parseGeminiResponse(responseBody)
        } else {
            parseGeminiError(httpResponse.status, responseBody)
        }
    }

    private fun parseGeminiResponse(responseBody: String): String {
        return try {
            val root = jsonParser.parseToJsonElement(responseBody).jsonObject
            val candidates = root["candidates"]?.jsonArray
            if (!candidates.isNullOrEmpty()) {
                val firstCandidate = candidates[0].jsonObject
                val contentObj = firstCandidate["content"]?.jsonObject
                val parts = contentObj?.get("parts")?.jsonArray
                if (!parts.isNullOrEmpty()) {
                    val rawText = parts[0].jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    if (rawText.isNotBlank()) {
                        return cleanForSpeech(rawText)
                    }
                }
            }

            "Gemini returned an empty response."
        } catch (e: Exception) {
            "Failed to parse Gemini response: ${e.message}"
        }
    }

    private fun parseGeminiError(status: HttpStatusCode, responseBody: String): String {
        return when (status.value) {
            400 -> "Gemini request was invalid. Please check the prompt."
            401, 403 -> "Gemini API key is invalid or unauthorized."
            404 -> "Gemini model or endpoint was not found."
            408 -> "Gemini request timed out."
            429 -> "Gemini rate limit reached. Please try again later."
            in 500..599 -> "Gemini server error. Please try again later."
            else -> "Gemini returned HTTP ${status.value}. Please try again later."
        }
    }

    private fun handleException(e: Exception): String {
        val msg = e.message ?: e.toString()
        return when {
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("SocketTimeout", ignoreCase = true) ->
                "Gemini request timed out."

            else ->
                "Unable to connect to Gemini. Please check your internet connection."
        }
    }

    fun cleanForSpeech(raw: String): String {
        return raw.cleanForSpeechInternal()
    }

    private fun String.cleanForSpeechInternal(): String {
        return this
            // Markdown bold/italics
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("_([^_]+)_"), "$1")
            // Code blocks and inline code
            .replace(Regex("```[a-zA-Z]*"), "")
            .replace("```", "")
            .replace("`", "")
            // URLs
            .replace(Regex("https?://\\S+"), "")
            // Markdown headers
            .replace(Regex("^[#]+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[#>\\[\\]{}~\\|\\\\]"), "")
            // Bullet points and list markers
            .replace(Regex("^[•*\\-]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\d+\\.\\s*", RegexOption.MULTILINE), "")
            // Special symbols to spoken words
            .replace("&", " and ")
            .replace("%", " percent ")
            .replace("°", " degrees ")
            .replace("+", " plus ")
            .replace("=", " equals ")
            .replace("@", " at ")
            // Newlines to speech pause
            .replace(Regex("\\n+"), ". ")
            // Collapse multiple spaces and dots
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\.\\s*\\."), ".")
            .trim()
    }
}
