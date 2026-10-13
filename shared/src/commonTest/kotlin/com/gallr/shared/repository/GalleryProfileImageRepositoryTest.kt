package com.gallr.shared.repository

import com.gallr.shared.data.network.GalleryProfileImageApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryProfileImageRepositoryTest {
    @Test
    fun `successful listing becomes a lookup`() =
        runTest {
            val repository =
                repository(
                    """[{"gallery_id":"g-1","name_ko":"국제갤러리","name_en":"Kukje Gallery","kind":"logo","storage_path":"g-1/a.jpg"}]""",
                )

            val images = repository.getProfileImages().getOrThrow()

            assertEquals("g-1", images.find(galleryId = null, nameKo = "국제갤러리", nameEn = "Kukje Gallery")?.galleryId)
        }

    @Test
    fun `server failure is contained in the result`() =
        runTest {
            val repository = repository("""{"message":"boom"}""", HttpStatusCode.InternalServerError)

            assertTrue(repository.getProfileImages().isFailure)
        }

    private fun repository(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): GalleryProfileImageRepository {
        val http =
            HttpClient(
                MockEngine {
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) {
                expectSuccess = true
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return GalleryProfileImageRepositoryImpl(GalleryProfileImageApiClient(http, "https://example.supabase.co"))
    }
}
