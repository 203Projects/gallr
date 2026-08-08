package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CachedExhibitionRepositoryTest {

    @Test
    fun exhibition_cache_model_round_trips_through_json() {
        val exhibition = exhibition("serialized", isFeatured = true)

        val encoded = Json.encodeToString(exhibition)

        assertEquals(exhibition, Json.decodeFromString<Exhibition>(encoded))
    }

    @Test
    fun successful_full_catalogue_is_persisted() = kotlinx.coroutines.test.runTest {
        val exhibitions = listOf(exhibition("remote", isFeatured = true))
        val cache = FakeExhibitionCache()
        val repository = CachedExhibitionRepository(
            remote = FakeExhibitionRepository(allResult = Result.success(exhibitions)),
            cache = cache,
        )

        assertEquals(exhibitions, repository.getExhibitions().getOrThrow())
        assertEquals(exhibitions, cache.exhibitions)
    }

    @Test
    fun cache_write_failure_does_not_hide_fresh_remote_catalogue() = kotlinx.coroutines.test.runTest {
        val exhibitions = listOf(exhibition("remote", isFeatured = true))
        val repository = CachedExhibitionRepository(
            remote = FakeExhibitionRepository(allResult = Result.success(exhibitions)),
            cache = FakeExhibitionCache(writeFailure = IllegalStateException("disk full")),
        )

        assertEquals(exhibitions, repository.getExhibitions().getOrThrow())
    }

    @Test
    fun successful_featured_response_does_not_replace_complete_cache() = kotlinx.coroutines.test.runTest {
        val completeCache = listOf(exhibition("cached regular", isFeatured = false))
        val featured = listOf(exhibition("remote featured", isFeatured = true))
        val cache = FakeExhibitionCache(completeCache)
        val repository = CachedExhibitionRepository(
            remote = FakeExhibitionRepository(featuredResult = Result.success(featured)),
            cache = cache,
        )

        assertEquals(featured, repository.getFeaturedExhibitions().getOrThrow())
        assertEquals(completeCache, cache.exhibitions)
    }

    @Test
    fun cached_catalogue_is_returned_when_remote_load_fails() = kotlinx.coroutines.test.runTest {
        val cached = listOf(exhibition("cached", isFeatured = false))
        val repository = CachedExhibitionRepository(
            remote = FakeExhibitionRepository(allResult = Result.failure(IllegalStateException("offline"))),
            cache = FakeExhibitionCache(cached),
        )

        assertEquals(cached, repository.getExhibitions().getOrThrow())
    }

    @Test
    fun featured_fallback_is_derived_from_verified_full_catalogue_cache() = kotlinx.coroutines.test.runTest {
        val featured = exhibition("featured", isFeatured = true)
        val regular = exhibition("regular", isFeatured = false)
        val repository = CachedExhibitionRepository(
            remote = FakeExhibitionRepository(
                featuredResult = Result.failure(IllegalStateException("offline")),
            ),
            cache = FakeExhibitionCache(listOf(featured, regular)),
        )

        assertEquals(listOf(featured), repository.getFeaturedExhibitions().getOrThrow())
    }

    @Test
    fun cache_read_failure_preserves_original_remote_failure() = kotlinx.coroutines.test.runTest {
        val remoteFailure = IllegalStateException("offline")
        val repository = CachedExhibitionRepository(
            remote = FakeExhibitionRepository(allResult = Result.failure(remoteFailure)),
            cache = FakeExhibitionCache(readFailure = IllegalArgumentException("corrupt cache")),
        )

        val result = repository.getExhibitions()

        assertTrue(result.isFailure)
        assertSame(remoteFailure, result.exceptionOrNull())
    }

    private class FakeExhibitionRepository(
        private val featuredResult: Result<List<Exhibition>> = Result.success(emptyList()),
        private val allResult: Result<List<Exhibition>> = Result.success(emptyList()),
    ) : ExhibitionRepository {
        override suspend fun getFeaturedExhibitions() = featuredResult
        override suspend fun getExhibitions() = allResult
    }

    private class FakeExhibitionCache(
        var exhibitions: List<Exhibition>? = null,
        private val readFailure: Throwable? = null,
        private val writeFailure: Throwable? = null,
    ) : ExhibitionCache {
        override suspend fun read(): List<Exhibition>? {
            readFailure?.let { throw it }
            return exhibitions
        }

        override suspend fun write(exhibitions: List<Exhibition>) {
            writeFailure?.let { throw it }
            this.exhibitions = exhibitions
        }
    }

    private fun exhibition(id: String, isFeatured: Boolean) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "venue",
        venueNameEn = "venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = isFeatured,
        latitude = 37.5,
        longitude = 127.0,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
