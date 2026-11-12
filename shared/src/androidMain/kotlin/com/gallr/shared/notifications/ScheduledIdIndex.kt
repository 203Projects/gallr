package com.gallr.shared.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The ids-only record before reminders were stored whole. Retired: alarms it lists are replaced in place when the next
 * sync schedules the same ids (same request code), so its entries are dropped on the first write.
 */
private val LEGACY_SCHEDULED_NOTIFICATION_IDS = stringSetPreferencesKey("scheduled_notification_ids")
private val SCHEDULED_REMINDERS = stringPreferencesKey("scheduled_reminders")

/**
 * Android-only record of the reminders this app has scheduled, whole (time, wording, deep link), because
 * AlarmManager cannot list its alarms. The record says what is owed, not what the system holds: alarms vanish on
 * reboot and force-stop while the record keeps them, so the scheduler checks each one against the system.
 * iOS reads from `UNUserNotificationCenter` directly and doesn't need this.
 */
class ScheduledIdIndex(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun getAll(): List<NotificationSpec> = StoredReminders.decode(dataStore.data.first()[SCHEDULED_REMINDERS])

    suspend fun put(spec: NotificationSpec) {
        update { reminders -> reminders.filterNot { it.id == spec.id } + spec }
    }

    suspend fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        update { reminders -> reminders.filterNot { it.id in ids } }
    }

    suspend fun clear() {
        update { emptyList() }
    }

    private suspend fun update(transform: (List<NotificationSpec>) -> List<NotificationSpec>) {
        dataStore.edit { prefs ->
            val reminders = transform(StoredReminders.decode(prefs[SCHEDULED_REMINDERS]))
            prefs[SCHEDULED_REMINDERS] = StoredReminders.encode(reminders)
            prefs.remove(LEGACY_SCHEDULED_NOTIFICATION_IDS)
        }
    }
}
