package com.gallr.shared.data.network

import com.gallr.shared.data.model.GalleryProfileImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GalleryProfileImageApiClientTest {
    @Test
    fun `lists profile images through the public rpc and builds storage urls`() =
        runTest {
            var requestedPath = ""
            var requestedMethod: HttpMethod? = null
            val client =
                apiClient(
                    """
                    [
                      {"gallery_id":"g-1","name_ko":"국제갤러리","name_en":"Kukje Gallery","kind":"logo",
                       "storage_path":"g-1/abc.jpg","credit":null},
                      {"gallery_id":"g-2","name_ko":"일민미술관","name_en":"Ilmin Museum of Art","kind":"photo",
                       "storage_path":"g-2/def.jpg","credit":"Jane Doe, CC BY-SA 4.0"}
                    ]
                    """.trimIndent(),
                ) { path, method ->
                    requestedPath = path
                    requestedMethod = method
                }

            val images = client.fetchProfileImages()

            assertEquals("/rest/v1/rpc/list_gallery_profile_images", requestedPath)
            assertEquals(HttpMethod.Post, requestedMethod)
            assertEquals(
                listOf(
                    GalleryProfileImage(
                        galleryId = "g-1",
                        nameKo = "국제갤러리",
                        nameEn = "Kukje Gallery",
                        kind = GalleryProfileImage.Kind.LOGO,
                        imageUrl = "$SUPABASE_URL/storage/v1/object/public/gallery-profile-images/g-1/abc.jpg",
                        credit = null,
                    ),
                    GalleryProfileImage(
                        galleryId = "g-2",
                        nameKo = "일민미술관",
                        nameEn = "Ilmin Museum of Art",
                        kind = GalleryProfileImage.Kind.PHOTO,
                        imageUrl = "$SUPABASE_URL/storage/v1/object/public/gallery-profile-images/g-2/def.jpg",
                        credit = "Jane Doe, CC BY-SA 4.0",
                    ),
                ),
                images,
            )
        }

    @Test
    fun `malformed rows are dropped without failing the list`() =
        runTest {
            val client =
                apiClient(
                    """
                    [
                      {"gallery_id":"g-1","name_ko":"A","name_en":"A","kind":"banner","storage_path":"g-1/a.jpg"},
                      {"gallery_id":"g-2","name_ko":"B","name_en":"B","kind":"logo","storage_path":" "},
                      {"gallery_id":"","name_ko":"C","name_en":"C","kind":"logo","storage_path":"c/c.jpg"},
                      {"gallery_id":"g-4","name_ko":"D","name_en":"D","kind":"photo","storage_path":"g-4/d.jpg","credit":"  "}
                    ]
                    """.trimIndent(),
                )

            val images = client.fetchProfileImages()

            assertEquals(listOf("g-4"), images.map { it.galleryId })
            assertNull(images.single().credit)
        }

    private fun apiClient(
        body: String,
        onRequest: (String, HttpMethod) -> Unit = { _, _ -> },
    ): GalleryProfileImageApiClient {
        val engine =
            MockEngine { request ->
                onRequest(request.url.encodedPath, request.method)
                respond(
                    content = body,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val http =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            coerceInputValues = true
                        },
                    )
                }
            }
        return GalleryProfileImageApiClient(client = http, supabaseUrl = SUPABASE_URL)
    }

    private companion object {
        const val SUPABASE_URL = "https://example.supabase.co"
    }
}
