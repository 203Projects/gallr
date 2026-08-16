package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.RemotePushAddress
import com.gallr.shared.data.network.GalleryAlertCommandSource
import com.gallr.shared.data.network.GalleryAlertInstallationState
import com.gallr.shared.data.network.GalleryAlertPushTokenState
import com.gallr.shared.data.network.GalleryAlertSubscriptionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GalleryAlertRegistrationRepositoryTest {
    @Test
    fun `enable registers installation token and explicit gallery subscription in order`() =
        runTest {
            val source = RecordingGalleryAlertSource()
            val repository = repository(source)

            repository
                .enableGallery(
                    galleryId = GALLERY_ID,
                    address = apnsAddress(),
                    locale = "ko-KR",
                ).getOrThrow()

            assertEquals(listOf("installation", "token", "subscription:true"), source.operations)
            assertEquals(0, source.installationExpectedRevision)
            assertEquals(0, source.tokenExpectedRevision)
            assertEquals(0, source.subscriptionExpectedRevision)
            assertEquals(GALLERY_ID, source.subscriptionGalleryId)
        }

    @Test
    fun `existing subscription revision is used for disable`() =
        runTest {
            val source =
                RecordingGalleryAlertSource(
                    installationState =
                        GalleryAlertInstallationState(
                            revision = 4,
                            subscriptions =
                                listOf(
                                    GalleryAlertSubscriptionState(
                                        galleryId = GALLERY_ID,
                                        enabled = true,
                                        revision = 3,
                                    ),
                                ),
                        ),
                )
            val repository = repository(source)

            repository
                .disableGallery(
                    galleryId = GALLERY_ID,
                    platform = "ios",
                    locale = "en-US",
                ).getOrThrow()

            assertEquals(listOf("installation", "subscription:false"), source.operations)
            assertEquals(3, source.subscriptionExpectedRevision)
        }

    @Test
    fun `installation credentials and remote revisions survive reconstruction`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val source = RecordingGalleryAlertSource()
            val first = repository(source, store)
            first.enableGallery(GALLERY_ID, apnsAddress(), "ko-KR").getOrThrow()

            val secondSource = RecordingGalleryAlertSource()
            val second = repository(secondSource, store)
            second.enableGallery(GALLERY_ID, apnsAddress(), "ko-KR").getOrThrow()

            assertEquals(source.installationId, secondSource.installationId)
            assertEquals(source.installationSecret, secondSource.installationSecret)
            assertEquals(1, secondSource.installationExpectedRevision)
            assertEquals(1, secondSource.tokenExpectedRevision)
            assertTrue(source.installationSecret.length >= 64)
        }

    @Test
    fun `remote failure is returned and stops later mutations`() =
        runTest {
            val source = RecordingGalleryAlertSource(failToken = true)
            val result = repository(source).enableGallery(GALLERY_ID, apnsAddress(), "ko-KR")

            assertTrue(result.isFailure)
            assertEquals(listOf("installation", "token"), source.operations)
        }

    @Test
    fun `invalid remote address is rejected before network work`() =
        runTest {
            val source = RecordingGalleryAlertSource()
            val repository = repository(source)

            assertFailsWith<IllegalArgumentException> {
                repository
                    .enableGallery(
                        GALLERY_ID,
                        apnsAddress().copy(token = "short"),
                        "ko-KR",
                    ).getOrThrow()
            }
            assertTrue(source.operations.isEmpty())
        }

    private fun repository(
        source: RecordingGalleryAlertSource,
        store: InMemoryPreferencesDataStore = InMemoryPreferencesDataStore(),
    ) = GalleryAlertRegistrationRepositoryImpl(
        source = source,
        stateStore =
            DataStoreGalleryAlertInstallationStateStore(
                dataStore = store,
                generateInstallationId = { "c1000000-0000-4000-8000-000000000001" },
                generateInstallationSecret = { "s".repeat(64) },
            ),
    )

    private fun apnsAddress() =
        RemotePushAddress(
            platform = "ios",
            provider = "apns",
            token = "a".repeat(64),
            environment = "sandbox",
        )

    private class RecordingGalleryAlertSource(
        private val installationState: GalleryAlertInstallationState =
            GalleryAlertInstallationState(revision = 1, subscriptions = emptyList()),
        private val failToken: Boolean = false,
    ) : GalleryAlertCommandSource {
        val operations = mutableListOf<String>()
        var installationId = ""
        var installationSecret = ""
        var installationExpectedRevision: Int? = null
        var tokenExpectedRevision: Int? = null
        var subscriptionExpectedRevision: Int? = null
        var subscriptionGalleryId = ""

        override suspend fun registerInstallation(
            installationId: String,
            installationSecret: String,
            platform: String,
            locale: String,
            expectedRevision: Int,
        ): GalleryAlertInstallationState {
            operations += "installation"
            this.installationId = installationId
            this.installationSecret = installationSecret
            installationExpectedRevision = expectedRevision
            return installationState
        }

        override suspend fun registerPushToken(
            installationId: String,
            installationSecret: String,
            address: RemotePushAddress,
            expectedRevision: Int,
        ): GalleryAlertPushTokenState {
            operations += "token"
            tokenExpectedRevision = expectedRevision
            if (failToken) error("provider unavailable")
            return GalleryAlertPushTokenState(revision = 1, status = "active")
        }

        override suspend fun setSubscription(
            installationId: String,
            installationSecret: String,
            galleryId: String,
            enabled: Boolean,
            expectedRevision: Int,
        ): GalleryAlertInstallationState {
            operations += "subscription:$enabled"
            subscriptionGalleryId = galleryId
            subscriptionExpectedRevision = expectedRevision
            return installationState.copy(
                subscriptions =
                    installationState.subscriptions
                        .filterNot { it.galleryId == galleryId } +
                        GalleryAlertSubscriptionState(
                            galleryId = galleryId,
                            enabled = enabled,
                            revision = expectedRevision + 1,
                        ),
            )
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private companion object {
        const val GALLERY_ID = "c2000000-0000-4000-8000-000000000001"
    }
}
