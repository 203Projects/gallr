package com.gallr.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reads the exhibitions opened most in the app from `list_most_viewed_exhibition_ids` (spec 091 FR-019). */
interface MostViewedExhibitionApi {
    /** Exhibition ids, most opened first, over the last [days] Seoul days; at most [limit] of them. */
    suspend fun fetchIds(
        days: Int,
        limit: Int,
    ): List<String>
}

internal fun mostViewedRequestBody(
    days: Int,
    limit: Int,
): String =
    buildJsonObject {
        put("p_days", days)
        put("p_limit", limit)
    }.toString()

/**
 * The function answers with a JSON list of id strings. Anything else (an error object before the migration is
 * applied, a body that is not JSON) reads as an empty ranking, and rows that are not a non-blank string are dropped,
 * so one odd row never costs the visitor the rest.
 */
internal fun decodeMostViewedExhibitionIds(body: String): List<String> {
    val rows = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
    return rows
        .mapNotNull { row -> (row as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) }
        .distinct()
}

class MostViewedExhibitionApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) : MostViewedExhibitionApi {
    private val restBase = "$supabaseUrl/rest/v1"

    override suspend fun fetchIds(
        days: Int,
        limit: Int,
    ): List<String> =
        decodeMostViewedExhibitionIds(
            client
                .post("$restBase/rpc/list_most_viewed_exhibition_ids") {
                    contentType(ContentType.Application.Json)
                    setBody(mostViewedRequestBody(days, limit))
                }.bodyAsText(),
        )
}
