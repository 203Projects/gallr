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
    private val catalogSource: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
) {
    private val restBase = "$supabaseUrl/rest/v1"
    private val countryCodeRollout = CatalogCountryCodeRollout()

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

    suspend fun fetchFeatured(): List<Exhibition> =
        fetchAllExhibitions(
            filter = ExhibitionPageFilter.Featured,
            source = catalogSource,
            fetchPage = ::fetchPage,
            fetchIntegrity = ::fetchIntegrity,
        )

    suspend fun fetchExhibitions(): List<Exhibition> =
        fetchAllExhibitions(
            source = catalogSource,
            fetchPage = ::fetchPage,
            fetchIntegrity = ::fetchIntegrity,
        )

    private suspend fun fetchPage(request: ExhibitionPageRequest): List<ExhibitionDto> =
        countryCodeRollout.fetch(
            request = { includeCountryCode ->
                client.get(
                    buildExhibitionPageUrl(
                        restBase = restBase,
                        request = request,
                        source = catalogSource,
                        includeCountryCode = includeCountryCode,
                    ),
                ).body()
            },
            isMissingCountryCodeColumn = { it.isMissingCountryCodeColumnResponse() },
        )

    private suspend fun fetchIntegrity(
        filter: ExhibitionPageFilter?,
    ): ExhibitionReaderIntegrityDto {
        val rows = client.get(buildExhibitionIntegrityUrl(restBase, filter, catalogSource))
            .body<List<ExhibitionReaderIntegrityDto>>()
        return singleExhibitionIntegrityRow(rows)
    }
}
