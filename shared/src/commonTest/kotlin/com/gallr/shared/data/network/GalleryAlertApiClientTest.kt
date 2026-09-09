package com.gallr.shared.data.network

import com.gallr.shared.data.model.RemotePushAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryAlertApiClientTest {
    @Test
    fun `register installation sends revision checked enrollment parameters`() =
        runTest {
            val request = captureRequest("""{"revision":4,"subscriptions":[]}""")
            val state =
                request.client.registerInstallation(
                    installationId = INSTALLATION_ID,
                    installationSecret = INSTALLATION_SECRET,
                    platform = "ios",
                    locale = "ko-KR",
                    expectedRevision = 3,
                )

            assertEquals(4, state.revision)
            assertEquals("/functions/v1/gallery-alert-enrollment", request.path)
            assertTrue(request.body.contains("\"action\":\"register_installation\""))
            assertTrue(request.body.contains("\"installation_id\":\"$INSTALLATION_ID\""))
            assertTrue(request.body.contains("\"installation_secret\":\"$INSTALLATION_SECRET\""))
            assertTrue(request.body.contains("\"expected_revision\":3"))
        }

    @Test
    fun `register push token sends provider address and maps state`() =
        runTest {
            val request = captureRequest("""{"push_token_revision":2,"push_token_status":"active"}""")
            val state =
                request.client.registerPushToken(
                    installationId = INSTALLATION_ID,
                    installationSecret = INSTALLATION_SECRET,
                    address =
                        RemotePushAddress(
                            platform = "android",
                            provider = "fcm",
                            token = "fcm-registration-token-value",
                            environment = "production",
                        ),
                    expectedRevision = 1,
                )

            assertEquals(GalleryAlertPushTokenState(2, "active"), state)
            assertEquals("/functions/v1/gallery-alert-enrollment", request.path)
            assertTrue(request.body.contains("\"action\":\"register_push_token\""))
            assertTrue(request.body.contains("\"provider\":\"fcm\""))
            assertTrue(request.body.contains("\"provider_token\":\"fcm-registration-token-value\""))
            assertTrue(request.body.contains("\"provider_environment\":\"production\""))
        }

    @Test
    fun `set subscription preserves server revision and gallery state`() =
        runTest {
            val request =
                captureRequest(
                    """{"revision":7,"subscriptions":[{"gallery_id":"$GALLERY_ID","enabled":true,"revision":5}]}""",
                )
            val state =
                request.client.setSubscription(
                    installationId = INSTALLATION_ID,
                    installationSecret = INSTALLATION_SECRET,
                    galleryId = GALLERY_ID,
                    enabled = true,
                    expectedRevision = 4,
                )

            assertEquals(7, state.revision)
            assertEquals(GalleryAlertSubscriptionState(GALLERY_ID, true, 5), state.subscriptions.single())
            assertEquals("/functions/v1/gallery-alert-enrollment", request.path)
            assertTrue(request.body.contains("\"action\":\"set_subscription\""))
            assertTrue(request.body.contains("\"gallery_id\":\"$GALLERY_ID\""))
            assertTrue(request.body.contains("\"enabled\":true"))
            assertTrue(request.body.contains("\"expected_revision\":4"))
        }

    @Test
    fun `authenticated registration forwards the member session`() =
        runTest {
            val request = captureRequest("""{"revision":1,"subscriptions":[]}""", "member-token")

            request.client.registerInstallation(
                installationId = INSTALLATION_ID,
                installationSecret = INSTALLATION_SECRET,
                platform = "android",
                locale = "en-US",
                expectedRevision = 0,
            )

            assertEquals("Bearer member-token", request.authorization)
        }

    @Test
    fun `signed out registration sends no member session`() =
        runTest {
            val request = captureRequest("""{"revision":1,"subscriptions":[]}""")

            request.client.registerInstallation(
                installationId = INSTALLATION_ID,
                installationSecret = INSTALLATION_SECRET,
                platform = "ios",
                locale = "ko-KR",
                expectedRevision = 0,
            )

            assertEquals("", request.authorization)
        }

    private fun captureRequest(
        responseBody: String,
        accessToken: String? = null,
    ): CapturedRequest {
        lateinit var captured: CapturedRequest
        val httpClient =
            HttpClient(
                MockEngine { request ->
                    captured.path = request.url.encodedPath
                    captured.authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                    captured.body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        content = responseBody,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        val api =
            GalleryAlertApiClient(
                httpClient,
                "https://example.supabase.co/",
                accessTokenProvider = { accessToken },
            )
        return CapturedRequest(api).also { captured = it }
    }

    private class CapturedRequest(
        val client: GalleryAlertCommandSource,
        var path: String = "",
        var body: String = "",
        var authorization: String = "",
    )

    private companion object {
        const val INSTALLATION_ID = "c1000000-0000-4000-8000-000000000001"
        const val INSTALLATION_SECRET = "ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss"
        const val GALLERY_ID = "c2000000-0000-4000-8000-000000000001"
    }
}
