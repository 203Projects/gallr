package com.gallr.shared.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

private val SCHEDULED_NOTIFICATION_IDS = stringSetPreferencesKey("scheduled_notification_ids")

/**
 * Android-only index of currently-scheduled notification IDs.
 * AlarmManager doesn't expose its scheduled-alarm list, so we maintain
 * our own index in DataStore. iOS reads from `UNUserNotificationCenter`
 * directly and doesn't need this.
 */
class ScheduledIdIndex(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun getAll(): Set<String> =
        dataStore.data.first()[SCHEDULED_NOTIFICATION_IDS] ?: emptySet()

    suspend fun add(id: String) {
        dataStore.edit { prefs ->
            prefs[SCHEDULED_NOTIFICATION_IDS] = (prefs[SCHEDULED_NOTIFICATION_IDS] ?: emptySet()) + id
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            prefs[SCHEDULED_NOTIFICATION_IDS] = (prefs[SCHEDULED_NOTIFICATION_IDS] ?: emptySet()) - id
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs[SCHEDULED_NOTIFICATION_IDS] = emptySet()
        }
    }
}
