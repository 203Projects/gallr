package com.gallr.shared.data.network

import com.gallr.shared.data.model.GuestEditor
import com.gallr.shared.data.network.dto.GuestEditorDto
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
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json

class GuestEditorApiClient(
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

    /**
     * Fetches the single active guest editor whose active_to is in the future or null.
     * When multiple is_active rows exist, the most recent active_from wins.
     * Returns null when no active editor exists.
     */
    suspend fun fetchActiveGuestEditor(): GuestEditor? {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val query = "select=*" +
            "&is_active=eq.true" +
            "&or=(active_to.is.null,active_to.gte.$today)" +
            "&order=active_from.desc" +
            "&limit=1"
        return client.get("$restBase/guest_editors?$query")
            .body<List<GuestEditorDto>>()
            .firstOrNull()
            ?.toDomain()
    }
}
