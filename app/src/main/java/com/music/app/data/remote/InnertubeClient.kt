package com.music.app.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SearchResult(
    val id: String,
    val title: String,
    val artists: String,
    val durationText: String,
    val thumbnailUrl: String?,
    val albumName: String?
)

class InnertubeClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
        defaultRequest {
            url("https://music.youtube.com")
        }
    }

    suspend fun search(query: String): List<SearchResult> {
        Log.d(TAG, "search: query='$query'")
        try {
            val body = buildSearchBody(query)
            Log.d(TAG, "search: posting to /youtubei/v1/search")
            val response = client.post("/youtubei/v1/search") {
                header("X-Goog-Api-Key", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = response.bodyAsText()
            Log.d(TAG, "search: response received, length=${text.length}")
            val results = parseSearchResults(json.parseToJsonElement(text))
            Log.d(TAG, "search: parsed ${results.size} results")
            return results
        } catch (e: Exception) {
            Log.e(TAG, "search: failed", e)
            throw e
        }
    }

    private fun buildSearchBody(query: String): String {
        return buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", JsonPrimitive("WEB_REMIX"))
                    put("clientVersion", JsonPrimitive("1.20250407.01.00"))
                    put("platform", JsonPrimitive("DESKTOP"))
                    put("hl", JsonPrimitive("en"))
                    put("gl", JsonPrimitive("US"))
                })
            })
            put("query", JsonPrimitive(query))
            put("params", JsonPrimitive("EgWKAQIIAWoKEAoQCRADEAAYAQ%3D%3D"))
        }.toString()
    }

    private fun parseSearchResults(element: JsonElement): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val contents = element.jsonObject
                .get("contents")?.jsonObject
                ?.get("tabbedSearchResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("tabRenderer")?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("sectionListRenderer")?.jsonObject
                ?.get("contents")?.jsonArray ?: return results

            for (section in contents) {
                val shelf = section.jsonObject["musicShelfRenderer"]?.jsonObject ?: continue
                val items = shelf["contents"]?.jsonArray ?: continue

                for (item in items) {
                    val renderer = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: continue
                    val result = parseItem(renderer) ?: continue
                    results.add(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseSearchResults: error parsing", e)
        }
        return results
    }

    private fun parseItem(renderer: JsonObject): SearchResult? {
        val flexColumns = renderer["flexColumns"]?.jsonArray ?: return null
        val id = extractVideoId(renderer) ?: return null
        val title = extractText(flexColumns, 0) ?: return null
        val artists = extractText(flexColumns, 1) ?: ""
        val album = extractText(flexColumns, 2)
        val duration = extractText(flexColumns, 3) ?: ""
        val thumbnail = extractThumbnail(renderer)
        return SearchResult(id, title, artists, duration, thumbnail, album)
    }

    private fun extractVideoId(renderer: JsonObject): String? {
        return renderer["navigationEndpoint"]?.jsonObject
            ?.get("watchEndpoint")?.jsonObject
            ?.get("videoId")?.jsonPrimitive?.content
            ?: renderer["flexColumns"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                ?.get("text")?.jsonObject
                ?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("navigationEndpoint")?.jsonObject
                ?.get("watchEndpoint")?.jsonObject
                ?.get("videoId")?.jsonPrimitive?.content
    }

    private fun extractText(columns: JsonArray, index: Int): String? {
        return columns.getOrNull(index)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject
            ?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
    }

    private fun extractThumbnail(renderer: JsonObject): String? {
        return renderer["thumbnail"]?.jsonObject
            ?.get("musicThumbnailRenderer")?.jsonObject
            ?.get("thumbnail")?.jsonObject
            ?.get("thumbnails")?.jsonArray
            ?.lastOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
    }

    fun dispose() {
        Log.d(TAG, "dispose: closing client")
        client.close()
    }

    companion object {
        private const val TAG = "InnertubeClient"
    }
}
