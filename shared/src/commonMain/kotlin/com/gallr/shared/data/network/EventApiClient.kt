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
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface EventApi {
    suspend fun fetchEvents(): List<Event>
    suspend fun fetchEventById(id: String): Event?
    suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition>
}

internal fun buildEventByIdUrl(restBase: String, id: String): String =
    URLBuilder("$restBase/events").apply {
        parameters.append("select", "*")
        parameters.append("id", "eq.${postgrestFilterLiteral(id)}")
        parameters.append("limit", "1")
    }.buildString()

class EventApiClient(
    supabaseUrl: String,
    anonKey: String,
    private val exhibitionCatalogSource: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
) : EventApi {
    private val restBase = "$supabaseUrl/rest/v1"

    private val client = HttpClient {
        expectSuccess = true
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
            headers.appendSupabaseApiKey(anonKey)
        }
    }

    override suspend fun fetchEvents(): List<Event> =
        client.get("$restBase/events?select=*")
            .body<List<EventDto>>()
            .mapNotNull { it.toDomain() }

    override suspend fun fetchEventById(id: String): Event? =
        client.get(buildEventByIdUrl(restBase, id))
            .body<List<EventDto>>()
            .firstOrNull()
            ?.toDomain()

    override suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition> =
        fetchAllExhibitions(
            filter = ExhibitionPageFilter.Event(id),
            source = exhibitionCatalogSource,
            fetchPage = ::fetchExhibitionPage,
            fetchIntegrity = ::fetchExhibitionIntegrity,
        )

    private suspend fun fetchExhibitionPage(request: ExhibitionPageRequest): List<ExhibitionDto> =
        client.get(buildExhibitionPageUrl(restBase, request, exhibitionCatalogSource))
            .body()

    private suspend fun fetchExhibitionIntegrity(
        filter: ExhibitionPageFilter?,
    ): ExhibitionReaderIntegrityDto {
        val rows = client.get(
            buildExhibitionIntegrityUrl(restBase, filter, exhibitionCatalogSource),
        )
            .body<List<ExhibitionReaderIntegrityDto>>()
        return singleExhibitionIntegrityRow(rows)
    }
}
