package com.gallr.shared.data.network

import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.network.dto.PromotionRequestDto
import com.gallr.shared.data.network.dto.PromotionResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/** Runtime source for one separately disclosed local promotion. */
interface PromotionSource {
    suspend fun fetch(
        key: String,
        cityKo: String,
        regionKo: String,
    ): PromotedExhibition?
}

class PromotionApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) : PromotionSource {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/functions/v1/promoted-nearby"

    override suspend fun fetch(
        key: String,
        cityKo: String,
        regionKo: String,
    ): PromotedExhibition? {
        val response =
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Origin, "app://gallr")
                setBody(PromotionRequestDto(key, cityKo, regionKo))
            }
        if (response.status == HttpStatusCode.NoContent) return null
        if (response.status != HttpStatusCode.OK) error("Promotion service unavailable.")
        return response.body<PromotionResponseDto>().placement.toDomain()
    }
}
