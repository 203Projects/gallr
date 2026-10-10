package com.gallr.shared.data.network

import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.RouteDeclineReason
import com.gallr.shared.route.RouteListingBlocker
import com.gallr.shared.route.RouteListingState
import com.gallr.shared.route.RouteReportReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 089 public routes: listing, the public list, copies and reports (contracts/public-routes-functions.md). */
class PublicRouteApiClientTest {
    @Test
    fun myRoutesCarryTheirListingFields() =
        runTest {
            val client =
                apiClient {
                    ok(
                        """[
                        {"id":"r1","name":"하나","stop_count":2,"is_published":true,"revoked_at":null,
                         "updated_at":"2026-10-08T02:00:00Z","listing_state":"declined",
                         "listing_decline_reason":"composition","listing_decline_note":"줄여 주세요",
                         "author_is_editor":false,"listing_blocker":"declined"},
                        {"id":"r2","name":"둘","stop_count":3,"is_published":true,"revoked_at":null,
                         "updated_at":"2026-10-07T02:00:00Z","listing_state":"approved","listing_decline_reason":null,
                         "listing_decline_note":null,"author_is_editor":true,"listing_blocker":"ended_stop"},
                        {"id":"r3","name":"셋","stop_count":2,"is_published":false,"revoked_at":null,
                         "updated_at":"2026-10-06T02:00:00Z"}
                        ]""",
                    )
                }

            val (declined, approved, older) = client.listMine()

            assertEquals(RouteListingState.Declined, declined.listingState)
            assertEquals(RouteDeclineReason.Composition, declined.declineReason)
            assertEquals("줄여 주세요", declined.declineNote)
            assertEquals(RouteListingBlocker.Declined, declined.listingBlocker)
            assertFalse(declined.authorIsEditor)
            assertEquals(RouteListingState.Approved, approved.listingState)
            assertEquals(RouteListingBlocker.EndedStop, approved.listingBlocker)
            assertTrue(approved.authorIsEditor)
            assertEquals(RouteListingState.Unlisted, older.listingState, "rows without listing fields read as unlisted")
            assertNull(older.listingBlocker)
        }

    @Test
    fun requestAndWithdrawCallTheirFunctionsWithTheRouteId() =
        runTest {
            val calls = mutableListOf<Pair<String, JsonObject>>()
            val client =
                apiClient { request ->
                    calls += request.url.encodedPath to bodyOf(request)
                    val requesting = request.url.encodedPath.endsWith("request_route_listing")
                    val state = if (requesting) "requested" else "unlisted"
                    ok(
                        """{"id":"r1","name":"하나","stop_count":2,"is_published":true,"revoked_at":null,
                        "updated_at":"2026-10-08T02:00:00Z","listing_state":"$state","author_is_editor":false}""",
                    )
                }

            assertEquals(RouteListingState.Requested, client.requestListing("r1").listingState)
            assertEquals(RouteListingState.Unlisted, client.withdrawListing("r1").listingState)
            assertEquals(
                listOf("/rest/v1/rpc/request_route_listing", "/rest/v1/rpc/withdraw_route_listing"),
                calls.map { it.first },
            )
            val ids = calls.map { (_, sent) -> sent.getValue("p_id").jsonPrimitive.content }
            assertEquals(listOf("r1", "r1"), ids)
        }

    @Test
    fun thePublicListIsReadWithoutAnAccountAndSkipsMalformedRows() =
        runTest {
            lateinit var path: String
            lateinit var body: JsonObject
            var authorization: String? = "unset"
            val client =
                apiClient(token = null) { request ->
                    path = request.url.encodedPath
                    body = bodyOf(request)
                    authorization = request.headers[HttpHeaders.Authorization]
                    ok("[$PUBLIC_ROW, {\"id\":\"broken\"}]")
                }

            val routes = client.listPublic(limit = 10)

            assertEquals("/rest/v1/rpc/list_public_routes", path)
            assertEquals("10", body.getValue("p_limit").jsonPrimitive.content)
            assertNull(authorization, "signed-out reads send only the publishable key")
            val route = routes.single()
            assertEquals("p1", route.id)
            assertEquals("한남 산책", route.name)
            assertEquals(4, route.stopCount)
            assertEquals("한남동", route.firstDistrictKo)
            assertEquals("Itaewon-dong", route.lastDistrictEn)
            assertEquals("에디터", route.authorDisplayName)
            assertTrue(route.isEditor)
            assertEquals(12, route.copyCount30d)
            assertEquals(LocalDate(2026, 10, 12), route.firstSharedDay)
            assertEquals(Instant.parse("2026-10-07T00:00:00Z"), route.approvedAt)
        }

    @Test
    fun anEmptyAuthorNameReadsAsNone() =
        runTest {
            val client = apiClient { ok("[${PUBLIC_ROW.replace("\"에디터\"", "\"\"")}]") }

