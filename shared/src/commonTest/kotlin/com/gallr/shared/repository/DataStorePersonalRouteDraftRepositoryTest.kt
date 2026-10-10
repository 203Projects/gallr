package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.AppendResult
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.UndoResult
import com.gallr.shared.route.UndoToken
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 089: one observed draft per device with atomic, rule-checked writes (RR1, RR3, RO2–RO5, E-D8). */
class DataStorePersonalRouteDraftRepositoryTest {
    private val now = Instant.parse("2026-10-08T03:00:00Z")

    @Test
    fun startsEmptyWithAnAppChosenRouteId() =
        runTest {
            val draft = repository().draft.first()

            assertTrue(draft.route.stops.isEmpty())
            assertEquals("", draft.route.name)
            assertTrue(draft.route.id.isNotBlank())
            assertNull(draft.ownerAccountId)
            assertFalse(draft.isSaved)
        }

    @Test
    fun appendAddsInOrderAndReportsTheCount() =
        runTest {
            val repository = repository()

            assertEquals(AppendResult.Added(1), repository.append(exhibition("a")))
            assertEquals(AppendResult.Added(2), repository.append(exhibition("b")))

            assertEquals(listOf("a", "b"), repository.draft.first().stopIds())
        }

    @Test
    fun appendRefusesDuplicatesTheEleventhStopAndMissingLocations() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))

            assertEquals(AppendResult.Duplicate, repository.append(exhibition("a")))
            assertEquals(AppendResult.MissingLocation, repository.append(exhibition("x", located = false)))
            (2..10).forEach { repository.append(exhibition("s$it")) }
            assertEquals(AppendResult.Full, repository.append(exhibition("eleventh")))
            val full = repository.draft.first()
            assertEquals(10, full.route.stops.size)
        }

    @Test
    fun everyChangeBumpsTheRevision() =
        runTest {
            val repository = repository()
            val start = repository.draft.first().revision

            repository.append(exhibition("a"))
            repository.append(exhibition("b"))
            repository.move(from = 1, to = 0)
            repository.rename("동선")

            assertEquals(start + 4, repository.draft.first().revision)
            assertEquals(listOf("b", "a"), repository.draft.first().stopIds())
            val renamed = repository.draft.first()
            assertEquals("동선", renamed.route.name)
        }

    @Test
    fun undoRestoresARemovedStopAtItsPosition() =
        runTest {
            val repository = repository()
            listOf("a", "b", "c").forEach { repository.append(exhibition(it)) }

            val token = repository.remove(position = 1)
            assertEquals(listOf("a", "c"), repository.draft.first().stopIds())

            assertEquals(UndoResult.Restored, repository.undo(token))
            assertEquals(listOf("a", "b", "c"), repository.draft.first().stopIds())
        }

    @Test
    fun undoExpiresAfterAnyLaterChange() =
        runTest {
            val repository = repository()
            listOf("a", "b", "c").forEach { repository.append(exhibition(it)) }
            val token = repository.remove(position = 0)

            repository.rename("다른 이름")

            assertEquals(UndoResult.Expired, repository.undo(token))
            assertEquals(listOf("b", "c"), repository.draft.first().stopIds())
        }

    @Test
    fun undoNeverBreaksTheCapOrDuplicatesAStop() =
        runTest {
            val full = repository()
            (1..10).forEach { full.append(exhibition("s$it")) }
            val fullToken = full.remove(position = 0)
            full.append(exhibition("replacement"))
            assertEquals(UndoResult.Full, full.undoIgnoringExpiry(fullToken))

            val duplicate = repository()
            listOf("a", "b").forEach { duplicate.append(exhibition(it)) }
            val duplicateToken = duplicate.remove(position = 0)
            duplicate.append(exhibition("a"))
            assertEquals(UndoResult.Duplicate, duplicate.undoIgnoringExpiry(duplicateToken))
        }

    @Test
    fun pendingActionRecordsTheDraftVersionAndAnyChangeClearsIt() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))
            repository.append(exhibition("b"))

            repository.setPending(PendingKind.SHARE)
            val pending = requireNotNull(repository.draft.first().pendingAction)
            assertEquals(PendingKind.SHARE, pending.kind)
            assertEquals(now, pending.createdAt)
            assertEquals(repository.draft.first().draftId, pending.draftId)
            assertEquals(repository.draft.first().revision, pending.draftRevision)

            repository.move(from = 0, to = 1)
            assertNull(repository.draft.first().pendingAction)

            repository.setPending(PendingKind.SAVE)
            repository.clearPending()
            assertNull(repository.draft.first().pendingAction)
        }

    @Test
    fun acknowledgingTheSentVersionMarksItSavedWithTheServerSnapshot() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))
            repository.append(exhibition("b"))
            val sent = repository.draft.first()
            val firstStop = sent.route.stops.first()
            val serverStop = firstStop.copy(nameKo = "서버 이름")
            val saved =
                sent.route.copy(
                    stops = listOf(serverStop, sent.route.stops[1]),
                    revision = Instant.parse("2026-10-08T03:01:00Z"),
                )

            val applied = repository.acknowledgeSave(sent.draftId, sent.revision, saved, ownerAccountId = "user-1")

            val after = repository.draft.first()
            assertTrue(applied)
            assertTrue(after.isSaved)
            assertEquals("user-1", after.ownerAccountId)
            val firstAfter = after.route.stops.first()
            assertEquals("서버 이름", firstAfter.nameKo)
            assertEquals(saved.revision, after.route.revision)
        }

    @Test
    fun aSaveAcknowledgedAfterNewerEditsKeepsThoseEditsUnsaved() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))
            repository.append(exhibition("b"))
            val sent = repository.draft.first()

            repository.append(exhibition("c"))
            repository.acknowledgeSave(sent.draftId, sent.revision, sent.route, ownerAccountId = "user-1")

            val after = repository.draft.first()
            assertEquals(listOf("a", "b", "c"), after.stopIds())
            assertFalse(after.isSaved)
            assertEquals("user-1", after.ownerAccountId)
        }

    @Test
    fun aSaveForAReplacedDraftIsIgnored() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))
            val sent = repository.draft.first()

            repository.replace(PersonalRoute(id = "other-route", name = "", stops = emptyList()))
            val applied = repository.acknowledgeSave(sent.draftId, sent.revision, sent.route, ownerAccountId = "user-1")

            assertFalse(applied)
            assertNull(repository.draft.first().ownerAccountId)
            val current = repository.draft.first()
            assertEquals("other-route", current.route.id)
        }

    @Test
    fun replaceStartsANewDraftAndDetachIssuesANewRouteId() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))
            val before = repository.draft.first()

            repository.replace(PersonalRoute(id = "saved-route", name = "저장한 동선", stops = before.route.stops), "user-1")
            val replaced = repository.draft.first()
            assertNotEquals(before.draftId, replaced.draftId)
            assertTrue(replaced.isSaved)
            assertEquals("user-1", replaced.ownerAccountId)

            repository.detach()
            val detached = repository.draft.first()
            assertNotEquals("saved-route", detached.route.id)
            assertNull(detached.ownerAccountId)
            assertFalse(detached.isSaved)
            assertFalse(detached.route.isPublished)
            assertEquals(listOf("a"), detached.stopIds())
        }

    @Test
    fun seedStartsANewDraftWithTheGivenOrderCappedAtTen() =
        runTest {
            val repository = repository()
            val before = repository.draft.first()
            val stops = (1..12).mapNotNull { exhibition("p$it").let(::requireStop) }

            repository.seed(stops)

            val seeded = repository.draft.first()
            assertNotEquals(before.draftId, seeded.draftId)
            assertEquals((1..10).map { "p$it" }, seeded.stopIds())
            assertFalse(seeded.isSaved)
        }

    @Test
    fun clearStartsAnEmptyDraft() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))

            repository.clear()

            val cleared = repository.draft.first()
            assertTrue(cleared.route.stops.isEmpty())
        }

    @Test
    fun markPublishedKeepsTheDraftSavedAndDoesNotCountAsAnEdit() =
        runTest {
            val repository = repository()
            repository.append(exhibition("a"))
            repository.append(exhibition("b"))
            val sent = repository.draft.first()
            repository.acknowledgeSave(sent.draftId, sent.revision, sent.route, ownerAccountId = "user-1")

            repository.markPublished(sent.draftId, sent.route.copy(isPublished = true))

            val after = repository.draft.first()
            assertTrue(after.route.isPublished)
            assertTrue(after.isSaved)
            assertEquals(sent.revision, after.revision)
        }

    @Test
    fun theDraftSurvivesRepositoryReconstruction() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val first = repository(store)
            first.append(exhibition("a"))
            first.rename("유지")

            val reconstructed = repository(store).draft.first()

            assertEquals(listOf("a"), reconstructed.stopIds())
            assertEquals("유지", reconstructed.route.name)
            val onlyStop = reconstructed.route.stops.single()
            assertEquals(GeoPoint(37.58, 126.98), onlyStop.point)
        }

    @Test
    fun anUnreadableStoredDraftIsReplacedByAnEmptyOne() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    this[stringPreferencesKey("personal_route_draft_v1")] = "{not json"
                }
            }

            val draft = repository(store).draft.first()

            assertTrue(draft.route.stops.isEmpty())
        }

    private fun repository(store: DataStore<Preferences> = InMemoryPreferencesDataStore()) =
        DataStorePersonalRouteDraftRepository(dataStore = store, clock = { now })

    /** Exercises the invariant check alone by undoing before the later change is recorded as expiring it. */
    private suspend fun DataStorePersonalRouteDraftRepository.undoIgnoringExpiry(token: UndoToken) =
        undo(token.copy(revisionAfterRemove = draft.first().revision))

    private fun PersonalRouteDraft.stopIds() = route.stops.map(PersonalRouteStop::exhibitionId)

    private fun requireStop(exhibition: Exhibition): PersonalRouteStop = requireNotNull(exhibition.toRouteStop())

    private fun exhibition(
        id: String,
        located: Boolean = true,
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 10, 1),
        closingDate = LocalDate(2026, 11, 30),
        isFeatured = false,
        latitude = if (located) 37.58 else null,
        longitude = if (located) 126.98 else null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
