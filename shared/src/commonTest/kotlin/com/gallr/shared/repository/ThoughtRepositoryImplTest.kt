package com.gallr.shared.repository

import com.gallr.shared.thoughts.ThoughtReportReason
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 103 US4: 감상 are read and reported only through the reader RPCs (contracts/database.md M2). */
class ThoughtRepositoryImplTest {
    private var status = HttpStatusCode.OK
    private var responseBody = "[]"
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private val client =
        cloudBookmarkTestClient { request ->
            requests += request to request.bodyText()
            respond(responseBody, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    private val repository = ThoughtRepositoryImpl(client)

    @Test
    fun exhibitionThoughtsCallTheReaderRpcAndMapEveryField() =
        runTest {
            responseBody = LIST_RESPONSE

            val thoughts = repository.exhibitionThoughts("exhibition-1", limit = 20)

            val (request, body) = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/rest/v1/rpc/list_exhibition_thoughts", request.url.encodedPath)
            val arguments = Json.parseToJsonElement(body).jsonObject
            assertEquals("exhibition-1", arguments.getValue("p_exhibition_id").jsonPrimitive.content)
            assertEquals(20, arguments.getValue("p_limit").jsonPrimitive.int)

            assertEquals(listOf("t-1", "t-2"), thoughts.map { it.id })
            val first = thoughts[0]
            assertEquals("author-1", first.authorId)
            assertEquals("좋은 전시였어요", first.content)
            assertEquals(Instant.parse("2026-11-10T03:00:00Z"), first.createdAt)
            assertEquals("민지", first.authorDisplayName)
            assertEquals("https://example.supabase.co/avatar.png", first.authorAvatarUrl)
            assertTrue(first.reportedByMe)
            val second = thoughts[1]
            assertEquals("", second.authorDisplayName)
            assertNull(second.authorAvatarUrl)
            assertFalse(second.reportedByMe)
        }

    @Test
    fun aMalformedRowIsLeftOutAndTheRestIsKept() =
        runTest {
            responseBody =
                """[{"id":"t-1","content":"no author"},""" +
                """{"id":"t-2","user_id":"author-2","content":"ok","created_at":"2026-11-09T01:00:00+00:00",""" +
                """"author_display_name":"","author_avatar_url":null,"reported_by_me":false}]"""

            val thoughts = repository.exhibitionThoughts("exhibition-1")

            assertEquals(listOf("t-2"), thoughts.map { it.id })
        }

    @Test
    fun aFailedListIsThrownNotReadAsEmpty() =
        runTest {
            status = HttpStatusCode.InternalServerError
            responseBody = """{"code":"XX000","message":"boom"}"""

            assertFailsWith<Exception> { repository.exhibitionThoughts("exhibition-1") }
        }

    @Test
    fun reportCallsTheReportRpcWithTheReasonWire() =
        runTest {
            client.signIn("reader-1")
            status = HttpStatusCode.NoContent
            responseBody = ""

            repository.report("11111111-1111-1111-1111-111111111111", ThoughtReportReason.PROMOTIONAL)

            val (request, body) = requests.single()
            assertEquals("/rest/v1/rpc/report_thought", request.url.encodedPath)
            val arguments = Json.parseToJsonElement(body).jsonObject
            assertEquals("11111111-1111-1111-1111-111111111111", arguments.getValue("p_id").jsonPrimitive.content)
            assertEquals("promotional", arguments.getValue("p_reason").jsonPrimitive.content)
        }

    @Test
    fun aSecondReportOfTheSameTextIsAlreadySent() =
        runTest {
            client.signIn("reader-1")
            status = HttpStatusCode.Conflict
            responseBody = """{"code":"PT409","message":"thought_report_exists","details":null,"hint":null}"""

            assertFailsWith<ThoughtReportAlreadySentException> {
                repository.report("t-1", ThoughtReportReason.OTHER)
            }
        }

    @Test
    fun anUnauthenticatedReportAsksForSignIn() =
        runTest {
            status = HttpStatusCode.Forbidden
            responseBody = """{"code":"42501","message":"thought_report_unauthenticated","details":null,"hint":null}"""

            assertFailsWith<ThoughtReportSignInRequiredException> {
                repository.report("t-1", ThoughtReportReason.OTHER)
            }
        }

    @Test
    fun aThoughtNoLongerPublicIsItsOwnFailure() =
        runTest {
            client.signIn("reader-1")
            status = HttpStatusCode.Conflict
            responseBody = """{"code":"PT409","message":"thought_not_public","details":null,"hint":null}"""

            assertFailsWith<ThoughtNoLongerPublicException> {
                repository.report("t-1", ThoughtReportReason.OTHER)
            }
        }

    @Test
    fun anyOtherFailureIsThrownAsIs() =
        runTest {
            client.signIn("reader-1")
            status = HttpStatusCode.InternalServerError
            responseBody = """{"code":"XX000","message":"boom","details":null,"hint":null}"""

            val error = assertFailsWith<Exception> { repository.report("t-1", ThoughtReportReason.OTHER) }
            assertFalse(error is ThoughtReportAlreadySentException)
            assertFalse(error is ThoughtReportSignInRequiredException)
            assertFalse(error is ThoughtNoLongerPublicException)
        }

    private suspend fun HttpRequestData.bodyText(): String =
        when (val content = body) {
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> ""
        }

    private companion object {
        val LIST_RESPONSE =
            """
            [
              {"id":"t-1","user_id":"author-1","content":"좋은 전시였어요","created_at":"2026-11-10T03:00:00+00:00",
               "updated_at":"2026-11-10T03:00:00+00:00","author_display_name":"민지",
               "author_avatar_url":"https://example.supabase.co/avatar.png","reported_by_me":true},
              {"id":"t-2","user_id":"author-2","content":"조용했어요","created_at":"2026-11-09T01:00:00.123456+00:00",
               "updated_at":"2026-11-09T01:00:00.123456+00:00","author_display_name":"",
               "author_avatar_url":null,"reported_by_me":false}
            ]
            """.trimIndent()
    }
}
