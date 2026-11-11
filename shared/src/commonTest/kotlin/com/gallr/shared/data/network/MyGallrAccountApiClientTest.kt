package com.gallr.shared.data.network

import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.MyGallrAccountMutation
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.ThoughtRejectionReason
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `a 1_15 archive without diary keys decodes to empty diary fields and no public notes`() =
        runTest {
            val archive = syncAnswering(RESPONSE).sync(emptyList())

            val visit = archive.visits.single()
            assertNull(visit.visitDate)
            assertNull(visit.feeling)
            assertTrue(visit.tags.isEmpty())
            assertNull(visit.note)
            assertNull(visit.entryUpdatedAt)
            assertTrue(archive.publicNotes.isEmpty())
        }

    @Test
    fun `each diary mutation is sent with the record contracts database md names`() =
        runTest {
            lateinit var body: String
            val entry =
                visit().copy(
                    visitDate = LocalDate(2026, 8, 13),
                    feelingWire = VisitFeeling.NOT_FOR_ME.wire,
                    tagWires = listOf(VisitTag.CALM_TO_LINGER.wire, VisitTag.SEE_AGAIN.wire),
                    note = "메모",
                    entryUpdatedAt = Instant.parse("2026-08-15T01:02:03Z"),
                )
            val legacyGuest = visit()

            syncAnswering(RESPONSE) { body = it }.sync(
                listOf(
                    MyGallrAccountMutation.UpdateVisit("m-1", entry),
                    MyGallrAccountMutation.MergeVisit("m-2", legacyGuest),
                    MyGallrAccountMutation.DeleteEntry("m-3", "exhibition-1"),
                    MyGallrAccountMutation.PublishNote("m-4", "exhibition-1"),
                    MyGallrAccountMutation.UnpublishNote("m-5", "exhibition-1"),
                    MyGallrAccountMutation.AcknowledgeNoteApproval("m-6", "exhibition-1"),
                ),
            )

            val mutations =
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("p_mutations")
                    .jsonArray
            assertEquals(
                listOf(
                    "update_visit",
                    "merge_visit",
                    "delete_entry",
                    "publish_note",
                    "unpublish_note",
                    "acknowledge_note_approval",
                ),
                mutations.map {
                    it.jsonObject
                        .getValue("kind")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                (1..6).map { "m-$it" },
                mutations.map {
                    it.jsonObject
                        .getValue("mutation_id")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {
                      "client_record_id":"record-1","exhibition_id":"exhibition-1",
                      "created_at":"2026-08-14T00:00:00Z",
                      "snapshot":{
                        "name_ko":"전시 하나","name_en":"Exhibition One",
                        "venue_name_ko":"갤러리 하나","venue_name_en":"Gallery One",
                        "opening_date":"2026-08-01","closing_date":"2026-08-31",
                        "cover_image_url":"https://example.invalid/one.jpg"
                      },
                      "visit_date":"2026-08-13","feeling":"not_for_me","tags":["calm_to_linger","see_again"],
                      "note":"메모","entry_updated_at":"2026-08-15T01:02:03Z"
                    }
                    """.trimIndent(),
                ),
                mutations[0].jsonObject.getValue("record"),
            )
            val mergeRecord = mutations[1].jsonObject.getValue("record").jsonObject
            assertEquals(JsonNull, mergeRecord.getValue("visit_date"))
            assertEquals(JsonNull, mergeRecord.getValue("feeling"))
            assertEquals(JsonArray(emptyList()), mergeRecord.getValue("tags"))
            assertEquals(JsonNull, mergeRecord.getValue("note"))
            assertEquals(JsonNull, mergeRecord.getValue("entry_updated_at"))
            mutations.drop(2).forEach { mutation ->
                assertEquals(
                    Json.parseToJsonElement("""{"exhibition_id":"exhibition-1"}"""),
                    mutation.jsonObject.getValue("record"),
                )
            }
        }

    @Test
    fun `add visit keeps the 1_15 record without diary keys`() =
        runTest {
            lateinit var body: String

            syncAnswering(RESPONSE) { body = it }.sync(listOf(MyGallrAccountMutation.AddVisit("m-1", visit())))

            val record =
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("p_mutations")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("record")
                    .jsonObject
            assertEquals(setOf("client_record_id", "exhibition_id", "created_at", "snapshot"), record.keys)
        }

    @Test
    fun `an archive with diary keys and public notes decodes`() =
        runTest {
            val archive = syncAnswering(DIARY_RESPONSE).sync(emptyList())

            val visit = archive.visits.single()
            assertEquals(LocalDate(2026, 8, 13), visit.visitDate)
            assertEquals(VisitFeeling.LOVED, visit.feeling)
            assertEquals(listOf(VisitTag.THE_WORK, VisitTag.PLAYFUL), visit.tags)
            assertEquals("계정 메모", visit.note)
            assertEquals(Instant.parse("2026-08-15T00:00:00Z"), visit.entryUpdatedAt)
            assertEquals(
                listOf(
                    OwnPublicNote(
                        exhibitionId = "exhibition-1",
                        thoughtId = "c3000000-0000-4000-8000-000000000001",
                        content = "공개 감상",
                        moderation = PublicNoteModeration.APPROVED,
                        rejectionReason = null,
                        requestedAt = Instant.parse("2026-08-15T00:00:00Z"),
                        approvedAt = Instant.parse("2026-08-16T00:00:00Z"),
                        approvalSeen = false,
                    ),
                    OwnPublicNote(
                        exhibitionId = "legacy-show",
                        thoughtId = "c3000000-0000-4000-8000-000000000002",
                        content = "반려된 감상",
                        moderation = PublicNoteModeration.REJECTED,
                        rejectionReason = ThoughtRejectionReason.PROMOTIONAL,
                        requestedAt = Instant.parse("2026-08-10T00:00:00Z"),
                        approvedAt = null,
                        approvalSeen = true,
                    ),
                ),
                archive.publicNotes,
            )
        }

    @Test
    fun `a public note with an unknown status reads as in review and a malformed one is left out`() =
        runTest {
            val archive = syncAnswering(UNKNOWN_NOTE_RESPONSE).sync(emptyList())

            assertEquals(listOf("future-status"), archive.publicNotes.map { it.exhibitionId })
            assertEquals(PublicNoteModeration.PENDING, archive.publicNotes.single().moderation)
            assertNull(archive.publicNotes.single().rejectionReason)
            assertEquals("exhibition-1", archive.visits.single().exhibitionId)
        }

    private fun syncAnswering(
        response: String,
        onBody: (String) -> Unit = {},
    ) = MyGallrAccountApiClient(
        client =
            HttpClient(
                MockEngine { request ->
                    onBody((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    respond(content = response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            coerceInputValues = true
                        },
                    )
                }
            },
        supabaseUrl = "https://example.supabase.co",
        accessTokenProvider = { "member-access-token" },
    )

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

        val DIARY_RESPONSE =
            """
            {
              "revision":8,
              "visits":[{
                "client_record_id":"record-1",
                "exhibition_id":"exhibition-1",
                "snapshot":{
                  "name_ko":"전시 하나","name_en":"Exhibition One",
                  "venue_name_ko":"갤러리 하나","venue_name_en":"Gallery One",
                  "opening_date":"2026-08-01","closing_date":"2026-08-31",
                  "cover_image_url":null
                },
                "created_at":"2026-08-14T00:00:00Z",
                "visit_date":"2026-08-13","feeling":"loved","tags":["the_work","playful"],
                "note":"계정 메모","entry_updated_at":"2026-08-15T00:00:00+00:00"
              }],
              "followed_galleries":[],
              "public_notes":[
                {
                  "exhibition_id":"exhibition-1","thought_id":"c3000000-0000-4000-8000-000000000001",
                  "content":"공개 감상","status":"approved","rejection_reason":null,
                  "requested_at":"2026-08-15T00:00:00+00:00","decided_at":"2026-08-16T00:00:00+00:00",
                  "approved_at":"2026-08-16T00:00:00+00:00","approval_seen":false
                },
                {
                  "exhibition_id":"legacy-show","thought_id":"c3000000-0000-4000-8000-000000000002",
                  "content":"반려된 감상","status":"rejected","rejection_reason":"promotional",
                  "requested_at":"2026-08-10T00:00:00+00:00","decided_at":"2026-08-11T00:00:00+00:00",
                  "approved_at":null,"approval_seen":true
                }
              ]
            }
            """.trimIndent()

        val UNKNOWN_NOTE_RESPONSE =
            """
            {
              "revision":9,
              "visits":[{
                "client_record_id":"record-1",
                "exhibition_id":"exhibition-1",
                "snapshot":{
                  "name_ko":"전시 하나","name_en":"Exhibition One",
                  "venue_name_ko":"갤러리 하나","venue_name_en":"Gallery One",
                  "opening_date":"2026-08-01","closing_date":"2026-08-31"
                },
                "created_at":"2026-08-14T00:00:00Z",
                "tags":null
              }],
              "public_notes":[
                {
                  "exhibition_id":"future-status","thought_id":"c3000000-0000-4000-8000-000000000003",
                  "content":"감상","status":"escalated","rejection_reason":"a_new_reason",
                  "requested_at":"2026-08-15T00:00:00Z"
                },
                {
                  "exhibition_id":"malformed","thought_id":"c3000000-0000-4000-8000-000000000004",
                  "content":"감상","status":"approved","requested_at":"not-a-time"
                }
              ]
            }
            """.trimIndent()
    }
}
