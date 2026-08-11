package com.gallr.shared.data.network

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.dto.ExhibitionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class ExhibitionApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
    private val catalogSource: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
) {
    private val restBase = "$supabaseUrl/rest/v1"

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
        client
            .get(buildExhibitionPageUrl(restBase, request, catalogSource))
            .body()

    private suspend fun fetchIntegrity(filter: ExhibitionPageFilter?): ExhibitionReaderIntegrityDto {
        val rows =
            client
                .get(buildExhibitionIntegrityUrl(restBase, filter, catalogSource))
                .body<List<ExhibitionReaderIntegrityDto>>()
        return singleExhibitionIntegrityRow(rows)
    }
}
