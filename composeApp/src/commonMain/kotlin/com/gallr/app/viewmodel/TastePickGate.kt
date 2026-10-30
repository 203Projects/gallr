package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.taste.TastePickState

/**
 * What must be out of the way before the taste sheet may open on its own (spec 101 FR-006). The required-update
 * screen and sign-in need no flag: the sheet's host is composed after the update screen returns, and it offers
 * only on Home's top screen.
 */
internal data class TastePickBlockers(
    /** The Home update nudge, until it is answered. */
    val updateNudgeShowing: Boolean,
    /** The launch splash, which the sheet would otherwise rise over. */
    val splashVisible: Boolean,
) {
    val any: Boolean get() = updateNudgeShowing || splashVisible
}

/**
 * Whether Home opens the taste sheet by itself: only for a visitor with nothing to go on (no save, visit,
 * follow or pick) who has not skipped it, and never over the update nudge or the splash.
 * [hasHistory] is null until the visitor's signals have been read, and then nothing is decided.
 */
internal fun shouldOfferTastePick(
    hasHistory: Boolean?,
    taste: TastePickState,
    blockers: TastePickBlockers,
): Boolean = hasHistory == false && taste.seed.isEmpty && !taste.skipped && !blockers.any

/** Whose saves, visits and follows the device holds ([accountId] null for a guest) and whether they have arrived. */
data class AccountHistory(
    val accountId: String?,
    /** The history is as known as it will be for now: arrived, or failed and waiting for a retry. */
    val settled: Boolean,
    /**
     * The account archive itself has arrived. A failed sync counts as [settled] but not delivered: follows and
     * visits the account already had can still land later, and they are not new.
     */
    val delivered: Boolean = settled,
) {
    companion object {
        val GUEST = AccountHistory(accountId = null, settled = true)
    }
}

internal fun accountHistory(
    auth: AuthState,
    sync: MyGallrSyncStatus,
    cloudBookmarksSettled: Boolean,
): AccountHistory =
    AccountHistory(
        accountId = (auth as? AuthState.Authenticated)?.user?.id,
        settled = historySettled(auth, sync, cloudBookmarksSettled),
        delivered =
            when (auth) {
                AuthState.Anonymous -> true
                AuthState.Loading -> false
                is AuthState.Authenticated -> sync == MyGallrSyncStatus.SYNCED && cloudBookmarksSettled
            },
    )

/**
 * Whether the device's saves, visits and follows are the visitor's whole history: always for a guest; for a
 * signed-in visitor only once the account archive (visits, follows) has arrived or failed and will be retried,
 * and the account's bookmarks have been read or given up on, because until then the lists read as empty and the
 * visitor would be asked again for a taste they already have.
 */
internal fun historySettled(
    auth: AuthState,
    sync: MyGallrSyncStatus,
    cloudBookmarksSettled: Boolean,
): Boolean =
    when (auth) {
        AuthState.Anonymous -> {
            true
        }

        AuthState.Loading -> {
            false
        }

        is AuthState.Authenticated -> {
            val archiveSettled = sync == MyGallrSyncStatus.SYNCED || sync == MyGallrSyncStatus.RETRY_NEEDED
            archiveSettled && cloudBookmarksSettled
        }
    }
