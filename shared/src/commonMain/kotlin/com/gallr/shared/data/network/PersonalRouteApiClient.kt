package com.gallr.shared.data.network

import com.gallr.shared.data.network.dto.PersonalRouteDto
import com.gallr.shared.data.network.dto.PersonalRouteRowDto
import com.gallr.shared.data.network.dto.PersonalRouteSummaryDto
import com.gallr.shared.data.network.dto.PostgrestErrorDto
import com.gallr.shared.data.network.dto.PublicRouteSummaryDto
import com.gallr.shared.data.network.dto.PublishedRouteDto
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A route call the server refused or could not answer, carrying the failure the app explains. */
class PersonalRouteApiException(
    val failure: PersonalRouteFailure,
) : Exception("personal route request failed")

/** The server side of personal routes, so repositories can be tested without HTTP. */
interface PersonalRouteRemoteSource {
    suspend fun save(route: PersonalRoute): PersonalRoute

    suspend fun publish(id: String): PersonalRoute

    suspend fun delete(id: String)

    suspend fun listMine(): List<PersonalRouteSummary>

    suspend fun loadMine(id: String): PersonalRoute

    /** Asks for public listing; an active editor is approved at once (spec 089 US7). */
    suspend fun requestListing(id: String): PersonalRouteSummary

    /** Withdraws a request or takes an approved route off the list. */
    suspend fun withdrawListing(id: String): PersonalRouteSummary

    /** The ranked public list; readable without an account. */
    suspend fun listPublic(limit: Int): List<PublicRouteSummary>

    /**
     * A listed route's stops and author by id, or null when it is not shown (withdrawn, declined, removed,
     * unpublished, revoked or no longer walkable); readable without an account.
     */
    suspend fun loadPublicStops(id: String): PublicRouteStops?

    /** Counts this account's copy of a listed route (once per approved version) and returns its stops. */
    suspend fun copyPublic(id: String): PersonalRoute

    suspend fun report(
        id: String,
        reason: RouteReportReason,
    )
}

/**
 * Calls the spec 089 route functions with the signed-in user's token. Only ids, the name and the ordered
 * exhibition ids are sent; the server copies every stop detail from the catalogue (E-D7).
 */
class PersonalRouteApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
    private val accessTokenProvider: suspend () -> String?,
) : PersonalRouteRemoteSource {
    private val restBase = "${supabaseUrl.trimEnd('/')}/rest/v1"
    private val errorJson = Json { ignoreUnknownKeys = true }
    private val rowJson =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    override suspend fun save(route: PersonalRoute): PersonalRoute =
        rpc(
            "save_personal_route",
            buildJsonObject {
                put("p_id", route.id)
                put("p_name", route.trimmedName)
                put("p_exhibition_ids", JsonArray(route.stops.map { JsonPrimitive(it.exhibitionId) }))
            }.toString(),
        ).body<PersonalRouteDto>().toDomain()

    override suspend fun publish(id: String): PersonalRoute =
        rpc("publish_personal_route", buildJsonObject { put("p_id", id) }.toString())
            .body<PersonalRouteDto>()
            .toDomain()

    override suspend fun delete(id: String) {
        rpc("delete_personal_route", buildJsonObject { put("p_id", id) }.toString())
    }

    override suspend fun listMine(): List<PersonalRouteSummary> =
        rpc("list_my_personal_routes", "{}").body<List<PersonalRouteSummaryDto>>().map { it.toDomain() }

    override suspend fun loadMine(id: String): PersonalRoute {
        val token = token()
        val response =
            client.get("$restBase/personal_routes") {
                bearerAuth(token)
                parameter("id", "eq.$id")
                parameter("select", ROUTE_SELECT)
            }
        val rows = checked(response).body<List<PersonalRouteRowDto>>()
        return rows.firstOrNull()?.toDomain() ?: throw PersonalRouteApiException(PersonalRouteFailure.NotFound)
    }

    override suspend fun requestListing(id: String): PersonalRouteSummary =
        rpc("request_route_listing", idBody(id)).body<PersonalRouteSummaryDto>().toDomain()

    override suspend fun withdrawListing(id: String): PersonalRouteSummary =
        rpc("withdraw_route_listing", idBody(id)).body<PersonalRouteSummaryDto>().toDomain()

    override suspend fun listPublic(limit: Int): List<PublicRouteSummary> {
        val rows = publicRpc("list_public_routes", buildJsonObject { put("p_limit", limit) }.toString()).bodyAsText()
        // One malformed row must not hide the rest of the list.
        return rowJson.parseToJsonElement(rows).jsonArray.mapNotNull(::publicRowOrNull)
    }

    private fun publicRowOrNull(row: JsonElement): PublicRouteSummary? =
        runCatching { rowJson.decodeFromJsonElement(PublicRouteSummaryDto.serializer(), row) }
            .getOrNull()
            ?.toDomain()

    override suspend fun loadPublicStops(id: String): PublicRouteStops? {
        // get_listed_route answers only while the route is on the public list; shared links use get_published_route.
        val body = publicRpc("get_listed_route", idBody(id)).bodyAsText()
        if (body.isBlank() || body.trim() == "null") return null
        return rowJson.decodeFromString(PublishedRouteDto.serializer(), body).toPublicStops()
    }

    override suspend fun copyPublic(id: String): PersonalRoute =
        rpc("save_public_route", idBody(id)).body<PublishedRouteDto>().toDomain()

    override suspend fun report(
        id: String,
        reason: RouteReportReason,
    ) {
        rpc(
            "report_route",
            buildJsonObject {
                put("p_id", id)
                put("p_reason", reason.wire)
            }.toString(),
        )
    }

    private fun idBody(id: String): String = buildJsonObject { put("p_id", id) }.toString()

    /** Reads open to readers without an account: the bearer token is sent only when someone is signed in. */
    private suspend fun publicRpc(
        function: String,
        jsonBody: String,
    ): HttpResponse {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
        val response =
            client.post("$restBase/rpc/$function") {
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        return checked(response)
    }

    private suspend fun rpc(
        function: String,
        jsonBody: String,
    ): HttpResponse {
        val token = token()
        val response =
            client.post("$restBase/rpc/$function") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        return checked(response)
    }

    private suspend fun token(): String =
        accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw PersonalRouteApiException(PersonalRouteFailure.Unauthenticated)

    private suspend fun checked(response: HttpResponse): HttpResponse {
        if (response.status.isSuccess()) return response
        throw PersonalRouteApiException(failureOf(response.status, response.bodyAsText()))
    }

    private fun failureOf(
        status: HttpStatusCode,
        body: String,
    ): PersonalRouteFailure {
        val error = runCatching { errorJson.decodeFromString(PostgrestErrorDto.serializer(), body) }.getOrNull()
        val byMessage =
            when (error?.message) {
                "personal_route_unauthenticated" -> PersonalRouteFailure.Unauthenticated

                "personal_route_not_owner" -> PersonalRouteFailure.NotOwner

                "personal_route_revoked" -> PersonalRouteFailure.Revoked

                "personal_route_invalid_name" -> PersonalRouteFailure.InvalidName

                "personal_route_invalid_stop_count",
                "personal_route_duplicate_stop",
                -> PersonalRouteFailure.InvalidStops

                "personal_route_missing_location" -> PersonalRouteFailure.MissingLocation(idsFrom(error.details))

                "personal_route_unavailable_stops" -> PersonalRouteFailure.UnavailableStops(idsFrom(error.details))

                "personal_route_not_found" -> PersonalRouteFailure.NotFound

                "route_listing_requires_published" -> PersonalRouteFailure.ListingRequiresPublished

                "route_listing_invalid_transition" -> PersonalRouteFailure.ListingInvalidTransition

                "route_not_listed" -> PersonalRouteFailure.NotListed

                "route_report_exists" -> PersonalRouteFailure.ReportExists

                "route_report_own_route" -> PersonalRouteFailure.ReportOwnRoute

                else -> null
            }
        return byMessage ?: if (status == HttpStatusCode.Unauthorized) {
            PersonalRouteFailure.Unauthenticated
        } else {
            PersonalRouteFailure.Unexpected
        }
    }

    private fun idsFrom(details: String?): List<String> =
        runCatching {
            Json.parseToJsonElement(details.orEmpty()).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())

    private companion object {
        const val ROUTE_SELECT = "id,name,is_published,updated_at,personal_route_stops(*)"
    }
}
