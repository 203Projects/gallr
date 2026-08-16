package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val EXHIBITION_VISITS_KEY = stringPreferencesKey("my_gallr_exhibition_visits_v1")
private const val VISIT_ARCHIVE_SCHEMA_VERSION = 1
private val visitArchiveLog = AppLog.tagged("DataStoreVisitRepository")

@Serializable
private data class VisitArchivePayload(
    val schemaVersion: Int = VISIT_ARCHIVE_SCHEMA_VERSION,
    val visits: List<ExhibitionVisit>,
)

class DataStoreVisitRepository(
    private val dataStore: DataStore<Preferences>,
) : VisitRepository {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        dataStore.data.map { preferences ->
            try {
                decode(preferences[EXHIBITION_VISITS_KEY]).sortedByDescending { it.createdAt }
            } catch (error: Exception) {
                visitArchiveLog.error("decode_visit_archive", error)
                throw error
            }
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        if (visits.isEmpty()) return

        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[EXHIBITION_VISITS_KEY])
                val existingIds = current.mapTo(mutableSetOf()) { it.exhibitionId }
                val additions = visits.filter { existingIds.add(it.exhibitionId) }
                preferences[EXHIBITION_VISITS_KEY] = encode(current + additions)
            }
        } catch (error: Exception) {
            visitArchiveLog.error("add_visits", error)
            throw error
        }
    }

    override suspend fun removeVisit(exhibitionId: String) {
        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[EXHIBITION_VISITS_KEY])
                preferences[EXHIBITION_VISITS_KEY] =
                    encode(current.filterNot { it.exhibitionId == exhibitionId })
            }
        } catch (error: Exception) {
            visitArchiveLog.error("remove_visit", error)
            throw error
        }
    }

    private fun decode(encoded: String?): List<ExhibitionVisit> {
        if (encoded == null) return emptyList()
        val payload = json.decodeFromString<VisitArchivePayload>(encoded)
        require(payload.schemaVersion == VISIT_ARCHIVE_SCHEMA_VERSION) {
            "Unsupported visit archive schema"
        }
        return payload.visits
    }

    private fun encode(visits: List<ExhibitionVisit>): String =
        json.encodeToString(VisitArchivePayload(visits = visits))
}
