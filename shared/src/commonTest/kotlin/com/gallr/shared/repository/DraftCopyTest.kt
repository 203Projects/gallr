package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.CopyIntoDraftResult
import com.gallr.shared.route.PENDING_ACTION_LIFETIME
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRouteStop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Spec 089 public routes (R13, DD18): a copy lands only in the draft the reader confirmed. */
class DraftCopyTest {
    private var now = Instant.parse("2026-10-08T03:00:00Z")
    private val stops = listOf(stop("a"), stop("b"), stop("c"))

    @Test
    fun aCopyReplacesTheConfirmedDraftWithANewUnsavedRoute() =
        runTest {
            val repository = repository()
            repository.rename("내 초안")
            val confirmed = repository.draft.first()

            val result = repository.copyIntoDraft("한남 산책", stops, confirmed.draftId, confirmed.revision)

            val draft = repository.draft.first()
            assertEquals(CopyIntoDraftResult.Applied(draft.draftId), result)
            assertNotEquals(confirmed.draftId, draft.draftId, "the copy starts a new draft")
            assertNotEquals(confirmed.route.id, draft.route.id, "the copy gets a new route id")
            assertEquals("한남 산책", draft.route.name)
            assertEquals(listOf("a", "b", "c"), draft.route.stops.map(PersonalRouteStop::exhibitionId))
            assertFalse(draft.route.isPublished)
            assertNull(draft.ownerAccountId)
            assertFalse(draft.isSaved)
            assertNull(draft.pendingAction)
        }

    @Test
    fun aDraftChangedSinceTheConfirmationIsLeftAlone() =
        runTest {
            val repository = repository()
            val confirmed = repository.draft.first()
            repository.rename("방금 바꾼 이름")
            val changed = repository.draft.first()

            val result = repository.copyIntoDraft("한남 산책", stops, confirmed.draftId, confirmed.revision)

            assertEquals(CopyIntoDraftResult.DraftChanged, result)
            assertEquals(changed, repository.draft.first())
        }

    @Test
    fun aDraftReplacedSinceTheConfirmationIsLeftAlone() =
        runTest {
            val repository = repository()
            val confirmed = repository.draft.first()
            repository.clear()

            assertEquals(
                CopyIntoDraftResult.DraftChanged,
                repository.copyIntoDraft("한남 산책", stops, confirmed.draftId, confirmed.revision),
            )
        }

    @Test
    fun aCopyKeepsAtMostTenStops() =
        runTest {
            val repository = repository()
            val confirmed = repository.draft.first()

            repository.copyIntoDraft("긴 동선", (1..12).map { stop("s$it") }, confirmed.draftId, confirmed.revision)

            val draft = repository.draft.first()
            assertEquals(10, draft.route.stops.size)
        }

    @Test
    fun aCopyWaitingForSignInRemembersItsRoute() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = repository(store)

            repository.setPending(PendingKind.COPY, routeId = "public-route-1")

            val pending = requireNotNull(repository(store).draft.first().pendingAction)
            assertEquals(PendingKind.COPY, pending.kind)
            assertEquals("public-route-1", pending.routeId)
            val draft = repository.draft.first()
            assertTrue(pending.isRunnableFor(draft, now + 29.minutes))
            assertFalse(pending.isRunnableFor(draft, now + PENDING_ACTION_LIFETIME + 1.minutes))
        }

    @Test
    fun saveAndShareStillCarryNoRoute() =
        runTest {
            val repository = repository()

            repository.setPending(PendingKind.SAVE)

            val pending = repository.draft.first().pendingAction
            assertEquals(PendingKind.SAVE, pending?.kind)
            assertNull(pending?.routeId)
        }

    private fun repository(store: DataStore<Preferences> = InMemoryPreferencesDataStore()) =
        DataStorePersonalRouteDraftRepository(dataStore = store, clock = { now })

    private fun stop(id: String) =
        PersonalRouteStop(
            exhibitionId = id,
            nameKo = id,
            nameEn = id,
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            point = GeoPoint(37.58, 126.98),
            regionKo = "한남동",
            regionEn = "Hannam-dong",
            cityKo = "서울",
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
