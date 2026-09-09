package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.network.ExhibitionCatalogSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreExhibitionCacheTest {
    @Test
    fun catalogue_round_trips_through_preferences_datastore() =
        runTest {
            val cache =
                DataStoreExhibitionCache(
                    dataStore = InMemoryPreferencesDataStore(),
                    source = ExhibitionCatalogSource.LEGACY,
                )
            val exhibitions = listOf(exhibition("cached"))

            assertNull(cache.read())
            cache.write(exhibitions)

            assertEquals(exhibitions, cache.read())
        }

    @Test
    fun catalogue_sources_use_independent_cache_namespaces() =
        runTest {
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

    @Test
    fun legacy_cached_payload_without_country_decodes_as_Korea() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val encodedExhibition =
                Json
                    .encodeToString(exhibition("legacy-country"))
                    .replace(Regex(",?\\\"countryCode\\\":\\\"KR\\\""), "")
            val payload = "{\"exhibitions\":[$encodedExhibition]}"
            val key = stringPreferencesKey("legacy_exhibition_catalog_cache_v1")
            dataStore.updateData { preferences ->
                preferences.toMutablePreferences().apply { this[key] = payload }
            }

            val decoded =
                DataStoreExhibitionCache(
                    dataStore = dataStore,
                    source = ExhibitionCatalogSource.LEGACY,
                ).read().orEmpty()

            assertEquals("KR", decoded.single().countryCode)
            assertEquals(emptyList(), decoded.single().artists)
            assertEquals(emptyList(), decoded.single().artTerms)
        }

    @Test
    fun catalogue_cache_round_trips_structured_art_metadata() =
        runTest {
            val cache =
                DataStoreExhibitionCache(
                    dataStore = InMemoryPreferencesDataStore(),
                    source = ExhibitionCatalogSource.CANONICAL_V2,
                )
            val rich =
                exhibition("rich").copy(
                    artists = listOf(ExhibitionArtist("artist-one", "작가", "Artist")),
                    artTerms = listOf(ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist")),
                )

            cache.write(listOf(rich))

            assertEquals(listOf(rich), cache.read())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private fun exhibition(
        id: String,
        countryCode: String = "KR",
    ) = Exhibition(
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
        countryCode = countryCode,
    )
}
