package com.gallr.shared.repository

import androidx.datastore.core.DataStore
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
import kotlinx.serialization.json.jsonArray

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

    /**
     * Picks answer the sheet and clear an earlier skip. Clearing every pick is an answer too: it is kept as a
     * skip, so the sheet never opens by itself again for a visitor who emptied it on purpose.
     */
    override suspend fun saveSeed(seed: TasteSeed) {
        dataStore.edit { preferences ->
            if (seed.isEmpty) {
                preferences.remove(TASTE_SEED_KEY)
                preferences[TASTE_PICK_SKIPPED_KEY] = true
            } else {
                preferences[TASTE_SEED_KEY] = json.encodeToString(TasteSeed.serializer(), seed)
                preferences.remove(TASTE_PICK_SKIPPED_KEY)
            }
        }
    }

    override suspend fun markSkipped() {
        dataStore.edit { preferences -> preferences[TASTE_PICK_SKIPPED_KEY] = true }
    }

    override suspend fun dismiss(record: TasteDismissal) = updateDismissals { it.dismiss(record) }

    override suspend fun removeDismissals(exhibitionIds: Set<String>) {
        if (exhibitionIds.isEmpty()) return
        updateDismissals { it.remove(exhibitionIds) }
    }

    override suspend fun releaseTerm(termId: String) = updateDismissals { it.releaseTerm(termId) }

    override suspend fun expire(today: LocalDate) = updateDismissals { it.expire(today) }

    /**
     * Writes only a real change: an expiry or undo that finds nothing to do never rewrites what is stored, so a
     * value this build cannot read survives the launch-time expiry. Callers log failures with their own context.
     */
    private suspend fun updateDismissals(transform: (TasteDismissals) -> TasteDismissals) {
        dataStore.edit { preferences ->
            val current = decodeDismissals(preferences[TASTE_DISMISSALS_KEY])
            val next = transform(current)
            if (next == current) return@edit
            if (next.records.isEmpty()) {
                preferences.remove(TASTE_DISMISSALS_KEY)
            } else {
                preferences[TASTE_DISMISSALS_KEY] = json.encodeToString(DISMISSAL_LIST, next.records)
            }
        }
    }

    private fun decodeSeed(stored: String?): TasteSeed =
        stored?.let { decodeOrNull { json.decodeFromString(TasteSeed.serializer(), it) } } ?: TasteSeed.EMPTY

    /** Each record is read on its own, so one this build cannot read costs only itself, not every hide. */
    private fun decodeDismissals(stored: String?): TasteDismissals {
        val elements =
            stored?.let { decodeOrNull { json.parseToJsonElement(it).jsonArray } } ?: return TasteDismissals.EMPTY
        val records =
            elements.mapNotNull { element ->
                decodeOrNull { json.decodeFromJsonElement(TasteDismissal.serializer(), element) }
            }
        return TasteDismissals(records.sortedBy(TasteDismissal::exhibitionId))
    }

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
