package com.gallr.shared.data.network

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.dto.EditorDto
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

// gallr's editorial schedule is anchored to Seoul. Computing "today" against
// device-local time would let a user temporarily abroad see a future editor
// activate ~24h early (or a finished editor linger ~24h late). Pinning the
// query date to Asia/Seoul keeps every device in sync with the editor's
// stated active_from / active_to dates.
private val EDITORIAL_TIMEZONE = TimeZone.of("Asia/Seoul")

class EditorApiClient(
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
     * Fetches the single active guest editor whose activation window
     * (active_from .. active_to) contains today in Asia/Seoul. When multiple
     * is_active rows have overlapping windows, the most recent active_from
     * wins. Returns null when no active editor exists.
     */
    suspend fun fetchActiveGuestEditor(): Editor? {
        val today = Clock.System.todayIn(EDITORIAL_TIMEZONE).toString()
        val query = "select=*" +
            "&is_active=eq.true" +
            "&active_from=lte.$today" +
            "&or=(active_to.is.null,active_to.gte.$today)" +
            "&order=active_from.desc" +
            "&limit=1"
        return client.get("$restBase/guest_editors?$query")
            .body<List<EditorDto>>()
            .firstOrNull()
            ?.toDomain()
    }
}
