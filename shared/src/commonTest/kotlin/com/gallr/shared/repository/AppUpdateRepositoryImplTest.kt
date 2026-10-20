package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.app.AppPlatform
import com.gallr.shared.app.AppVersion
import com.gallr.shared.app.UpdatePolicy
import com.gallr.shared.app.UpdateRequirement
import com.gallr.shared.data.network.AppVersionPolicyApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 098 FR-001, FR-005: the last successful read is the answer until a newer read replaces it. */
class AppUpdateRepositoryImplTest {
    private val installed = AppVersion(1, 14, 0)

    private fun repository(
        dataStore: DataStore<Preferences> = InMemoryPreferencesDataStore(),
        api: FakePolicyApi = FakePolicyApi(),
        installed: AppVersion? = this.installed,
    ) = AppUpdateRepositoryImpl(installed, AppPlatform.ANDROID, api, dataStore)

    @Test
    fun aFreshInstallRequiresNothing() =
        runTest {
            assertEquals(UpdateRequirement.NONE, repository().requirement.first())
        }

    @Test
    fun aSuccessfulReadIsStoredAndApplied() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val api = FakePolicyApi(UpdatePolicy(AppVersion(1, 15, 0), AppVersion(1, 15, 0)))
            val repository = repository(dataStore, api)

            repository.refresh()

            assertEquals(UpdateRequirement.REQUIRED, repository.requirement.first())
            assertEquals("1.15.0|1.15.0", dataStore.data.first()[stringPreferencesKey("app_version_policy")])
            assertEquals(listOf(AppPlatform.ANDROID), api.asked)
        }

    @Test
    fun aFailedReadKeepsTheLastAnswer() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val api = FakePolicyApi(UpdatePolicy(AppVersion(1, 15, 0), AppVersion(1, 15, 0)), null)
            val repository = repository(dataStore, api)
            repository.refresh()
            assertEquals(UpdateRequirement.REQUIRED, repository.requirement.first())

            repository.refresh()

            assertEquals(UpdateRequirement.REQUIRED, repository.requirement.first(), "offline keeps the gate")
            val relaunched = repository(dataStore, FakePolicyApi())
            assertEquals(UpdateRequirement.REQUIRED, relaunched.requirement.first(), "and so does a relaunch")
        }

    @Test
    fun aLaterReadLiftsTheGate() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val api =
                FakePolicyApi(
                    UpdatePolicy(AppVersion(1, 15, 0), AppVersion(1, 15, 0)),
                    UpdatePolicy(AppVersion(1, 13, 1), AppVersion(1, 13, 1)),
                )
            val repository = repository(dataStore, api)
            repository.refresh()
            repository.refresh()

            assertEquals(UpdateRequirement.NONE, repository.requirement.first())
        }

    @Test
    fun aStoredValueThatCannotBeReadRequiresNothing() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            dataStore.edit { it[stringPreferencesKey("app_version_policy")] = "garbage" }

            assertEquals(UpdateRequirement.NONE, repository(dataStore).requirement.first())
        }

    @Test
    fun anUnreadableInstalledVersionIsNeverGated() =
        runTest {
            val api = FakePolicyApi(UpdatePolicy(AppVersion(9, 0, 0), AppVersion(9, 0, 0)))
            val repository = repository(api = api, installed = null)
            repository.refresh()

            assertEquals(UpdateRequirement.NONE, repository.requirement.first())
        }

    @Test
    fun theNudgeDismissalIsADate() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val api = FakePolicyApi(UpdatePolicy(AppVersion(1, 13, 1), AppVersion(1, 15, 0)))
            val repository = repository(dataStore, api)
            repository.refresh()
            assertEquals(UpdateRequirement.RECOMMENDED, repository.requirement.first())
            assertNull(repository.nudgeDismissedOn.first())

            repository.dismissNudge(LocalDate(2026, 10, 20))

            assertEquals(LocalDate(2026, 10, 20), repository.nudgeDismissedOn.first())
            assertEquals("2026-10-20", dataStore.data.first()[stringPreferencesKey("app_update_nudge_dismissed")])
            dataStore.edit { it[stringPreferencesKey("app_update_nudge_dismissed")] = "yesterday" }
            assertNull(repository.nudgeDismissedOn.first(), "a malformed date reads as never dismissed")
        }

    private class FakePolicyApi(
        vararg answers: UpdatePolicy?,
    ) : AppVersionPolicyApi {
        private val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<AppPlatform>()

        override suspend fun fetch(platform: AppPlatform): UpdatePolicy? {
            asked += platform
            return queue.removeFirstOrNull()
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
