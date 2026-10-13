package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.RouteSaveProblem
import com.gallr.shared.route.routeFailure
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/** What a route can be shared with: the saved, published route the card shows and its versioned link (E-D16). */
data class RouteSharePayload(
    val route: PersonalRoute,
    val link: String,
)

/** Why a save or share did not complete, in the terms the composer shows (DR-D7). */
sealed interface RouteActionError {
    /** Offline or any unexpected rejection: "! 저장하지 못했어요 · 다시 시도". */
    data object SaveFailed : RouteActionError

    /** Staff removed the route; the draft now saves as a new route. */
    data object Revoked : RouteActionError

    /** Exhibitions no longer published that the saved route did not already hold (E-D18). */
    data class BlockedStops(
        val exhibitionIds: List<String>,
    ) : RouteActionError

    /** Exhibitions without a map location (RO5). */
    data class MissingLocation(
        val exhibitionIds: List<String>,
    ) : RouteActionError

    /** The draft breaks a rule the server also checks, such as an empty name. */
    data class Invalid(
        val problem: RouteSaveProblem,
    ) : RouteActionError
}

sealed interface RouteActionOutcome {
    /** Signed out: the action waits for sign-in as a pending action on the draft (RR1). */
    data object SignInRequired : RouteActionOutcome

    data class Saved(
        val route: PersonalRoute,
    ) : RouteActionOutcome

    /**
     * Saved and published. [resumed] is true when the share was interrupted by sign-in; the composer then shows
     * "공유 준비됐어요" and waits for a tap instead of opening the share sheet by itself.
     */
    data class ReadyToShare(
        val payload: RouteSharePayload,
        val resumed: Boolean = false,
    ) : RouteActionOutcome

    data class Failed(
        val error: RouteActionError,
    ) : RouteActionOutcome
}

/**
 * The save-then-publish-then-share sequence shared by the composer and the 내 동선 list (E-D9).
 *
 * Every save sends the draft's current edits and is acknowledged only for the revision it sent (RO3). A link is
 * produced only for a published route whose saved state equals what the card shows. Signed out, the action becomes
 * a pending action bound to this draft version, which [resumePending] runs on the next sign-in within 30 minutes
 * (RR1, RO2). A route belonging to another account, or rejected as not owned, is saved as a new route (E-D8).
 */
