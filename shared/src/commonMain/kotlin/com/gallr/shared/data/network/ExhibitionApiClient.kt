package com.gallr.shared.data.network

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.dto.ExhibitionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ExhibitionApiClient(
    supabaseUrl: String,
    anonKey: String,
) {
    private val restBase = "$supabaseUrl/rest/v1"

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.INFO
        }
        defaultRequest {
            headers.append("apikey", anonKey)
            headers.append("Authorization", "Bearer $anonKey")
        }
    }

    suspend fun fetchFeatured(): List<Exhibition> =
        client.get("$restBase/exhibitions?select=*&is_featured=eq.true")
            .body<List<ExhibitionDto>>()
            .mapNotNull { it.toDomain() }

    suspend fun fetchExhibitions(): List<Exhibition> =
        client.get("$restBase/exhibitions?select=*")
            .body<List<ExhibitionDto>>()
            .mapNotNull { it.toDomain() }
}
