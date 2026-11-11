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
import com.gallr.shared.data.network.MyGallrAccountAuthenticationRequiredException
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.mergeEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // Spec 101 US3: what the account brings is never the visitor's new act, so the lift rule must know it before
    // the device's copy shows it, and must not mistake this device's own changes for it.
    @Test
    fun `the archive the server sends arrives before the device copy and without this device's own changes`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val source = InMemorySource(MyGallrAccountArchive(1, listOf(visit("cloud")), emptyList()))
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    guestVisitRepository = DataStoreVisitRepository(dataStore),
                    guestFollowedGalleryRepository = DataStoreFollowedGalleryRepository(dataStore),
                    accountStore = DataStoreMyGallrAccountStore(dataStore),
                    source = source,
                    generateMutationId = sequentialIds(),
                )
            val arrivedWhenShown = mutableListOf<Set<String>?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.observeActiveArchive().collect { archive ->
                    if (archive?.visits?.any { it.exhibitionId == "cloud" } == true) {
                        arrivedWhenShown += coordinator.arrivedArchive.value?.visitIds()
                    }
                }
            }

            coordinator.activateAccount("member-one")
            assertEquals(setOf("cloud"), coordinator.arrivedArchive.value?.visitIds())
            assertTrue(arrivedWhenShown.isNotEmpty())
            assertTrue(arrivedWhenShown.all { it == setOf("cloud") })

            // The sync that sends the visitor's own visit does not count it as the account's.
            coordinator.addVisits(listOf(visit("mine")))
            assertEquals(setOf("cloud"), coordinator.arrivedArchive.value?.visitIds())

            coordinator.deactivateAccount()
            assertNull(coordinator.arrivedArchive.value)
        }

    @Test
    fun `nothing arrives while the server cannot be reached`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    DataStoreVisitRepository(dataStore),
                    DataStoreFollowedGalleryRepository(dataStore),
                    DataStoreMyGallrAccountStore(dataStore),
                    FailingSource,
                    sequentialIds(),
                )

            coordinator.activateAccount("member-one")
            coordinator.addVisits(listOf(visit("offline")))

            assertNull(coordinator.arrivedArchive.value)
        }

    @Test
    fun `every diary mutation survives the outbox and reaches the server as itself`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val offline = coordinator(dataStore, FailingSource)
            offline.activateAccount("member-one")
            val entry = visit("ex").copy(note = "메모", entryUpdatedAt = Instant.parse("2026-11-01T00:00:00Z"))

            offline.saveEntry(entry)
            offline.publishNote("ex")
            offline.acknowledgeNoteApproval("ex")
            offline.unpublishNote("ex")
            offline.deleteEntry("ex")
            offline.deactivateAccount()

            val recording = RecordingSource(MyGallrAccountArchive(1, emptyList(), emptyList()))
            coordinator(dataStore, recording).activateAccount("member-one")

            assertEquals(
                listOf(
                    MyGallrAccountMutation.UpdateVisit(id(1), entry),
                    MyGallrAccountMutation.PublishNote(id(2), "ex"),
                    MyGallrAccountMutation.AcknowledgeNoteApproval(id(3), "ex"),
                    MyGallrAccountMutation.UnpublishNote(id(4), "ex"),
                    MyGallrAccountMutation.DeleteEntry(id(5), "ex"),
                ),
                recording.sent.single(),
            )
        }

    @Test
    fun `a merge mutation survives the outbox`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val guest = visit("guest").copy(note = "기기 메모")
            DataStoreVisitRepository(dataStore).addVisits(listOf(guest))
            coordinator(dataStore, FailingSource).activateAccount("member-one")

            val recording = RecordingSource(MyGallrAccountArchive(1, emptyList(), emptyList()))
            coordinator(dataStore, recording).activateAccount("member-one")

            assertEquals(listOf(MyGallrAccountMutation.MergeVisit(id(1), guest)), recording.sent.first())
        }

    @Test
    fun `sign in sends guest visits as merges so a device note joins the account note`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val guestVisits = DataStoreVisitRepository(dataStore)
            val device = visit("shared").copy(note = "기기 메모", feelingWire = VisitFeeling.LOVED.wire)
            guestVisits.addVisits(listOf(device))
            val account = visit("shared").copy(clientRecordId = "record-account", note = "계정 메모")
            val recording = RecordingSource(MyGallrAccountArchive(1, listOf(account), emptyList()))
            val coordinator = coordinator(dataStore, recording)

            coordinator.activateAccount("member-one")

            assertEquals(listOf(MyGallrAccountMutation.MergeVisit(id(1), device)), recording.sent.first())
            assertTrue(recording.sent.flatten().none { it is MyGallrAccountMutation.AddVisit })
            val merged = coordinator.activeVisit("shared")
            assertEquals("계정 메모\n\n기기 메모", merged.note)
            assertEquals(VisitFeeling.LOVED, merged.feeling)
            assertTrue(guestVisits.observeVisits().first().isEmpty())
        }

    @Test
    fun `the local merge follows the shared merge rule`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val account = visit("ex").copy(note = "계정", entryUpdatedAt = Instant.parse("2026-11-01T00:00:00Z"))
            val device = visit("ex").copy(clientRecordId = "record-device", note = "기기")
            DataStoreVisitRepository(dataStore).addVisits(listOf(device))
            DataStoreMyGallrAccountStore(dataStore).update("member-one") { it.copy(visits = listOf(account)) }
            val coordinator = coordinator(dataStore, FailingSource)

            coordinator.activateAccount("member-one")

            assertEquals(mergeEntry(account, device), coordinator.activeVisit("ex"))
        }

    @Test
    fun `an entry update replaces the diary fields only when it is newer`() =
        runTest {
            val stored = visit("ex").copy(note = "서버 메모", entryUpdatedAt = Instant.parse("2026-11-05T00:00:00Z"))
            val coordinator = offlineAccount(visits = listOf(stored))

            coordinator.saveEntry(stored.copy(note = "이전", entryUpdatedAt = Instant.parse("2026-11-04T00:00:00Z")))
            assertEquals("서버 메모", coordinator.activeVisit("ex").note)

            // Ties keep what the account already holds, as the server does.
            coordinator.saveEntry(stored.copy(note = "같은 시각", entryUpdatedAt = stored.entryUpdatedAt))
            assertEquals("서버 메모", coordinator.activeVisit("ex").note)

            val newer =
                stored.copy(
                    clientRecordId = "record-other-device",
                    note = "새 메모",
                    feelingWire = VisitFeeling.FINE.wire,
                    entryUpdatedAt = Instant.parse("2026-11-06T00:00:00Z"),
                )
            coordinator.saveEntry(newer)
            assertEquals(newer.copy(clientRecordId = stored.clientRecordId), coordinator.activeVisit("ex"))
        }

    @Test
    fun `an entry update fills a 1_15 record and creates a missing one`() =
        runTest {
            val legacy = visit("legacy")
            val coordinator = offlineAccount(visits = listOf(legacy))
            val edited = legacy.copy(note = "메모", entryUpdatedAt = Instant.parse("2026-11-01T00:00:00Z"))

            coordinator.saveEntry(edited)
            coordinator.saveEntry(visit("new").copy(entryUpdatedAt = Instant.parse("2026-11-01T00:00:00Z")))

            assertEquals(edited, coordinator.activeVisit("legacy"))
            assertEquals("new", coordinator.activeVisit("new").exhibitionId)
        }

    @Test
    fun `clearing the note withdraws the local public note`() =
        runTest {
            val stored = visit("ex").copy(note = "메모", entryUpdatedAt = Instant.parse("2026-11-01T00:00:00Z"))
            val coordinator = offlineAccount(visits = listOf(stored), notes = listOf(note("ex")))

            coordinator.saveEntry(stored.copy(note = null, entryUpdatedAt = Instant.parse("2026-11-02T00:00:00Z")))

            assertTrue(coordinator.activeNotes().isEmpty())
        }

    @Test
    fun `deleting an entry drops the visit and its public note while removing a visit keeps the note`() =
        runTest {
            val coordinator =
                offlineAccount(
                    visits = listOf(visit("deleted"), visit("removed")),
                    notes = listOf(note("deleted"), note("removed")),
                )

            coordinator.deleteEntry("deleted")
            coordinator.removeVisit("removed")

            assertTrue(
                coordinator
                    .observeActiveArchive()
                    .first()!!
                    .visits
                    .isEmpty(),
            )
            assertEquals(listOf("removed"), coordinator.activeNotes().map { it.exhibitionId })
        }

    @Test
    fun `publishing marks the note pending with the entry text and unpublishing removes it`() =
        runTest {
            val coordinator =
                offlineAccount(
                    visits =
                        listOf(
                            visit("new").copy(note = "새 감상"),
                            visit("edited").copy(note = "고친 감상"),
                            visit("same").copy(note = "그대로"),
                            visit("no-note"),
                            visit("too-long").copy(note = "가".repeat(281)),
                        ),
                    notes =
                        listOf(
                            note("edited", PublicNoteModeration.APPROVED, content = "예전 감상"),
                            note("same", PublicNoteModeration.APPROVED, content = "그대로"),
                        ),
                )

            listOf("new", "edited", "same", "no-note", "too-long", "no-visit").forEach { coordinator.publishNote(it) }

            val notes = coordinator.activeNotes().associateBy { it.exhibitionId }
            assertEquals(setOf("new", "edited", "same"), notes.keys)
            assertEquals("새 감상", notes.getValue("new").content)
            assertEquals(PublicNoteModeration.PENDING, notes.getValue("new").moderation)
            assertNull(notes.getValue("new").thoughtId)
            assertEquals("고친 감상", notes.getValue("edited").content)
            assertEquals(PublicNoteModeration.PENDING, notes.getValue("edited").moderation)
            assertNull(notes.getValue("edited").approvedAt)
            assertEquals("thought-edited", notes.getValue("edited").thoughtId)
            assertEquals(PublicNoteModeration.APPROVED, notes.getValue("same").moderation)

            coordinator.unpublishNote("edited")
            assertEquals(setOf("new", "same"), coordinator.activeNotes().mapTo(mutableSetOf()) { it.exhibitionId })
        }

    @Test
    fun `acknowledging an approval marks only an approved note seen`() =
        runTest {
            val coordinator =
                offlineAccount(notes = listOf(note("approved", PublicNoteModeration.APPROVED), note("pending")))

            coordinator.acknowledgeNoteApproval("approved")
            coordinator.acknowledgeNoteApproval("pending")

            val notes = coordinator.activeNotes().associateBy { it.exhibitionId }
            assertTrue(notes.getValue("approved").approvalSeen)
            assertFalse(notes.getValue("pending").approvalSeen)
        }

    @Test
    fun `the archive keeps the public notes the server sends`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val remoteNote = note("remote", PublicNoteModeration.APPROVED)
            val archive = MyGallrAccountArchive(2, emptyList(), emptyList(), listOf(remoteNote))
            val coordinator = coordinator(dataStore, RecordingSource(archive))

            coordinator.activateAccount("member-one")

            assertEquals(listOf(remoteNote), coordinator.activeNotes())
            assertEquals(listOf(remoteNote), DataStoreMyGallrAccountStore(dataStore).get("member-one").publicNotes)
        }

    // research P9 and PR #405: while the session refresh is pending the account stays active and saves stay local;
    // the activation that follows the refresh must not merge the guest's records a second time.
    @Test
    fun `while a session refresh is pending the outbox and archive are kept and guest records merge once`() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val accountStore = DataStoreMyGallrAccountStore(dataStore)
            val guestVisits = DataStoreVisitRepository(dataStore)
            guestVisits.addVisits(listOf(visit("guest").copy(note = "기기 메모")))
            accountStore.update("member-one") { it.copy(revision = 4, visits = listOf(visit("cached"))) }
            val source = RefreshPendingSource(cached = visit("cached"))
            val coordinator = coordinator(dataStore, source)
            val saved = visit("saved").copy(note = "저장", entryUpdatedAt = Instant.parse("2026-11-01T00:00:00Z"))

            coordinator.activateAccount("member-one")
            coordinator.saveEntry(saved)
            coordinator.activateAccount("member-one")

            assertEquals(MyGallrSyncStatus.RETRY_NEEDED, coordinator.observeStatus().first())
            assertEquals(setOf("cached", "guest", "saved"), coordinator.activeVisitIds())
            assertEquals(
                listOf("merge_visit", "update_visit"),
                accountStore.get("member-one").pendingMutations.map { it.kind },
            )
            assertEquals(1, guestVisits.observeVisits().first().size)

            source.refreshed = true
            coordinator.activateAccount("member-one")

            assertEquals(
                listOf(MyGallrAccountMutation.MergeVisit::class, MyGallrAccountMutation.UpdateVisit::class),
                source.delivered.map { it::class },
            )
            assertEquals(setOf("cached", "guest", "saved"), coordinator.activeVisitIds())
            assertEquals("기기 메모", coordinator.activeVisit("guest").note)
            assertTrue(accountStore.get("member-one").pendingMutations.isEmpty())
            assertTrue(guestVisits.observeVisits().first().isEmpty())
        }

    private fun coordinator(
        dataStore: DataStore<Preferences>,
        source: MyGallrAccountCommandSource,
    ) = MyGallrAccountSyncCoordinator(
        guestVisitRepository = DataStoreVisitRepository(dataStore),
        guestFollowedGalleryRepository = DataStoreFollowedGalleryRepository(dataStore),
        accountStore = DataStoreMyGallrAccountStore(dataStore),
        source = source,
        generateMutationId = sequentialIds(),
    )

    private suspend fun offlineAccount(
        visits: List<ExhibitionVisit> = emptyList(),
        notes: List<OwnPublicNote> = emptyList(),
    ): MyGallrAccountSyncCoordinator {
        val dataStore = InMemoryPreferencesDataStore()
        DataStoreMyGallrAccountStore(dataStore).update("member-one") {
            it.copy(revision = 1, visits = visits, publicNotes = notes)
        }
        return coordinator(dataStore, FailingSource).also { it.activateAccount("member-one") }
    }

    private suspend fun MyGallrAccountSyncCoordinator.activeVisit(exhibitionId: String): ExhibitionVisit =
        observeActiveArchive().first()!!.visits.single { it.exhibitionId == exhibitionId }

    private suspend fun MyGallrAccountSyncCoordinator.activeVisitIds(): Set<String> =
        observeActiveArchive().first()!!.visitIds()

    private suspend fun MyGallrAccountSyncCoordinator.activeNotes(): List<OwnPublicNote> =
        observeActiveArchive().first()!!.publicNotes

    private fun note(
        exhibitionId: String,
        moderation: PublicNoteModeration = PublicNoteModeration.PENDING,
        content: String = "감상",
    ) = OwnPublicNote(
        exhibitionId = exhibitionId,
        thoughtId = "thought-$exhibitionId",
        content = content,
        moderation = moderation,
        rejectionReason = null,
        requestedAt = Instant.parse("2026-10-01T00:00:00Z"),
        approvedAt = if (moderation == PublicNoteModeration.APPROVED) Instant.parse("2026-10-02T00:00:00Z") else null,
        approvalSeen = false,
    )

    private fun id(sequence: Int) = "c1000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}"

    /** Keeps every batch it is sent and answers with its archive, into which it merges any merge mutation. */
    private class RecordingSource(
        private var archive: MyGallrAccountArchive,
    ) : MyGallrAccountCommandSource {
        val sent = mutableListOf<List<MyGallrAccountMutation>>()

        override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive {
            sent += mutations
            val merges = mutations.filterIsInstance<MyGallrAccountMutation.MergeVisit>().map { it.visit }
            val accountIds = archive.visits.mapTo(mutableSetOf()) { it.exhibitionId }
            val merged =
                archive.visits.map { account ->
                    merges.firstOrNull { it.exhibitionId == account.exhibitionId }?.let { mergeEntry(account, it) }
                        ?: account
                }
            archive = archive.copy(visits = merged + merges.filterNot { it.exhibitionId in accountIds })
            return archive
        }
    }

    /** Refuses without a token while the refresh is pending, as the API client does; then stores what it is sent. */
    private class RefreshPendingSource(
        private val cached: ExhibitionVisit,
    ) : MyGallrAccountCommandSource {
        var refreshed = false
        val delivered = mutableListOf<MyGallrAccountMutation>()

        override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive {
            if (!refreshed) throw MyGallrAccountAuthenticationRequiredException()
            delivered += mutations
            val visits =
                mutations.mapNotNull { mutation ->
                    when (mutation) {
                        is MyGallrAccountMutation.MergeVisit -> mutation.visit
                        is MyGallrAccountMutation.UpdateVisit -> mutation.visit
                        else -> null
                    }
                }
            return MyGallrAccountArchive(5, listOf(cached) + visits, emptyList())
        }
    }

    private fun MyGallrAccountArchive.visitIds(): Set<String> = visits.mapTo(mutableSetOf()) { it.exhibitionId }

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

                        is MyGallrAccountMutation.MergeVisit -> {
                            val account = archive.visits.firstOrNull { it.exhibitionId == mutation.visit.exhibitionId }
                            archive.copy(
                                revision = archive.revision + 1,
                                visits =
                                    archive.visits.filterNot { it.exhibitionId == mutation.visit.exhibitionId } +
                                        (account?.let { mergeEntry(it, mutation.visit) } ?: mutation.visit),
                            )
                        }

                        else -> {
                            archive
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
