package com.gallr.shared.data.network

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 089: authenticated calls to the route database functions (contracts/database-functions.md). */
class PersonalRouteApiClientTest {
    @Test
    fun saveSendsOnlyTheIdNameAndOrderedExhibitionIds() =
        runTest {
            lateinit var path: String
            lateinit var authorization: String
            lateinit var body: String
            val client =
                apiClient { request ->
                    path = request.url.encodedPath
                    authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    ok(ROUTE_JSON)
                }

            val saved = client.save(route())

            assertEquals("/rest/v1/rpc/save_personal_route", path)
            assertEquals("Bearer member-token", authorization)
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("p_id", "p_name", "p_exhibition_ids"), sent.keys)
            assertEquals("route-1", sent.getValue("p_id").jsonPrimitive.content)
            assertEquals(listOf("b", "a"), sent.getValue("p_exhibition_ids").jsonArray.map { it.jsonPrimitive.content })
            assertFalse(body.contains("name_ko"), "snapshot fields are never sent (E-D7)")
            assertEquals("종로 산책", saved.name)
            assertEquals(Instant.parse("2026-10-08T02:00:00Z"), saved.revision)
            assertEquals(listOf("b", "a"), saved.stops.map(PersonalRouteStop::exhibitionId))
            assertEquals("서버 이름", saved.stops.first().nameKo)
            assertEquals(GeoPoint(37.58, 126.98), saved.stops.first().point)
        }

    @Test
    fun aMissingSessionFailsBeforeAnyRequest() =
        runTest {
            val client =
                PersonalRouteApiClient(
                    client = HttpClient(MockEngine { error("network must not be called") }),
                    supabaseUrl = "https://example.supabase.co",
                    accessTokenProvider = { null },
                )

            val error = assertFailsWith<PersonalRouteApiException> { client.save(route()) }
            assertEquals(PersonalRouteFailure.Unauthenticated, error.failure)
        }

    @Test
    fun databaseErrorsBecomeTypedFailures() =
        runTest {
            suspend fun failureFor(
                status: HttpStatusCode,
                body: String,
            ): PersonalRouteFailure =
                assertFailsWith<PersonalRouteApiException> { apiClient { error(status, body) }.save(route()) }.failure

            assertEquals(
                PersonalRouteFailure.NotOwner,
                failureFor(HttpStatusCode.Forbidden, """{"code":"42501","message":"personal_route_not_owner"}"""),
            )
            // Expected outcomes arrive as PT409 (409) and PT404 (404); the mapping reads the message, never the code.
            assertEquals(
                PersonalRouteFailure.Revoked,
                failureFor(HttpStatusCode.Conflict, """{"code":"PT409","message":"personal_route_revoked"}"""),
            )
            assertEquals(
                PersonalRouteFailure.NotFound,
                failureFor(HttpStatusCode.NotFound, """{"code":"PT404","message":"personal_route_not_found"}"""),
            )
            assertEquals(
                PersonalRouteFailure.UnavailableStops(listOf("a", "c")),
                failureFor(
                    HttpStatusCode.BadRequest,
                    """{"code":"22023","message":"personal_route_unavailable_stops","details":"[\"a\", \"c\"]"}""",
                ),
            )
            assertEquals(
                PersonalRouteFailure.MissingLocation(listOf("x")),
                failureFor(
                    HttpStatusCode.BadRequest,
                    """{"code":"22023","message":"personal_route_missing_location","details":"[\"x\"]"}""",
                ),
            )
            assertEquals(
                PersonalRouteFailure.InvalidName,
                failureFor(HttpStatusCode.BadRequest, """{"code":"22023","message":"personal_route_invalid_name"}"""),
            )
            assertEquals(
                PersonalRouteFailure.InvalidStops,
                failureFor(
                    HttpStatusCode.BadRequest,
                    """{"code":"22023","message":"personal_route_invalid_stop_count"}""",
                ),
            )
            assertEquals(
                PersonalRouteFailure.Unauthenticated,
                failureFor(HttpStatusCode.Unauthorized, """{"code":"PGRST301","message":"JWT expired"}"""),
            )
            assertEquals(
                PersonalRouteFailure.Unexpected,
                failureFor(HttpStatusCode.InternalServerError, "not json"),
            )
        }

    @Test
    fun publishAndDeleteCallTheirFunctions() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                apiClient { request ->
                    paths += request.url.encodedPath
                    if (request.url.encodedPath.endsWith("delete_personal_route")) ok("null") else ok(ROUTE_JSON)
                }

            val published = client.publish("route-1")
            client.delete("route-1")

            assertTrue(published.isPublished)
            assertEquals(
                listOf("/rest/v1/rpc/publish_personal_route", "/rest/v1/rpc/delete_personal_route"),
                paths,
            )
        }

    @Test
    fun listMineMapsSummariesNewestFirst() =
        runTest {
            val client =
                apiClient {
                    ok(
                        """[
                        {"id":"r2","name":"둘","stop_count":3,"is_published":true,"revoked_at":null,
                         "updated_at":"2026-10-08T02:00:00Z"},
                        {"id":"r1","name":"하나","stop_count":2,"is_published":false,"revoked_at":"2026-10-07T00:00:00Z",
                         "updated_at":"2026-10-07T02:00:00Z"}
                        ]""",
                    )
                }

            val summaries = client.listMine()

            assertEquals(listOf("r2", "r1"), summaries.map { it.id })
            assertEquals(3, summaries.first().stopCount)
            assertTrue(summaries.first().isPublished)
            assertTrue(summaries.last().isRevoked)
        }

    @Test
    fun loadMineReadsTheRouteWithOrderedStops() =
        runTest {
            lateinit var method: HttpMethod
            lateinit var url: String
            val client =
                apiClient { request ->
                    method = request.method
                    url = request.url.toString()
                    ok("[$ROW_JSON]")
                }

            val route = client.loadMine("route-1")

            assertEquals(HttpMethod.Get, method)
            assertTrue(url.contains("/rest/v1/personal_routes"))
            assertTrue(url.contains("id=eq.route-1"))
            assertEquals(listOf("b", "a"), route.stops.map(PersonalRouteStop::exhibitionId))
            assertEquals(Instant.parse("2026-10-08T02:00:00Z"), route.revision)
        }

    private fun apiClient(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ) = PersonalRouteApiClient(
        client =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            coerceInputValues = true
                        },
                    )
                }
            },
        supabaseUrl = "https://example.supabase.co/",
        accessTokenProvider = { "member-token" },
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.error(
        status: HttpStatusCode,
        body: String,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun route() =
        PersonalRoute(
            id = "route-1",
            name = " 종로 산책 ",
            stops = listOf(stop("b"), stop("a")),
        )

    private fun stop(id: String) =
        PersonalRouteStop(
            exhibitionId = id,
            nameKo = id,
            nameEn = id,
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            point = GeoPoint(37.58, 126.98),
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            cityKo = "서울",
        )

    private companion object {
        const val STOPS_JSON =
            """[
            {"position":0,"exhibition_id":"b","name_ko":"서버 이름","name_en":"B","venue_name_ko":"갤러리",
             "venue_name_en":"Gallery","latitude":37.58,"longitude":126.98,"region_ko":"종로구",
             "region_en":"Jongno-gu","city_ko":"서울"},
            {"position":1,"exhibition_id":"a","name_ko":"A","name_en":"A","venue_name_ko":"갤러리",
             "venue_name_en":"Gallery","latitude":37.59,"longitude":126.99,"region_ko":"종로구",
             "region_en":"Jongno-gu","city_ko":"서울"}
            ]"""

        const val ROUTE_JSON =
            """{"id":"route-1","revision":"2026-10-08T02:00:00Z","name":"종로 산책","is_published":true,
            "published_at":"2026-10-08T02:00:00Z","revoked_at":null,"stops":$STOPS_JSON}"""

        const val ROW_JSON =
            """{"id":"route-1","name":"종로 산책","is_published":false,"updated_at":"2026-10-08T02:00:00Z",
            "revoked_at":null,"personal_route_stops":[
            {"position":1,"exhibition_id":"a","name_ko":"A","name_en":"A","venue_name_ko":"갤러리",
             "venue_name_en":"Gallery","latitude":37.59,"longitude":126.99,"region_ko":"종로구",
             "region_en":"Jongno-gu","city_ko":"서울"},
            {"position":0,"exhibition_id":"b","name_ko":"B","name_en":"B","venue_name_ko":"갤러리",
             "venue_name_en":"Gallery","latitude":37.58,"longitude":126.98,"region_ko":"종로구",
             "region_en":"Jongno-gu","city_ko":"서울"}]}"""
    }
}
