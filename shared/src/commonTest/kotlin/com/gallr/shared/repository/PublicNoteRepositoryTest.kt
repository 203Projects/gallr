package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.MyGallrAccountArchive
import com.gallr.shared.data.model.MyGallrAccountMutation
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.diary.PublicNoteModeration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** contracts/shared-diary-api.md PublicNoteRepository: signed-in only, backed by the account sync. */
class PublicNoteRepositoryTest {
    private val remoteNote =
        OwnPublicNote(
            exhibitionId = "remote",
            thoughtId = "thought-remote",
            content = "공개 감상",
            moderation = PublicNoteModeration.APPROVED,
            rejectionReason = null,
            requestedAt = Instant.parse("2026-10-01T00:00:00Z"),
            approvedAt = Instant.parse("2026-10-02T00:00:00Z"),
            approvalSeen = false,
        )

    @Test
    fun ownNotesComeFromTheArchiveAndAreEmptyWithoutAnAccount() =
        runTest {
            val source = Source(online = true, archive = archive(notes = listOf(remoteNote)))
            val (coordinator, repository) = repository(source)

            assertTrue(repository.observeOwnNotes().first().isEmpty())

            coordinator.activateAccount("member-one")
            assertEquals(listOf(remoteNote), repository.observeOwnNotes().first())

            coordinator.deactivateAccount()
            assertTrue(repository.observeOwnNotes().first().isEmpty())
        }

    @Test
    fun publishUnpublishAndAcknowledgeEnqueueTheirMutations() =
        runTest {
            val source = Source(online = true, archive = archive(visits = listOf(visit("ex", note = "메모"))))
            val (coordinator, repository) = repository(source)
            coordinator.activateAccount("member-one")

            repository.publish("ex")
            repository.acknowledgeApproval("ex")
            repository.unpublish("ex")

            assertEquals(
                listOf(
                    MyGallrAccountMutation.PublishNote::class,
                    MyGallrAccountMutation.AcknowledgeNoteApproval::class,
                    MyGallrAccountMutation.UnpublishNote::class,
                ),
                source.sent.flatten().map { it::class },
            )
            assertTrue(source.sent.flatten().all { it.exhibitionIdOrNull() == "ex" })
        }

    @Test
    fun pendingNoteMutationsAreObservablePerExhibitionUntilTheServerTakesThem() =
        runTest {
            val source =
                Source(online = false, archive = archive(visits = listOf(visit("a", "메모"), visit("b", "메모"))))
            val (coordinator, repository) = repository(source)
            coordinator.activateAccount("member-one")
            assertTrue(repository.observePendingNoteMutations().first().isEmpty())

            repository.publish("a")
            repository.publish("b")
            repository.unpublish("b")
            repository.acknowledgeApproval("a")

            assertEquals(
                mapOf("a" to PendingNoteMutation.PUBLISH, "b" to PendingNoteMutation.UNPUBLISH),
                repository.observePendingNoteMutations().first(),
            )

            source.online = true
            coordinator.refresh()

            assertTrue(repository.observePendingNoteMutations().first().isEmpty())
        }

    private fun MyGallrAccountMutation.exhibitionIdOrNull(): String? =
        when (this) {
            is MyGallrAccountMutation.PublishNote -> exhibitionId
            is MyGallrAccountMutation.UnpublishNote -> exhibitionId
            is MyGallrAccountMutation.AcknowledgeNoteApproval -> exhibitionId
            else -> null
        }

    private fun repository(
        source: MyGallrAccountCommandSource,
    ): Pair<MyGallrAccountSyncCoordinator, PublicNoteRepository> {
        val dataStore = InMemoryPreferencesDataStore()
        val coordinator =
            MyGallrAccountSyncCoordinator(
                guestVisitRepository = DataStoreVisitRepository(dataStore),
                guestFollowedGalleryRepository = DataStoreFollowedGalleryRepository(dataStore),
                accountStore = DataStoreMyGallrAccountStore(dataStore),
                source = source,
                generateMutationId = sequentialIds(),
            )
        return coordinator to PublicNoteRepositoryImpl(coordinator)
    }

    private fun archive(
        visits: List<ExhibitionVisit> = emptyList(),
        notes: List<OwnPublicNote> = emptyList(),
    ) = MyGallrAccountArchive(1, visits, emptyList(), notes)

    private fun visit(
        id: String,
        note: String? = null,
    ) = ExhibitionVisit(
        clientRecordId = "record-$id",
        exhibitionId = id,
        snapshot =
            ExhibitionVisitSnapshot(
                "전시",
                "Exhibition",
                "갤러리",
                "Gallery",
                LocalDate(2026, 10, 1),
                LocalDate(2026, 12, 1),
                null,
            ),
        createdAt = Instant.parse("2026-11-01T00:00:00Z"),
        note = note,
    )

    private fun sequentialIds(): () -> String {
        var next = 1
        return { "c1000000-0000-4000-8000-${next++.toString().padStart(12, '0')}" }
    }

    private class Source(
        var online: Boolean,
        private val archive: MyGallrAccountArchive,
    ) : MyGallrAccountCommandSource {
        val sent = mutableListOf<List<MyGallrAccountMutation>>()

        override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive {
            if (!online) error("offline")
            if (mutations.isNotEmpty()) sent += mutations
            return archive
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
