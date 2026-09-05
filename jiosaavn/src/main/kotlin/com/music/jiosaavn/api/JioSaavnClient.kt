package com.music.jiosaavn.api

import com.music.jiosaavn.models.JioSaavnSearchResponse
import com.music.jiosaavn.models.JioSaavnSongRaw
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object JioSaavnClient {
    private const val BASE_URL = "https://www.jiosaavn.com"

    private val jsonConfig = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(jsonConfig)
            }

            defaultRequest {
                url(BASE_URL)
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                header("Accept", "application/json, text/plain, */*")
            }

            expectSuccess = false
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 5): List<JioSaavnSongRaw> {
        if (query.isBlank()) return emptyList()
        return try {
            val response: JioSaavnSearchResponse = client.get("/api.php") {
                parameter("__call", "search.getResults")
                parameter("_format", "json")
                parameter("n", limit)
                parameter("p", 1)
                parameter("q", query)
                parameter("_marker", "0")
                parameter("ctx", "android")
            }.body()

            response.results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