class RouteShareOrchestrator(
    private val draftRepository: PersonalRouteDraftRepository,
    private val routeRepository: PersonalRouteRepository,
    private val clock: Clock = Clock.System,
    private val analytics: RouteAnalytics = RouteAnalytics.None,
) {
    suspend fun save(auth: AuthState): RouteActionOutcome = run(PendingKind.SAVE, auth, resumed = false)

    suspend fun share(auth: AuthState): RouteActionOutcome = run(PendingKind.SHARE, auth, resumed = false)

    /**
     * Shares a saved route that is not the open draft, from the 내 동선 list: publishes it first when it is not
     * public yet, otherwise reads its current version for the card. The open draft is left alone.
     */
    suspend fun shareSaved(
        routeId: String,
        auth: AuthState,
    ): RouteActionOutcome {
        if (auth !is AuthState.Authenticated) return RouteActionOutcome.SignInRequired
        val current = routeRepository.loadMine(routeId).getOrElse { error -> return listFailure(error) }
        val published =
            if (current.isPublished) {
                current
            } else {
                routeRepository
                    .publish(routeId)
                    .getOrElse { error -> return listFailure(error) }
                    .also { analytics.published(it.stops.size) }
            }
        return RouteActionOutcome.ReadyToShare(RouteSharePayload(published, routeShareLink(published)))
    }

    private fun listFailure(error: Throwable): RouteActionOutcome =
        when (error.routeFailure()) {
            PersonalRouteFailure.Unauthenticated -> RouteActionOutcome.SignInRequired
            PersonalRouteFailure.Revoked -> RouteActionOutcome.Failed(RouteActionError.Revoked)
            else -> RouteActionOutcome.Failed(RouteActionError.SaveFailed)
        }

    /** Runs the draft's pending action when it is still for this draft version and fresh; otherwise drops it. */
    suspend fun resumePending(auth: AuthState): RouteActionOutcome? {
        if (auth !is AuthState.Authenticated) return null
        val draft = draftRepository.draft.first()
        val pending = draft.pendingAction ?: return null
        // A copy of a listed route continues in its preview (PublicRoutesViewModel), not here.
        if (pending.kind == PendingKind.COPY) return null
        draftRepository.clearPending()
        if (!pending.isRunnableFor(draft, clock.now())) return null
        return run(pending.kind, auth, resumed = true)
    }

    /** Counts an opened share sheet for [payload]'s route. */
    suspend fun shareOpened(payload: RouteSharePayload) {
        analytics.shared(payload.route.stops.size)
    }

    /** The author backed out of sign-in. */
    suspend fun cancelPending() {
        draftRepository.clearPending()
    }

    private suspend fun run(
        kind: PendingKind,
        auth: AuthState,
        resumed: Boolean,
    ): RouteActionOutcome {
        if (auth !is AuthState.Authenticated) return signInRequired(kind)
        val accountId = auth.user.id
        val draft = draftRepository.draft.first()
        draft.route.saveProblem()?.let { return RouteActionOutcome.Failed(RouteActionError.Invalid(it)) }
        val saved =
            when (val result = saveAsAccount(draft, accountId, kind)) {
                is SaveResult.Done -> result.route
                is SaveResult.Stopped -> return result.outcome
            }
        if (kind == PendingKind.SAVE) return RouteActionOutcome.Saved(saved)
        val published =
            if (saved.isPublished) {
                saved
            } else {
                routeRepository
                    .publish(saved.id)
                    .getOrElse { error -> return failure(error, kind) }
                    .also { analytics.published(it.stops.size) }
            }
        draftRepository.markPublished(draft.draftId, published)
        return RouteActionOutcome.ReadyToShare(RouteSharePayload(published, routeShareLink(published)), resumed)
    }

    private suspend fun saveAsAccount(
        startingDraft: PersonalRouteDraft,
        accountId: String,
        kind: PendingKind,
    ): SaveResult {
        var draft = startingDraft
        if (draft.ownerAccountId != null && draft.ownerAccountId != accountId) draft = detached()
        repeat(SAVE_ATTEMPTS) { attempt ->
            val result = routeRepository.save(draft.route)
            val saved =
                result.getOrElse { error ->
                    val failure = error.routeFailure()
                    if (failure == PersonalRouteFailure.NotOwner && attempt < SAVE_ATTEMPTS - 1) {
                        draft = detached()
                        return@repeat
                    }
                    return SaveResult.Stopped(failure(error, kind))
                }
            draftRepository.acknowledgeSave(draft.draftId, draft.revision, saved, accountId)
            return SaveResult.Done(saved)
        }
        return SaveResult.Stopped(RouteActionOutcome.Failed(RouteActionError.SaveFailed))
    }

    private suspend fun detached(): PersonalRouteDraft {
        draftRepository.detach()
        return draftRepository.draft.first()
    }

    private suspend fun failure(
        error: Throwable,
        kind: PendingKind,
    ): RouteActionOutcome =
        when (val failure = error.routeFailure()) {
            PersonalRouteFailure.Unauthenticated -> {
                signInRequired(kind)
            }

            PersonalRouteFailure.Revoked -> {
                draftRepository.detach()
                RouteActionOutcome.Failed(RouteActionError.Revoked)
            }

            is PersonalRouteFailure.UnavailableStops -> {
                RouteActionOutcome.Failed(RouteActionError.BlockedStops(failure.exhibitionIds))
            }

            is PersonalRouteFailure.MissingLocation -> {
                RouteActionOutcome.Failed(RouteActionError.MissingLocation(failure.exhibitionIds))
            }

            PersonalRouteFailure.InvalidName -> {
                RouteActionOutcome.Failed(RouteActionError.Invalid(RouteSaveProblem.NAME_EMPTY))
            }

            else -> {
                RouteActionOutcome.Failed(RouteActionError.SaveFailed)
            }
        }

    private suspend fun signInRequired(kind: PendingKind): RouteActionOutcome {
        draftRepository.setPending(kind)
        return RouteActionOutcome.SignInRequired
    }

    private sealed interface SaveResult {
        data class Done(
            val route: PersonalRoute,
        ) : SaveResult

        data class Stopped(
            val outcome: RouteActionOutcome,
        ) : SaveResult
    }
}

/** The public link for a published route; `v` changes with every save so link previews refresh (E-D16). */
fun routeShareLink(route: PersonalRoute): String {
    val version = route.revision?.epochSeconds ?: 0
    return "$ROUTE_PAGE_BASE_URL/${route.id}?s=share&v=$version"
}

private const val ROUTE_PAGE_BASE_URL = "https://gallrmap.com/route"

/** One retry, after detaching, when the server says the route belongs to someone else. */
private const val SAVE_ATTEMPTS = 2
