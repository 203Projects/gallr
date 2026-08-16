package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.MyGallrAccountArchive
import com.gallr.shared.data.model.MyGallrAccountMutation
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MyGallrAccountSyncTest {
    @Test
    fun `sign in merges guest records preserves current device consent and clears guest archive`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val guestVisits = DataStoreVisitRepository(dataStore)
            val guestGalleries = DataStoreFollowedGalleryRepository(dataStore)
            guestVisits.addVisits(listOf(visit("guest")))
            guestGalleries.followGalleries(listOf(gallery(alerts = true)))
            val source = InMemorySource(MyGallrAccountArchive(1, listOf(visit("cloud")), emptyList()))
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    guestVisitRepository = guestVisits,
                    guestFollowedGalleryRepository = guestGalleries,
                    accountStore = DataStoreMyGallrAccountStore(dataStore),
                    source = source,
                    generateMutationId = sequentialIds(),
                )

            coordinator.activateAccount("member-one")
            val restored = coordinator.observeActiveArchive().first()!!

            assertEquals(setOf("guest", "cloud"), restored.visits.mapTo(mutableSetOf()) { it.exhibitionId })
            assertTrue(restored.followedGalleries.single().newExhibitionAlertsEnabled)
            assertTrue(guestVisits.observeVisits().first().isEmpty())
            assertTrue(guestGalleries.observeFollowedGalleries().first().isEmpty())
            assertEquals(MyGallrSyncStatus.SYNCED, coordinator.observeStatus().first())
        }

    @Test
    fun `failed account mutation remains visible and durable for retry`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val accountStore = DataStoreMyGallrAccountStore(dataStore)
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    guestVisitRepository = DataStoreVisitRepository(dataStore),
                    guestFollowedGalleryRepository = DataStoreFollowedGalleryRepository(dataStore),
                    accountStore = accountStore,
                    source = FailingSource,
                    generateMutationId = sequentialIds(),
                )
            coordinator.activateAccount("member-one")

            coordinator.addVisits(listOf(visit("offline")))

            assertEquals(
                "offline",
                coordinator
                    .observeActiveArchive()
                    .first()!!
                    .visits
                    .single()
                    .exhibitionId,
            )
            assertEquals(1, accountStore.get("member-one").pendingMutations.size)
            assertEquals(MyGallrSyncStatus.RETRY_NEEDED, coordinator.observeStatus().first())
            val reconstructed = DataStoreMyGallrAccountStore(dataStore)
            assertEquals(
                "offline",
                reconstructed
                    .get("member-one")
                    .visits
                    .single()
                    .exhibitionId,
            )
            assertEquals(1, reconstructed.get("member-one").pendingMutations.size)
        }

    @Test
    fun `account store isolates cached records by user`() =
        runTest {
            val store = DataStoreMyGallrAccountStore(InMemoryPreferencesDataStore())
            store.update("member-one") { it.copy(visits = listOf(visit("private"))) }

            assertEquals(
                "private",
                store
                    .get("member-one")
                    .visits
                    .single()
                    .exhibitionId,
            )
            assertTrue(store.get("member-two").visits.isEmpty())
        }

    @Test
    fun `restored gallery defaults alerts off without local consent`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val source = InMemorySource(MyGallrAccountArchive(3, emptyList(), listOf(gallery(alerts = false))))
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    DataStoreVisitRepository(dataStore),
                    DataStoreFollowedGalleryRepository(dataStore),
                    DataStoreMyGallrAccountStore(dataStore),
                    source,
                    sequentialIds(),
                )

            coordinator.activateAccount("member-one")

            assertFalse(
                coordinator
                    .observeActiveArchive()
                    .first()!!
                    .followedGalleries
                    .single()
                    .newExhibitionAlertsEnabled,
            )
        }

    @Test
    fun `refresh applies a remote removal without resurrecting stale account cache`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val source = InMemorySource(MyGallrAccountArchive(1, listOf(visit("remote")), emptyList()))
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    DataStoreVisitRepository(dataStore),
                    DataStoreFollowedGalleryRepository(dataStore),
                    DataStoreMyGallrAccountStore(dataStore),
                    source,
                    sequentialIds(),
                )
            coordinator.activateAccount("member-one")
            assertEquals(
                "remote",
                coordinator
                    .observeActiveArchive()
                    .first()!!
                    .visits
                    .single()
                    .exhibitionId,
            )

            source.removeRemotely("remote")
            coordinator.refresh()

            assertTrue(
                coordinator
                    .observeActiveArchive()
                    .first()!!
                    .visits
                    .isEmpty(),
            )
        }

    private class InMemorySource(
        initial: MyGallrAccountArchive,
    ) : MyGallrAccountCommandSource {
        private var archive = initial

        fun removeRemotely(exhibitionId: String) {
            archive =
                archive.copy(
                    revision = archive.revision + 1,
                    visits = archive.visits.filterNot { it.exhibitionId == exhibitionId },
                )
        }

        override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive {
            mutations.forEach { mutation ->
                archive =
                    when (mutation) {
                        is MyGallrAccountMutation.AddVisit -> {
                            if (archive.visits.any { it.exhibitionId == mutation.visit.exhibitionId }) {
                                archive
                            } else {
                                archive.copy(
                                    revision = archive.revision + 1,
                                    visits = archive.visits + mutation.visit,
                                )
                            }
                        }

                        is MyGallrAccountMutation.RemoveVisit -> {
                            archive.copy(
                                revision = archive.revision + 1,
                                visits = archive.visits.filterNot { it.exhibitionId == mutation.exhibitionId },
                            )
                        }

                        is MyGallrAccountMutation.FollowGallery -> {
                            archive.copy(
                                revision = archive.revision + 1,
                                followedGalleries =
                                    archive.followedGalleries.filterNot {
                                        it.galleryKey == mutation.gallery.galleryKey
                                    } + mutation.gallery.copy(newExhibitionAlertsEnabled = false),
                            )
                        }

                        is MyGallrAccountMutation.UnfollowGallery -> {
                            archive.copy(
                                revision = archive.revision + 1,
                                followedGalleries =
                                    archive.followedGalleries.filterNot {
                                        it.galleryKey ==
                                            mutation.galleryKey
                                    },
                            )
                        }

                        is MyGallrAccountMutation.AcknowledgeGallery -> {
                            archive.copy(revision = archive.revision + 1)
                        }
                    }
            }
            return archive
        }
    }

    private data object FailingSource : MyGallrAccountCommandSource {
        override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive = error("offline")
    }

    private fun sequentialIds(): () -> String {
        var next = 1
        return {
            "c1000000-0000-4000-8000-${next++.toString().padStart(12, '0')}"
        }
    }

    private fun visit(id: String) =
        ExhibitionVisit(
            clientRecordId = "record-$id",
            exhibitionId = id,
            snapshot =
                ExhibitionVisitSnapshot(
                    nameKo = "전시 $id",
                    nameEn = "Exhibition $id",
                    venueNameKo = "갤러리",
                    venueNameEn = "Gallery",
                    openingDate = LocalDate(2026, 8, 1),
                    closingDate = LocalDate(2026, 8, 31),
                    coverImageUrl = null,
                ),
            createdAt = Instant.parse("2026-08-14T00:00:00Z"),
        )

    private fun gallery(alerts: Boolean) =
        FollowedGallery(
            galleryKey = "갤러리\u001fgallery",
            galleryId = "c2000000-0000-4000-8000-000000000001",
            snapshot = FollowedGallerySnapshot("갤러리", "Gallery", "서울", "Seoul", "삼청", "Samcheong"),
            knownExhibitionIds = setOf("known"),
            followedAt = Instant.parse("2026-08-14T00:00:00Z"),
            newExhibitionAlertsEnabled = alerts,
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
