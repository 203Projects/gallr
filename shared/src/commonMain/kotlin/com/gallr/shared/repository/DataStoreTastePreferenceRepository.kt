package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.observability.AppLog
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TasteDismissals
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON [TasteSeed]; renaming the key or the shape needs a migration. */
private val TASTE_SEED_KEY = stringPreferencesKey("taste_seed")

/** True once the visitor skipped the taste sheet. */
private val TASTE_PICK_SKIPPED_KEY = booleanPreferencesKey("taste_pick_skipped")

/** JSON list of [TasteDismissal]; renaming the key or the shape needs a migration. */
private val TASTE_DISMISSALS_KEY = stringPreferencesKey("taste_dismissals")

private val tasteLog = AppLog.tagged("TastePreferences")

/** [TastePreferenceRepository] in the app's preferences DataStore. */
class DataStoreTastePreferenceRepository(
    private val dataStore: DataStore<Preferences>,
) : TastePreferenceRepository {
    private val json =
        Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

    override val state: Flow<TastePickState> =
        dataStore.data.map { preferences ->
            TastePickState(
                seed = decodeSeed(preferences[TASTE_SEED_KEY]),
                skipped = preferences[TASTE_PICK_SKIPPED_KEY] ?: false,
                dismissals = decodeDismissals(preferences[TASTE_DISMISSALS_KEY]),
            )
        }

    override suspend fun saveSeed(seed: TasteSeed) =
        write("taste_seed_save") { preferences ->
            preferences[TASTE_SEED_KEY] = json.encodeToString(TasteSeed.serializer(), seed)
            preferences.remove(TASTE_PICK_SKIPPED_KEY)
        }

    override suspend fun markSkipped() =
        write("taste_pick_skip") { preferences ->
            preferences[TASTE_PICK_SKIPPED_KEY] =
                true
        }

    override suspend fun dismiss(record: TasteDismissal) = updateDismissals("taste_dismiss") { it.dismiss(record) }

    override suspend fun removeDismissals(exhibitionIds: Set<String>) {
        if (exhibitionIds.isEmpty()) return
        updateDismissals("taste_dismissal_remove") { it.remove(exhibitionIds) }
    }

    override suspend fun releaseTerm(termId: String) = updateDismissals("taste_term_release") { it.releaseTerm(termId) }

    override suspend fun expire(today: LocalDate) = updateDismissals("taste_dismissal_expire") { it.expire(today) }

    private suspend fun updateDismissals(
        operation: String,
        transform: (TasteDismissals) -> TasteDismissals,
    ) = write(operation) { preferences ->
        val current = decodeDismissals(preferences[TASTE_DISMISSALS_KEY])
        val next = transform(current)
        if (next.records.isEmpty()) {
            preferences.remove(TASTE_DISMISSALS_KEY)
        } else {
            preferences[TASTE_DISMISSALS_KEY] = json.encodeToString(DISMISSAL_LIST, next.records)
        }
    }

    private suspend fun write(
        operation: String,
        block: (MutablePreferences) -> Unit,
    ) {
        try {
            dataStore.edit(block)
        } catch (error: Exception) {
            tasteLog.error("${operation}_failed", error)
            throw error
        }
    }

    private fun decodeSeed(stored: String?): TasteSeed =
        stored?.let { decodeOrNull { json.decodeFromString(TasteSeed.serializer(), it) } } ?: TasteSeed.EMPTY

    private fun decodeDismissals(stored: String?): TasteDismissals =
        stored?.let {
            decodeOrNull {
                TasteDismissals(
                    json.decodeFromString(DISMISSAL_LIST, it).sortedBy(TasteDismissal::exhibitionId),
                )
            }
        }
            ?: TasteDismissals.EMPTY

    private fun <T> decodeOrNull(decode: () -> T): T? =
        try {
            decode()
        } catch (error: SerializationException) {
            tasteLog.warn("taste_preference_read_failed", error)
            null
        } catch (error: IllegalArgumentException) {
            tasteLog.warn("taste_preference_read_failed", error)
            null
        }
}

private val DISMISSAL_LIST = kotlinx.serialization.builtins.ListSerializer(TasteDismissal.serializer())
