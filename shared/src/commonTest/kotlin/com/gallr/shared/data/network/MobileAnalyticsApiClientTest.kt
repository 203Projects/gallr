package com.gallr.shared.data.network

import com.gallr.shared.analytics.AnalyticsEntryPoint
import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.MobileAnalyticsBatch
import com.gallr.shared.analytics.MobileAnalyticsEvent
import com.gallr.shared.analytics.MobileAnalyticsSink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileAnalyticsApiClientTest {
    @Test
    fun `lazy sink does not construct its client until first delivery`() =
        runTest {
            var constructions = 0
            val sink =
                lazyMobileAnalyticsSink {
                    constructions += 1
                    MobileAnalyticsSink { }
                }

            assertEquals(0, constructions)
            sink.deliver(MobileAnalyticsBatch(listOf(event())))
            sink.deliver(MobileAnalyticsBatch(listOf(event())))
            assertEquals(1, constructions)
        }

    @Test
    fun `delivery uses anonymous closed batch contract`() =
        runTest {
            lateinit var captured: CapturedRequest
            val httpClient =
                HttpClient(
                    MockEngine { request ->
                        captured =
                            CapturedRequest(
                                path = request.url.encodedPath,
                                origin = request.headers[HttpHeaders.Origin].orEmpty(),
                                authorization = request.headers[HttpHeaders.Authorization],
                                cookie = request.headers[HttpHeaders.Cookie],
                                apiKey = request.headers["apikey"],
                                body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                            )
                        respond(content = "", status = HttpStatusCode.NoContent)
                    },
                ) {
                    install(ContentNegotiation) { json(Json) }
                }
            val client = MobileAnalyticsApiClient(httpClient, "https://example.supabase.co/")

            client.deliver(MobileAnalyticsBatch(listOf(event())))

            assertEquals("/functions/v1/mobile-analytics", captured.path)
            assertEquals("app://gallr", captured.origin)
            assertEquals(null, captured.authorization)
            assertEquals(null, captured.cookie)
            assertEquals(null, captured.apiKey)
            assertTrue("\"event_name\":\"surface_viewed\"" in captured.body)
            assertFalse("user_id" in captured.body)
            assertFalse("session_id" in captured.body)
        }

    @Test
    fun `non no-content response remains a delivery failure`() =
        runTest {
            val client =
                MobileAnalyticsApiClient(
                    HttpClient(MockEngine { respond("upstream detail", HttpStatusCode.ServiceUnavailable) }) {
                        install(ContentNegotiation) { json(Json) }
                    },
                    "https://example.supabase.co",
                )

            assertFailsWith<IllegalStateException> {
                client.deliver(MobileAnalyticsBatch(listOf(event())))
            }
        }

    private fun event() =
        MobileAnalyticsEvent.surfaceViewed(
            eventId = "a4000000-0000-4000-8000-000000000001",
            occurredOn = LocalDate(2026, 8, 30),
            platform = AnalyticsPlatform.ANDROID,
            appMajor = 1,
            surface = AnalyticsSurface.FEATURED,
            entryPoint = AnalyticsEntryPoint.TAB,
        )

    private data class CapturedRequest(
        val path: String,
        val origin: String,
        val authorization: String?,
        val cookie: String?,
        val apiKey: String?,
        val body: String,
    )
}
