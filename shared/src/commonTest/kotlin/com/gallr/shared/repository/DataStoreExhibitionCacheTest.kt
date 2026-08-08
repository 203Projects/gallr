package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.ExhibitionCatalogSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreExhibitionCacheTest {
    @Test
    fun catalogue_round_trips_through_preferences_datastore() = runTest {
        val cache = DataStoreExhibitionCache(
            dataStore = InMemoryPreferencesDataStore(),
            source = ExhibitionCatalogSource.LEGACY,
        )
        val exhibitions = listOf(exhibition("cached"))

        assertNull(cache.read())
        cache.write(exhibitions)

        assertEquals(exhibitions, cache.read())
    }

    @Test
    fun catalogue_sources_use_independent_cache_namespaces() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val legacy = DataStoreExhibitionCache(dataStore, ExhibitionCatalogSource.LEGACY)
        val canonical = DataStoreExhibitionCache(dataStore, ExhibitionCatalogSource.CANONICAL_V2)
        val legacyExhibitions = listOf(exhibition("legacy"))
        val canonicalExhibitions = listOf(exhibition("canonical"))

        legacy.write(legacyExhibitions)
        assertNull(canonical.read())

        canonical.write(canonicalExhibitions)

        assertEquals(legacyExhibitions, legacy.read())
        assertEquals(canonicalExhibitions, canonical.read())
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    private fun exhibition(id: String) = Exhibition(
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
        isFeatured = false,
        latitude = 37.5,
        longitude = 127.0,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
