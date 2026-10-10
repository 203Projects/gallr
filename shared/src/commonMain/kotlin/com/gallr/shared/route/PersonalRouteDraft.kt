package com.gallr.shared.route

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The one route being composed on this device (spec 089).
 *
 * [revision] increases on every change the author makes, so a save or a pending action can be tied to exactly
 * the version that was on screen (RO2, RO3). [savedRevision] is the revision the last successful save
 * acknowledged; [ownerAccountId] is the account that saved it (E-D8).
 */
data class PersonalRouteDraft(
    val draftId: String,
    val revision: Long,
    val route: PersonalRoute,
    val ownerAccountId: String?,
    val savedRevision: Long?,
    val pendingAction: PendingAction?,
) {
    /** True when the server holds exactly what the author sees. */
    val isSaved: Boolean get() = savedRevision != null && savedRevision == revision

    /** True once the route exists on the server for [ownerAccountId]. */
    val hasRemoteRoute: Boolean get() = ownerAccountId != null

    /** Stops the server does not hold yet: replacing this draft would lose them, so the author must confirm. */
    val hasUnsavedStops: Boolean get() = route.stops.isNotEmpty() && !isSaved
}

/** What the author asked for while signed out (RR1); COPY copies a listed route into the draft (DD18). */
enum class PendingKind { SAVE, SHARE, COPY }

/**
 * A save or share waiting for sign-in, bound to the draft version the author tapped (RO2). It may run only
 * while [draftId] and [draftRevision] still match and within [PENDING_ACTION_LIFETIME] of [createdAt].
 */
data class PendingAction(
    val kind: PendingKind,
    val createdAt: Instant,
    val draftId: String,
    val draftRevision: Long,
    /** The listed route a [PendingKind.COPY] copies; null for a save or share. */
    val routeId: String? = null,
) {
    fun isRunnableFor(
        draft: PersonalRouteDraft,
        now: Instant,
    ): Boolean =
        draftId == draft.draftId &&
            draftRevision == draft.revision &&
            now >= createdAt &&
            now - createdAt <= PENDING_ACTION_LIFETIME
}

/** Outcome of adding an exhibition to the draft. */
sealed interface AppendResult {
    data class Added(
        val stopCount: Int,
    ) : AppendResult

    data object Full : AppendResult

    data object Duplicate : AppendResult

    data object MissingLocation : AppendResult
}

/** Handle for undoing one removal; valid only until the draft changes again (RO4). */
data class UndoToken(
    val draftId: String,
    val revisionAfterRemove: Long,
    val position: Int,
    val stop: PersonalRouteStop,
)

/** Outcome of copying a listed route into the draft (R13). */
sealed interface CopyIntoDraftResult {
    /** The copy is now the draft [draftId]. */
    data class Applied(
        val draftId: String,
    ) : CopyIntoDraftResult

    /** The draft changed after the reader confirmed, so it was left alone. */
    data object DraftChanged : CopyIntoDraftResult
}

/** Outcome of an undo. */
enum class UndoResult { Restored, Full, Duplicate, Expired }

val PENDING_ACTION_LIFETIME = 30.minutes
