package com.blindassistant

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WebSearchManager(private val aiClient: AiClient) {

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 30_000L
            requestTimeoutMillis = 30_000L
        }
    }

    suspend fun searchLiveWeb(query: String): String {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return "Please specify what you want to search on the web."

        val snippets = fetchWebSnippets(cleaned)
        if (snippets.isBlank()) {
            return "I could not retrieve live web search results for '$cleaned'. Please verify your internet connection or try rephrasing your search."
        }

        val prompt = "User search query: $cleaned\n\nWeb Search Results:\n$snippets"
        val systemPrompt = "You are Blind AI Assistant summarizing real-time web search results for a blind person. Give a clear, concise, accurate, natural spoken summary based on the provided search results. Avoid markdown, decorative symbols, and raw URLs."

        return aiClient.askWithContext(systemPrompt, prompt)
    }

    suspend fun conductDeepResearch(topic: String): String {
        val cleaned = topic.trim()
        if (cleaned.isBlank()) return "Please specify a topic for deep research."

        val subQueries = listOf(
            cleaned,
            "$cleaned overview and history",
            "$cleaned key facts and details",
            "$cleaned current status and developments"
        )

        val collectedSnippets = mutableSetOf<String>()
        for (q in subQueries) {
            val snips = fetchWebSnippets(q)
            if (snips.isNotBlank()) {
                collectedSnippets.add(snips)
            }
        }

        val evidence = if (collectedSnippets.isNotEmpty()) {
            collectedSnippets.joinToString("\n\n---\n\n")
        } else {
            "No specific external search snippets found."
        }

        val systemPrompt = "You are Blind AI Assistant conducting deep research for a blind person. Based on the consolidated web research evidence below, synthesize a comprehensive, clear, and structured natural spoken report. Explain the key background, milestones, and current status. Avoid markdown formatting, tables, or reading raw URLs."
        val userPrompt = "Research Topic: $cleaned\n\nCollected Research Evidence:\n$evidence"

        return aiClient.askWithContext(systemPrompt, userPrompt)
    }

    private suspend fun fetchWebSnippets(query: String): String {
        val results = mutableListOf<String>()

        // 1. DuckDuckGo Instant Answer API
        try {
            val ddgUrl = "https://api.duckduckgo.com/?q=${encodeUrl(query)}&format=json&no_html=1&skip_disambig=1"
            val response: HttpResponse = httpClient.get(ddgUrl)
            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                val root = Json.parseToJsonElement(body).jsonObject
                val abstractText = root["AbstractText"]?.jsonPrimitive?.content
                if (!abstractText.isNullOrBlank()) {
                    results.add(abstractText.trim())
                }

                val relatedTopics = root["RelatedTopics"]?.jsonArray
                if (!relatedTopics.isNullOrEmpty()) {
                    for (topic in relatedTopics.take(4)) {
                        if (topic is JsonObject) {
                            val text = topic["Text"]?.jsonPrimitive?.content
                            if (!text.isNullOrBlank()) {
                                results.add(text.trim())
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. DuckDuckGo HTML Search
        try {
            val htmlUrl = "https://html.duckduckgo.com/html/?q=${encodeUrl(query)}"
            val htmlResponse: HttpResponse = httpClient.get(htmlUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            if (htmlResponse.status == HttpStatusCode.OK) {
                val html = htmlResponse.bodyAsText()
                val snippetRegex = Regex("<a class=\"result__snippet[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                val matches = snippetRegex.findAll(html).take(5)
                for (match in matches) {
                    val rawSnippet = match.groupValues[1]
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&#x27;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .trim()
                    if (rawSnippet.isNotBlank() && !results.contains(rawSnippet)) {
                        results.add(rawSnippet)
                    }
                }
            }
        } catch (_: Exception) {}

        return results.joinToString("\n")
    }

    suspend fun getWeather(locationQuery: String? = null): String {
        val loc = if (!locationQuery.isNullOrBlank()) locationQuery.trim() else "current location"
        return try {
            val weatherUrl = if (!locationQuery.isNullOrBlank()) {
                "https://wttr.in/${encodeUrl(loc)}?format=%C,+%t,+Wind+%w"
            } else {
                "https://wttr.in/?format=%C,+%t,+Wind+%w"
            }
            val report: String = httpClient.get(weatherUrl).body()
            if (report.isNotBlank() && !report.contains("<html")) {
                "Weather in $loc is currently ${report.trim()}."
            } else {
                searchLiveWeb("What is the current weather in $loc?")
            }
        } catch (_: Exception) {
            searchLiveWeb("What is the current weather in $loc?")
        }
    }

    private fun encodeUrl(value: String): String {
        return value.replace(" ", "%20")
            .replace("?", "%3F")
            .replace("&", "%26")
            .replace("+", "%2B")
    }
}
