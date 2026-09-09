package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface AnalyticsPreferenceRepository {
    fun observeEnabled(): Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}

private val MOBILE_ANALYTICS_ENABLED_KEY = booleanPreferencesKey("mobile_analytics_enabled_v1")

/** Device-local opt-in preference. It is not synced to an account. */
class DataStoreAnalyticsPreferenceRepository(
    private val dataStore: DataStore<Preferences>,
) : AnalyticsPreferenceRepository {
    override fun observeEnabled(): Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[MOBILE_ANALYTICS_ENABLED_KEY] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[MOBILE_ANALYTICS_ENABLED_KEY] = enabled }
    }
}
