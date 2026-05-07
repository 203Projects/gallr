package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val PERMISSION_PROMPTED = booleanPreferencesKey("notification_permission_prompted")

class NotificationPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    fun observePermissionPrompted(): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PERMISSION_PROMPTED] ?: false }

    suspend fun setPermissionPrompted() {
        dataStore.edit { prefs -> prefs[PERMISSION_PROMPTED] = true }
    }
}
