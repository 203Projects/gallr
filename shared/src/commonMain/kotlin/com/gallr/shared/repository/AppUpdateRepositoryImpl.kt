package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.app.AppPlatform
import com.gallr.shared.app.AppVersion
import com.gallr.shared.app.UpdatePolicy
import com.gallr.shared.app.UpdateRequirement
import com.gallr.shared.app.updateRequirement
import com.gallr.shared.data.network.AppVersionPolicyApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Persisted as `minimum|recommended`; renaming the key or the format needs a migration. */
private val APP_VERSION_POLICY_KEY = stringPreferencesKey("app_version_policy")

/** Persisted as `yyyy-MM-dd`. */
private val APP_UPDATE_NUDGE_DISMISSED_KEY = stringPreferencesKey("app_update_nudge_dismissed")

/**
 * [AppUpdateRepository] over the preferences DataStore. An [installed] version that could not be read never gates
 * anyone: the app cannot judge a version it does not know.
 */
class AppUpdateRepositoryImpl(
    private val installed: AppVersion?,
    private val platform: AppPlatform,
    private val api: AppVersionPolicyApi,
    private val dataStore: DataStore<Preferences>,
) : AppUpdateRepository {
    override val requirement: Flow<UpdateRequirement> =
        dataStore.data.map { preferences ->
            val policy = preferences[APP_VERSION_POLICY_KEY]?.let(::decodePolicy)
            if (installed == null || policy == null) UpdateRequirement.NONE else updateRequirement(installed, policy)
        }

    override val nudgeDismissedOn: Flow<LocalDate?> =
        dataStore.data.map { preferences ->
            val stored = preferences[APP_UPDATE_NUDGE_DISMISSED_KEY] ?: return@map null
            runCatching { LocalDate.parse(stored) }.getOrNull()
        }

    override suspend fun refresh() {
        val policy = api.fetch(platform) ?: return
        dataStore.edit { preferences ->
            preferences[APP_VERSION_POLICY_KEY] = "${policy.minimum}$SEPARATOR${policy.recommended}"
        }
    }

    override suspend fun dismissNudge(today: LocalDate) {
        dataStore.edit { preferences -> preferences[APP_UPDATE_NUDGE_DISMISSED_KEY] = today.toString() }
    }

    private fun decodePolicy(stored: String): UpdatePolicy? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        val minimum = AppVersion.parse(parts[0]) ?: return null
        val recommended = AppVersion.parse(parts[1]) ?: return null
        return UpdatePolicy(minimum = minimum, recommended = recommended)
    }
}

private const val SEPARATOR = "|"
