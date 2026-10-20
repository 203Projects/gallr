package com.gallr.shared.data.network

import com.gallr.shared.app.AppPlatform
import com.gallr.shared.app.AppVersion
import com.gallr.shared.app.UpdatePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 098 contracts/app-version-policy.md: the app's side of the anonymous read. */
class AppVersionPolicyApiClientTest {
    @Test
    fun theRequestNamesThePlatform() {
        assertEquals("""{"p_platform":"android"}""", appVersionPolicyRequestBody(AppPlatform.ANDROID))
        assertEquals("""{"p_platform":"ios"}""", appVersionPolicyRequestBody(AppPlatform.IOS))
    }

    @Test
    fun aGoodAnswerIsAPolicy() {
        assertEquals(
            UpdatePolicy(AppVersion(1, 13, 1), AppVersion(1, 14, 0)),
            decodeUpdatePolicy("""{"minimum_version":"1.13.1","recommended_version":"1.14.0"}"""),
        )
    }

    @Test
    fun anythingElseIsAFailedRead() {
        assertNull(decodeUpdatePolicy("null"), "no row for the platform")
        assertNull(decodeUpdatePolicy("""{"minimum_version":"1.13.1"}"""), "missing field")
        assertNull(decodeUpdatePolicy("""{"minimum_version":"1.13","recommended_version":"1.14.0"}"""), "bad version")
        assertNull(decodeUpdatePolicy("""{"code":"PGRST202","message":"missing"}"""), "an error object")
        assertNull(decodeUpdatePolicy("not json"))
    }

    @Test
    fun theClientPostsToTheRpcAndReadsThePolicy() =
        runTest {
            var request: HttpRequestData? = null
            val client =
                apiClient { data ->
                    request = data
                    respond(
                        """{"minimum_version":"1.13.1","recommended_version":"1.14.0"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                }

            val policy = client.fetch(AppPlatform.IOS)

            assertEquals(UpdatePolicy(AppVersion(1, 13, 1), AppVersion(1, 14, 0)), policy)
            assertEquals("/rest/v1/rpc/get_app_version_policy", request?.url?.encodedPath)
            val sent = (request?.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            assertEquals("""{"p_platform":"ios"}""", sent)
        }

    @Test
    fun aServerErrorOrATransportFailureIsAFailedReadNotAnException() =
        runTest {
            val serverError = apiClient { respond("boom", HttpStatusCode.InternalServerError) }
            val transportFailure = apiClient { throw IllegalStateException("offline") }

            assertNull(serverError.fetch(AppPlatform.ANDROID))
            assertNull(transportFailure.fetch(AppPlatform.ANDROID))
        }

    private fun apiClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AppVersionPolicyApiClient(HttpClient(MockEngine { request -> handler(request) }), "https://example.supabase.co")
}