            assertNull(client.listPublic(limit = 10).single().authorDisplayName)
        }

    @Test
    fun aListedRoutesStopsAreReadByIdEvenWhenSignedOut() =
        runTest {
            lateinit var body: JsonObject
            val client =
                apiClient(token = null) { request ->
                    body = bodyOf(request)
                    if (request.url.encodedPath.endsWith("get_published_route")) ok(PUBLISHED_ROUTE) else ok("null")
                }

            val loaded = requireNotNull(client.loadPublicStops("p1"))
            val route = loaded.route
            assertEquals("o1", loaded.ownerId)

            assertEquals("p1", body.getValue("p_id").jsonPrimitive.content)
            assertEquals(listOf("b", "a"), route.stops.map(PersonalRouteStop::exhibitionId))
            assertTrue(route.isPublished)
            assertEquals(Instant.parse("2026-10-08T02:00:00Z"), route.revision)
        }

    @Test
    fun aRouteThatIsNotShownReadsAsNothing() =
        runTest {
            val client = apiClient(token = null) { ok("null") }

            assertNull(client.loadPublicStops("gone"))
        }

    @Test
    fun copyingNeedsAnAccountAndReturnsTheRoute() =
        runTest {
            val signedOut =
                PersonalRouteApiClient(
                    client = HttpClient(MockEngine { error("network must not be called") }),
                    supabaseUrl = "https://example.supabase.co",
                    accessTokenProvider = { null },
                )
            assertEquals(
                PersonalRouteFailure.Unauthenticated,
                assertFailsWith<PersonalRouteApiException> { signedOut.copyPublic("p1") }.failure,
            )

            lateinit var path: String
            val client =
                apiClient { request ->
                    path = request.url.encodedPath
                    ok(PUBLISHED_ROUTE)
                }
            val copied = client.copyPublic("p1")

            assertEquals("/rest/v1/rpc/save_public_route", path)
            assertEquals("한남 산책", copied.name)
            assertEquals(2, copied.stops.size)
        }

    @Test
    fun reportsSendTheReasonsWireValue() =
        runTest {
            lateinit var body: JsonObject
            lateinit var path: String
            val client =
                apiClient { request ->
                    path = request.url.encodedPath
                    body = bodyOf(request)
                    ok("null")
                }

            client.report("p1", RouteReportReason.WrongInformation)

            assertEquals("/rest/v1/rpc/report_route", path)
            assertEquals("p1", body.getValue("p_id").jsonPrimitive.content)
            assertEquals("wrong_information", body.getValue("p_reason").jsonPrimitive.content)
        }

    @Test
    fun publicRouteErrorsBecomeTypedFailures() =
        runTest {
            suspend fun failureFor(message: String): PersonalRouteFailure =
                assertFailsWith<PersonalRouteApiException> {
                    apiClient { respondError("""{"code":"55000","message":"$message"}""") }.requestListing("r1")
                }.failure

            assertEquals(PersonalRouteFailure.ListingRequiresPublished, failureFor("route_listing_requires_published"))
            assertEquals(PersonalRouteFailure.ListingInvalidTransition, failureFor("route_listing_invalid_transition"))
            assertEquals(PersonalRouteFailure.NotListed, failureFor("route_not_listed"))
            assertEquals(PersonalRouteFailure.ReportExists, failureFor("route_report_exists"))
            assertEquals(PersonalRouteFailure.ReportOwnRoute, failureFor("route_report_own_route"))
        }

    private fun apiClient(
        token: String? = "member-token",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
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
        accessTokenProvider = { token },
    )

    private fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.respondError(body: String) =
        respond(body, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val PUBLIC_ROW =
            """{"id":"p1","name":"한남 산책","stop_count":4,"first_district_ko":"한남동","first_district_en":"Hannam-dong",
            "last_district_ko":"이태원동","last_district_en":"Itaewon-dong","author_display_name":"에디터","is_editor":true,
            "copy_count_30d":12,"first_shared_day":"2026-10-12","listing_decided_at":"2026-10-07T00:00:00Z"}"""

        const val PUBLISHED_ROUTE =
            """{"id":"p1","name":"한남 산책","owner":"o1","updated_at":"2026-10-08T02:00:00Z","stops":[
            {"position":1,"exhibition_id":"a","name_ko":"A","name_en":"A","venue_name_ko":"갤러리",
             "venue_name_en":"Gallery","latitude":37.59,"longitude":126.99,"region_ko":"한남동",
             "region_en":"Hannam-dong","city_ko":"서울"},
            {"position":0,"exhibition_id":"b","name_ko":"B","name_en":"B","venue_name_ko":"갤러리",
             "venue_name_en":"Gallery","latitude":37.58,"longitude":126.98,"region_ko":"이태원동",
             "region_en":"Itaewon-dong","city_ko":"서울"}]}"""
    }
}
