package com.gallr.shared.data.network

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.dto.EventDto
import com.gallr.shared.data.network.dto.ExhibitionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.URLBuilder

interface EventApi {
    suspend fun fetchEvents(): List<Event>

    suspend fun fetchEventById(id: String): Event?

    suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition>
}

internal fun buildEventByIdUrl(
    restBase: String,
    id: String,
): String =
    URLBuilder("$restBase/events")
        .apply {
            parameters.append("select", "*")
            parameters.append("id", "eq.${postgrestFilterLiteral(id)}")
            parameters.append("limit", "1")
        }.buildString()

class EventApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
    private val exhibitionCatalogSource: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
) : EventApi {
    private val restBase = "$supabaseUrl/rest/v1"
    private val countryCodeRollout = CatalogCountryCodeRollout()

    override suspend fun fetchEvents(): List<Event> =
        client
            .get("$restBase/events?select=*")
            .body<List<EventDto>>()
            .mapNotNull { it.toDomain() }

    override suspend fun fetchEventById(id: String): Event? =
        client
            .get(buildEventByIdUrl(restBase, id))
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
        countryCodeRollout.fetch(
            request = { includeCountryCode ->
                client
                    .get(
                        buildExhibitionPageUrl(
                            restBase = restBase,
                            request = request,
                            source = exhibitionCatalogSource,
                            includeCountryCode = includeCountryCode,
                        ),
                    ).body()
            },
            isMissingCountryCodeColumn = { it.isMissingCountryCodeColumnResponse() },
        )

    private suspend fun fetchExhibitionIntegrity(filter: ExhibitionPageFilter?): ExhibitionReaderIntegrityDto {
        val rows =
            client
                .get(
                    buildExhibitionIntegrityUrl(restBase, filter, exhibitionCatalogSource),
                ).body<List<ExhibitionReaderIntegrityDto>>()
        return singleExhibitionIntegrityRow(rows)
    }
}
