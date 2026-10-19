package com.gallr.shared.data.network

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteWaypoint
import com.gallr.shared.map.WalkingRouter
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Spec 093: the app's side of contracts/walking-route.md. */
class WalkingRouteApiClientTest {
    private val stops =
        listOf(
            RouteWaypoint("ex-1", GeoPoint(37.5445, 127.056)),
            RouteWaypoint("ex-2", GeoPoint(37.5345, 127.001)),
            RouteWaypoint("ex-3", GeoPoint(37.534, 126.994)),
        )

    @Test
    fun venuesAreSentToTheFunctionAsTheAppOrigin() =
        runTest {
            lateinit var request: HttpRequestData
            val client =
                apiClient { sent ->
                    request = sent
                    ok(
                        """{"legs":[{"status":"routed","distance_m":5230,"polyline":"_p~iF~ps|U"},
                        {"status":"unavailable"}]}""",
                    )
                }

            client.legs(stops)

            assertEquals("/functions/v1/walking-route", request.url.encodedPath)
            assertEquals("app://gallr", request.headers[HttpHeaders.Origin])
            assertNull(request.headers[HttpHeaders.Authorization], "no account token is needed or sent")
            val waypoints = bodyOf(request).getValue("waypoints").jsonArray.map { it.jsonObject }
            val ids = waypoints.map { it.getValue("exhibition_id").jsonPrimitive.content }
            assertEquals(listOf("ex-1", "ex-2", "ex-3"), ids)
            assertEquals("37.5445", waypoints[0].getValue("lat").jsonPrimitive.content)
            assertEquals("127.056", waypoints[0].getValue("lng").jsonPrimitive.content)
        }

    @Test
    fun legsComeBackRoutedOrUnavailableInOrder() =
        runTest {
            val client =
                apiClient {
                    ok(
                        """{"legs":[{"status":"routed","distance_m":5230,"polyline":"_p~iF~ps|U","provider":"osrm"},
                        {"status":"unavailable"},{"status":"routed"},{"status":"teleported"}]}""",
                    )
                }

            val fiveStops =
                stops +
                    RouteWaypoint("ex-4", GeoPoint(37.53, 126.99)) +
                    RouteWaypoint("ex-5", GeoPoint(37.52, 126.98))

            val legs = client.legs(fiveStops)

            assertEquals(4, legs.size)
            val routed = assertIs<WalkingLegResult.Routed>(legs[0])
            assertEquals(5_230, routed.distanceMeters)
            assertEquals(listOf(GeoPoint(38.5, -120.2)), routed.geometry)
            assertEquals(WalkingRouter.OSRM, routed.router)
            assertIs<WalkingLegResult.Unavailable>(legs[1])
            assertIs<WalkingLegResult.Unavailable>(legs[2], "a routed leg without its distance is unusable")
            assertIs<WalkingLegResult.Unavailable>(legs[3], "an unknown status is unusable")
        }

    @Test
    fun aRoutedLegWithAnUnknownOrMissingRouterIsStillAPathWithNoRouter() =
        runTest {
            val client =
                apiClient {
                    ok(
                        """{"legs":[{"status":"routed","distance_m":100,"polyline":"_p~iF~ps|U","provider":"pigeon"},
                        {"status":"routed","distance_m":100,"polyline":"_p~iF~ps|U"}]}""",
                    )
                }

            val legs = client.legs(stops)

            assertEquals(listOf<WalkingRouter?>(null, null), legs.map { (it as WalkingLegResult.Routed).router })
        }

    @Test
    fun aRefusedRequestIsAFailure() =
        runTest {
            val client = apiClient { respond("", HttpStatusCode.TooManyRequests) }

            assertFailsWith<IllegalStateException> { client.legs(stops) }
        }

    @Test
    fun aPlaceThatIsNotAVenueIsRefusedBeforeSending() =
        runTest {
            val client = apiClient { error("network must not be called") }

            assertFailsWith<IllegalArgumentException> {
                client.legs(listOf(RouteWaypoint(null, GeoPoint(37.55, 127.0))) + stops)
            }
        }

    private fun apiClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        WalkingRouteApiClient(
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
        )

    private fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
