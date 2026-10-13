package com.gallr.shared.repository

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.model.GALLERY_IMAGE_LIMIT
import com.gallr.shared.data.network.CuratorNoteApi
import com.gallr.shared.data.network.dto.CuratorNotePage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CuratorNoteRepositoryImplTest {
    private val note =
        CuratorNote(
            exhibitionId = "a",
            versionNumber = 1,
            hookKo = "훅",
            hookEn = "",
            insightsKo = listOf("하나", "둘"),
            insightsEn = emptyList(),
            tipKo = "팁",
            tipEn = "",
            approvedAt = Instant.parse("2026-10-11T03:00:00Z"),
        )

    @Test
    fun `published notes come back as a success`() =
        runTest {
            val page = CuratorNotePage(listOf(note), skipped = 1)
            val repository = CuratorNoteRepositoryImpl(FakeCuratorNoteApi(page = page))

            val result = repository.getPublishedNotes()

            assertEquals(listOf(note), result.getOrThrow())
        }

    @Test
    fun `a client failure becomes a failed result`() =
        runTest {
            val repository = CuratorNoteRepositoryImpl(FakeCuratorNoteApi(failure = IllegalStateException("offline")))

            assertTrue(repository.getPublishedNotes().isFailure)
            assertTrue(repository.getNote("a").isFailure)
        }

    @Test
    fun `an exhibition without a note yields null`() =
        runTest {
            val repository = CuratorNoteRepositoryImpl(FakeCuratorNoteApi(page = CuratorNotePage(emptyList(), 0)))

            assertNull(repository.getNote("a").getOrThrow())
        }

    @Test
    fun `an exhibition with a note yields it`() =
        runTest {
            val repository = CuratorNoteRepositoryImpl(FakeCuratorNoteApi(page = CuratorNotePage(listOf(note), 0)))

            assertEquals(note, repository.getNote("a").getOrThrow())
        }

    @Test
    fun `an exhibition's images come back in order and capped at the gallery's size`() =
        runTest {
            val images =
                (1..GALLERY_IMAGE_LIMIT + 3).map { number ->
                    ExhibitionImage("https://cdn.example.invalid/$number.jpg", "", "", "", null, null)
                }
            val repository = CuratorNoteRepositoryImpl(FakeCuratorNoteApi(images = images))

            assertEquals(images.take(GALLERY_IMAGE_LIMIT), repository.getGalleryImages("a").getOrThrow())
        }

    @Test
    fun `a failed image read is a failed result`() =
        runTest {
            val api = FakeCuratorNoteApi(failure = IllegalStateException("offline"))

            assertTrue(CuratorNoteRepositoryImpl(api).getGalleryImages("a").isFailure)
        }

    private class FakeCuratorNoteApi(
        private val page: CuratorNotePage = CuratorNotePage(emptyList(), 0),
        private val failure: Throwable? = null,
        private val images: List<ExhibitionImage> = emptyList(),
    ) : CuratorNoteApi {
        override suspend fun fetchGalleryImages(exhibitionId: String): List<ExhibitionImage> {
            failure?.let { throw it }
            return images
        }

        override suspend fun fetchPublished(limit: Int): CuratorNotePage = answer()

        override suspend fun fetchForExhibition(exhibitionId: String): CuratorNotePage = answer()

        private fun answer(): CuratorNotePage {
            failure?.let { throw it }
            return page
        }
    }
}
