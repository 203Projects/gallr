package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.route.AppendResult
import com.gallr.shared.route.CopyIntoDraftResult
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.UndoResult
import com.gallr.shared.route.UndoToken
import kotlinx.coroutines.flow.Flow

/**
 * The device's single route draft (spec 089, RR3). Every screen that changes the draft goes through these
 * atomic operations, and every reader observes [draft], so no writer can overwrite another.
 *
 * Every author change increments the draft revision and clears any pending action (RO2).
 */
interface PersonalRouteDraftRepository {
    val draft: Flow<PersonalRouteDraft>

    /** Adds [exhibition] at the end, refusing duplicates, an eleventh stop and exhibitions without a location. */
    suspend fun append(exhibition: Exhibition): AppendResult

    /** Moves the stop at [from] to [to]; out-of-range positions are ignored. */
    suspend fun move(
        from: Int,
        to: Int,
    )

    /** Removes the stop at [position] and returns a token that can restore it until the next change. */
    suspend fun remove(position: Int): UndoToken

    /** Restores a removed stop unless the draft changed since, is full, or already holds it (RO4). */
    suspend fun undo(token: UndoToken): UndoResult

    suspend fun rename(name: String)

    /**
     * Starts a new draft holding [route]. Pass [ownerAccountId] when [route] is a saved route of that account,
     * which marks the new draft as saved.
     */
    suspend fun replace(
        route: PersonalRoute,
        ownerAccountId: String? = null,
    )

    /** Starts a new draft with these stops in order, e.g. copied from a planner route. */
    suspend fun seed(stops: List<PersonalRouteStop>)

    /**
     * Replaces the draft with a copy of a listed route in one update, but only while the draft is still the one
     * the reader confirmed ([expectedDraftId] at [expectedRevision]); otherwise leaves it alone (R13). The copy is
     * a new, unsaved route under [name] with at most ten stops.
     */
    suspend fun copyIntoDraft(
        name: String,
        stops: List<PersonalRouteStop>,
        expectedDraftId: String,
        expectedRevision: Long,
    ): CopyIntoDraftResult

    /** Starts a new, empty draft. */
    suspend fun clear()

    /** Keeps the stops but forgets the remote route so the next save creates a new one (E-D8, E-D18). */
    suspend fun detach()

    /** Records an action to run after sign-in; [routeId] names the listed route of a [PendingKind.COPY]. */
    suspend fun setPending(
        kind: PendingKind,
        routeId: String? = null,
    )

    suspend fun clearPending()

    /**
     * Applies a save response for the draft version [sentRevision] of [draftId]. Ignored when the draft was
     * replaced; when newer edits exist they are kept and the draft stays unsaved (RO3).
     *
     * @return true when the response was applied to the current draft.
     */
    suspend fun acknowledgeSave(
        draftId: String,
        sentRevision: Long,
        saved: PersonalRoute,
        ownerAccountId: String,
    ): Boolean

    /** Records that the saved route is now public, without counting as an author change. */
    suspend fun markPublished(
        draftId: String,
        published: PersonalRoute,
    )
}
