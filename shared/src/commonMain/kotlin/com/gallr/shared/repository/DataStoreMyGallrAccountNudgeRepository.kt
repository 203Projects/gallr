package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val ACCOUNT_NUDGE_DISMISSED_KEY =
    booleanPreferencesKey("my_gallr_account_backup_nudge_dismissed_v2")
private val accountNudgeLog = AppLog.tagged("DataStoreMyGallrAccountNudgeRepository")

class DataStoreMyGallrAccountNudgeRepository(
    private val dataStore: DataStore<Preferences>,
) : MyGallrAccountNudgeRepository {
    override fun observeDismissed(): Flow<Boolean> =
        dataStore.data
            .map { preferences -> preferences[ACCOUNT_NUDGE_DISMISSED_KEY] ?: false }
            .catch { error ->
                accountNudgeLog.error("observe_dismissal", error)
                throw error
            }

    override suspend fun dismiss() {
        try {
            dataStore.edit { preferences -> preferences[ACCOUNT_NUDGE_DISMISSED_KEY] = true }
        } catch (error: Exception) {
            accountNudgeLog.error("dismiss", error)
            throw error
        }
    }
}
