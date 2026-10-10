package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.route.AppendResult
import com.gallr.shared.route.CopyIntoDraftResult
import com.gallr.shared.route.MAX_ROUTE_STOPS
import com.gallr.shared.route.PendingAction
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.UndoResult
import com.gallr.shared.route.UndoToken
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/** In-memory draft with the repository's append and undo rules, recording moves for the composer tests. */
class FakePersonalRouteDraftRepository(
    initialStops: List<PersonalRouteStop> = emptyList(),
    name: String = "",
) : PersonalRouteDraftRepository {
    private val state =
        MutableStateFlow(
            PersonalRouteDraft(
                draftId = "draft-1",
                revision = 0,
                route = PersonalRoute(id = "route-1", name = name, stops = initialStops),
                ownerAccountId = null,
                savedRevision = null,
                pendingAction = null,
            ),
        )
    override val draft: StateFlow<PersonalRouteDraft> = state.asStateFlow()

    val moves = mutableListOf<Pair<Int, Int>>()

    /** When set, the next undo returns this instead of applying the repository rules. */
    var forcedUndoResult: UndoResult? = null

    override suspend fun append(exhibition: Exhibition): AppendResult {
        val stop = exhibition.toRouteStop() ?: return AppendResult.MissingLocation
        val stops = state.value.route.stops
        return when {
            stops.any { it.exhibitionId == stop.exhibitionId } -> AppendResult.Duplicate
            stops.size >= MAX_ROUTE_STOPS -> AppendResult.Full
            else -> AppendResult.Added(edit { it + stop }.size)
        }
    }

    override suspend fun move(
        from: Int,
        to: Int,
    ) {
        moves += from to to
        edit { stops -> stops.toMutableList().apply { add(to, removeAt(from)) } }
    }

    override suspend fun remove(position: Int): UndoToken {
        val stop = state.value.route.stops[position]
        edit { stops -> stops.filterIndexed { index, _ -> index != position } }
        return UndoToken(state.value.draftId, state.value.revision, position, stop)
    }

    override suspend fun undo(token: UndoToken): UndoResult {
        forcedUndoResult?.let { return it.also { forcedUndoResult = null } }
        if (token.revisionAfterRemove != state.value.revision) return UndoResult.Expired
        edit { stops -> stops.toMutableList().apply { add(token.position.coerceAtMost(size), token.stop) } }
        return UndoResult.Restored
    }

    override suspend fun rename(name: String) {
        state.value = state.value.changed(state.value.route.copy(name = name))
    }

    override suspend fun replace(
        route: PersonalRoute,
        ownerAccountId: String?,
    ) {
        state.value = state.value.changed(route).copy(ownerAccountId = ownerAccountId)
    }

    override suspend fun seed(stops: List<PersonalRouteStop>) {
        val current = state.value
        state.value =
            current
                .changed(PersonalRoute(id = "route-${current.revision + 1}", name = "", stops = stops))
                .copy(draftId = "draft-seeded-${current.revision + 1}", ownerAccountId = null, savedRevision = null)
    }

    override suspend fun clear() {
        edit { emptyList() }
    }

    var detachCount = 0
        private set

    override suspend fun detach() {
        detachCount += 1
        val current = state.value
        state.value =
            current.copy(
                route = current.route.copy(id = "route-detached-$detachCount", isPublished = false, revision = null),
                ownerAccountId = null,
                savedRevision = null,
            )
    }

    /** When pending actions are stamped; tests move it to check the 30-minute lifetime. */
    var pendingCreatedAt: Instant = Instant.parse("2026-10-08T04:00:00Z")

    override suspend fun setPending(
        kind: PendingKind,
        routeId: String?,
    ) {
        val current = state.value
        state.value =
            current.copy(
                pendingAction = PendingAction(kind, pendingCreatedAt, current.draftId, current.revision, routeId),
            )
    }

    /** Copies applied, in order; a test can change the draft first to see [CopyIntoDraftResult.DraftChanged]. */
    val copies = mutableListOf<Pair<String, List<PersonalRouteStop>>>()

    override suspend fun copyIntoDraft(
        name: String,
        stops: List<PersonalRouteStop>,
        expectedDraftId: String,
        expectedRevision: Long,
    ): CopyIntoDraftResult {
        val current = state.value
        if (current.draftId != expectedDraftId || current.revision != expectedRevision) {
            return CopyIntoDraftResult.DraftChanged
        }
        copies += name to stops
        val draftId = "draft-copy-${copies.size}"
        state.value =
            PersonalRouteDraft(
                draftId = draftId,
                revision = 0,
                route =
                    PersonalRoute(
                        id = "route-copy-${copies.size}",
                        name = name,
                        stops = stops.take(MAX_ROUTE_STOPS),
                    ),
                ownerAccountId = null,
                savedRevision = null,
                pendingAction = null,
            )
        return CopyIntoDraftResult.Applied(draftId)
    }

    override suspend fun clearPending() {
        state.value = state.value.copy(pendingAction = null)
    }

    override suspend fun acknowledgeSave(
        draftId: String,
        sentRevision: Long,
        saved: PersonalRoute,
        ownerAccountId: String,
    ): Boolean {
        if (draftId != state.value.draftId) return false
        state.value =
            state.value.copy(
                route = state.value.route.copy(revision = saved.revision, isPublished = saved.isPublished),
                ownerAccountId = ownerAccountId,
                savedRevision = sentRevision.takeIf { it == state.value.revision },
            )
        return true
    }

    override suspend fun markPublished(
        draftId: String,
        published: PersonalRoute,
    ) {
        if (draftId != state.value.draftId) return
        state.value = state.value.copy(route = state.value.route.copy(isPublished = true))
    }

    private fun edit(change: (List<PersonalRouteStop>) -> List<PersonalRouteStop>): List<PersonalRouteStop> {
        val current = state.value
        val stops = change(current.route.stops)
        state.value = current.changed(current.route.copy(stops = stops))
        return stops
    }

    private fun PersonalRouteDraft.changed(route: PersonalRoute) =
        copy(route = route, revision = revision + 1, pendingAction = null)
}
