package com.gallr.shared.data.network

import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.MyGallrAccountMutation
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MyGallrAccountApiClientTest {
    @Test
    fun `sync sends authenticated ordered mutations and maps restored alerts off`() =
        runTest {
            lateinit var path: String
            lateinit var authorization: String
            lateinit var body: String
            val client =
                HttpClient(
                    MockEngine { request ->
                        path = request.url.encodedPath
                        authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                        body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        respond(
                            content = RESPONSE,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val source =
                MyGallrAccountApiClient(
                    client = client,
                    supabaseUrl = "https://example.supabase.co/",
                    accessTokenProvider = { "member-access-token" },
                )

            val archive =
                source.sync(
                    listOf(
                        MyGallrAccountMutation.AddVisit(MUTATION_ONE, visit()),
                        MyGallrAccountMutation.FollowGallery(MUTATION_TWO, gallery(alerts = true)),
                    ),
                )

            assertEquals("/rest/v1/rpc/sync_my_gallr_archive", path)
            assertEquals("Bearer member-access-token", authorization)
            assertTrue(body.indexOf(MUTATION_ONE) < body.indexOf(MUTATION_TWO))
            assertTrue(body.contains("\"kind\":\"add_visit\""))
            assertTrue(body.contains("\"kind\":\"follow_gallery\""))
            assertFalse(body.contains("new_exhibition_alerts_enabled"))
            assertEquals(7, archive.revision)
            assertEquals("exhibition-1", archive.visits.single().exhibitionId)
            assertFalse(archive.followedGalleries.single().newExhibitionAlertsEnabled)
        }

    @Test
    fun `sync rejects a missing authenticated session before network`() =
        runTest {
            val source =
                MyGallrAccountApiClient(
                    client = HttpClient(MockEngine { error("network must not be called") }),
                    supabaseUrl = "https://example.supabase.co",
                    accessTokenProvider = { null },
                )

            val result = runCatching { source.sync(emptyList()) }

            assertTrue(result.exceptionOrNull() is MyGallrAccountAuthenticationRequiredException)
        }

    private fun visit() =
        ExhibitionVisit(
            clientRecordId = "record-1",
            exhibitionId = "exhibition-1",
            snapshot =
                ExhibitionVisitSnapshot(
                    nameKo = "전시 하나",
                    nameEn = "Exhibition One",
                    venueNameKo = "갤러리 하나",
                    venueNameEn = "Gallery One",
                    openingDate = LocalDate(2026, 8, 1),
                    closingDate = LocalDate(2026, 8, 31),
                    coverImageUrl = "https://example.invalid/one.jpg",
                ),
            createdAt = Instant.parse("2026-08-14T00:00:00Z"),
        )

    private fun gallery(alerts: Boolean) =
        FollowedGallery(
            galleryKey = "갤러리 하나\u001fGallery One",
            galleryId = GALLERY_ID,
            snapshot =
                FollowedGallerySnapshot(
                    nameKo = "갤러리 하나",
                    nameEn = "Gallery One",
                    cityKo = "서울",
                    cityEn = "Seoul",
                    regionKo = "삼청",
                    regionEn = "Samcheong",
                ),
            knownExhibitionIds = setOf("exhibition-1"),
            followedAt = Instant.parse("2026-08-14T00:00:00Z"),
            newExhibitionAlertsEnabled = alerts,
        )

    private companion object {
        const val MUTATION_ONE = "c1000000-0000-4000-8000-000000000001"
        const val MUTATION_TWO = "c1000000-0000-4000-8000-000000000002"
        const val GALLERY_ID = "c2000000-0000-4000-8000-000000000001"
        val RESPONSE =
            """
            {
              "revision":7,
              "visits":[{
                "client_record_id":"record-1",
                "exhibition_id":"exhibition-1",
                "snapshot":{
                  "name_ko":"전시 하나","name_en":"Exhibition One",
                  "venue_name_ko":"갤러리 하나","venue_name_en":"Gallery One",
                  "opening_date":"2026-08-01","closing_date":"2026-08-31",
                  "cover_image_url":"https://example.invalid/one.jpg"
                },
                "created_at":"2026-08-14T00:00:00Z"
              }],
              "followed_galleries":[{
                "gallery_key":"갤러리 하나\\u001fGallery One",
                "gallery_id":"$GALLERY_ID",
                "snapshot":{
                  "name_ko":"갤러리 하나","name_en":"Gallery One",
                  "city_ko":"서울","city_en":"Seoul",
                  "region_ko":"삼청","region_en":"Samcheong"
                },
                "known_exhibition_ids":["exhibition-1"],
                "followed_at":"2026-08-14T00:00:00Z"
              }]
            }
            """.trimIndent()
    }
}
