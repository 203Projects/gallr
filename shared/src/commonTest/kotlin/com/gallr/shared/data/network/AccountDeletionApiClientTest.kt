package com.gallr.shared.data.network

import com.gallr.shared.repository.AccountDeletionAuthenticationRequiredException
import com.gallr.shared.repository.AccountDeletionRateLimitedException
import com.gallr.shared.repository.AccountDeletionReauthenticationRequiredException
import com.gallr.shared.repository.AccountDeletionStatusUnknownException
import com.gallr.shared.repository.AccountDeletionSupportRequiredException
import com.gallr.shared.repository.AccountDeletionUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class AccountDeletionApiClientTest {
    @Test
    fun confirmed_deletion_requires_the_stable_success_contract() =
        runTest {
            var authorization: String? = null
            var requestPath: String? = null
            val client =
                client(
                    status = HttpStatusCode.OK,
                    body = """{"status":"deleted","request_id":"00000000-0000-0000-0000-000000000001"}""",
                ) { request ->
                    authorization = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                }

            AccountDeletionApiClient(client, "https://example.supabase.co/")
                .deleteAccount("access-token")

            assertEquals("Bearer access-token", authorization)
            assertEquals("/functions/v1/delete-account", requestPath)
        }

    @Test
    fun server_codes_map_to_typed_sanitized_failures() =
        runTest {
            val cases =
                listOf(
                    Triple(
                        HttpStatusCode.Unauthorized,
                        "authentication_required",
                        AccountDeletionAuthenticationRequiredException::class,
                    ),
                    Triple(
                        HttpStatusCode.Conflict,
                        "reauthentication_required",
                        AccountDeletionReauthenticationRequiredException::class,
                    ),
                    Triple(HttpStatusCode.Conflict, "support_required", AccountDeletionSupportRequiredException::class),
                    Triple(HttpStatusCode.TooManyRequests, "rate_limited", AccountDeletionRateLimitedException::class),
                    Triple(
                        HttpStatusCode.ServiceUnavailable,
                        "deletion_status_unknown",
                        AccountDeletionStatusUnknownException::class,
                    ),
                )

            cases.forEach { (status, code, expectedType) ->
                val api =
                    AccountDeletionApiClient(
                        client(status, """{"error":{"code":"$code","message":"detail"}}"""),
                        "https://example.supabase.co",
                    )
                val failure = assertFails { api.deleteAccount("token") }
                assertEquals(expectedType, failure::class)
            }
        }

    @Test
    fun malformed_success_and_unknown_errors_fail_closed() =
        runTest {
            val malformed =
                AccountDeletionApiClient(
                    client(HttpStatusCode.OK, """{"status":"deleted"}"""),
                    "https://example.supabase.co",
                )
            assertFailsWith<AccountDeletionUnavailableException> {
                malformed.deleteAccount("token")
            }

            val unknown =
                AccountDeletionApiClient(
                    client(HttpStatusCode.BadGateway, "upstream detail"),
                    "https://example.supabase.co",
                )
            assertFailsWith<AccountDeletionUnavailableException> {
                unknown.deleteAccount("token")
            }
        }

    @Test
    fun blank_access_token_is_rejected_before_network_io() =
        runTest {
            val api =
                AccountDeletionApiClient(
                    HttpClient(MockEngine { error("network must not be called") }),
                    "https://example.supabase.co",
                )
            assertFailsWith<AccountDeletionAuthenticationRequiredException> {
                api.deleteAccount(" ")
            }
        }

    private fun client(
        status: HttpStatusCode,
        body: String,
        inspect: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                inspect(request)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
}
