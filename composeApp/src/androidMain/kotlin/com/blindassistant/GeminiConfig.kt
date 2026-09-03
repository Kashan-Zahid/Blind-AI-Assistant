package com.blindassistant

/**
 * Google Gemini Developer API Configuration for Android development build.
 * Provider: Google Gemini Developer API
 * Model: gemini-3.6-flash
 *
 * The API key is injected at build time from `local.properties`
 * (GEMINI_API_KEY=...) via BuildConfig. Never commit the key to source.
 */
internal object GeminiConfig {
    const val API_KEY = BuildConfig.GEMINI_API_KEY
    const val DEFAULT_MODEL = "gemini-3.6-flash"
}
