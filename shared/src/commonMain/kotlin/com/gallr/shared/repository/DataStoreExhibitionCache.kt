package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.ExhibitionCatalogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val LEGACY_EXHIBITION_CATALOG_CACHE_KEY =
    stringPreferencesKey("legacy_exhibition_catalog_cache_v1")
private val CANONICAL_EXHIBITION_CATALOG_CACHE_KEY =
    stringPreferencesKey("canonical_exhibition_catalog_cache_v1")

@Serializable
private data class ExhibitionCachePayload(
    val exhibitions: List<Exhibition>,
)

/** Source-isolated JSON catalogue cache backed by a dedicated preferences DataStore file. */
class DataStoreExhibitionCache(
    private val dataStore: DataStore<Preferences>,
    source: ExhibitionCatalogSource,
) : ExhibitionCache {
    private val cacheKey = when (source) {
        ExhibitionCatalogSource.LEGACY -> LEGACY_EXHIBITION_CATALOG_CACHE_KEY
        ExhibitionCatalogSource.CANONICAL_V2 -> CANONICAL_EXHIBITION_CATALOG_CACHE_KEY
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun read(): List<Exhibition>? {
        val encoded = dataStore.data.first()[cacheKey] ?: return null
        return withContext(Dispatchers.Default) {
            json.decodeFromString<ExhibitionCachePayload>(encoded).exhibitions
        }
    }

    override suspend fun write(exhibitions: List<Exhibition>) {
        val encoded = withContext(Dispatchers.Default) {
            json.encodeToString(ExhibitionCachePayload(exhibitions))
        }
        dataStore.edit { preferences -> preferences[cacheKey] = encoded }
    }
}
