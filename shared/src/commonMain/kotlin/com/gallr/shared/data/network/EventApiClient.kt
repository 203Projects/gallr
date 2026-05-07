package com.gallr.shared.data.network

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.dto.EventDto
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

interface EventApi {
    suspend fun fetchEvents(): List<Event>
    suspend fun fetchEventById(id: String): Event?
    suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition>
}

class EventApiClient(
    supabaseUrl: String,
    anonKey: String,
) : EventApi {
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

    override suspend fun fetchEvents(): List<Event> =
        client.get("$restBase/events?select=*")
            .body<List<EventDto>>()
            .mapNotNull { it.toDomain() }

    override suspend fun fetchEventById(id: String): Event? =
        client.get("$restBase/events?select=*&id=eq.$id&limit=1")
            .body<List<EventDto>>()
            .firstOrNull()
            ?.toDomain()

    override suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition> =
        client.get("$restBase/exhibitions?select=*&event_id=eq.$id")
            .body<List<ExhibitionDto>>()
            .mapNotNull { it.toDomain() }
}
