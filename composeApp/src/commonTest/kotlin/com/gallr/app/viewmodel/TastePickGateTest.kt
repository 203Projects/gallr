package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec 101 FR-001, FR-002 and FR-006: when the taste sheet opens on its own. */
class TastePickGateTest {
    private val clear = TastePickBlockers(updateNudgeShowing = false, splashVisible = false)

    @Test
    fun `a visitor with no history no picks and no skip is asked`() {
        assertTrue(shouldOfferTastePick(hasHistory = false, taste = TastePickState.EMPTY, blockers = clear))
    }

    @Test
    fun `history picks or a skip mean the sheet stays closed`() {
        assertFalse(shouldOfferTastePick(hasHistory = true, taste = TastePickState.EMPTY, blockers = clear))
        assertFalse(
            shouldOfferTastePick(
                hasHistory = false,
                taste = TastePickState(seed = TasteSeed(termIds = setOf("theme:city"))),
                blockers = clear,
            ),
        )
        assertFalse(shouldOfferTastePick(hasHistory = false, taste = TastePickState(skipped = true), blockers = clear))
    }

    @Test
    fun `nothing is decided before history is known`() {
        assertFalse(shouldOfferTastePick(hasHistory = null, taste = TastePickState.EMPTY, blockers = clear))
    }

    @Test
    fun `the update nudge and the launch splash both come first`() {
        listOf(
            clear.copy(updateNudgeShowing = true),
            clear.copy(splashVisible = true),
        ).forEach { blockers ->
            assertFalse(shouldOfferTastePick(hasHistory = false, taste = TastePickState.EMPTY, blockers = blockers))
        }
    }

    @Test
    fun `a signed in visitor history counts only once the account archive has arrived`() {
        val signedIn = AuthState.Authenticated(GallrUser("u1", "하나", null))

        assertTrue(historySettled(AuthState.Anonymous, MyGallrSyncStatus.DEVICE_ONLY, cloudBookmarksSettled = false))
        assertFalse(historySettled(AuthState.Loading, MyGallrSyncStatus.DEVICE_ONLY, cloudBookmarksSettled = true))
        assertFalse(historySettled(signedIn, MyGallrSyncStatus.DEVICE_ONLY, cloudBookmarksSettled = true))
        assertFalse(historySettled(signedIn, MyGallrSyncStatus.SYNCING, cloudBookmarksSettled = true))
        assertTrue(historySettled(signedIn, MyGallrSyncStatus.SYNCED, cloudBookmarksSettled = true))
        assertTrue(historySettled(signedIn, MyGallrSyncStatus.RETRY_NEEDED, cloudBookmarksSettled = true))
        // The archive alone is not enough: an account whose history is only saves would read as empty.
        assertFalse(historySettled(signedIn, MyGallrSyncStatus.SYNCED, cloudBookmarksSettled = false))
    }

    @Test
    fun `account history names the signed in account and settles with its archive and bookmarks`() {
        val signedIn = AuthState.Authenticated(GallrUser("u1", "하나", null))

        assertEquals(
            AccountHistory("u1", settled = false),
            accountHistory(signedIn, MyGallrSyncStatus.SYNCING, cloudBookmarksSettled = true),
        )
        assertEquals(
            AccountHistory("u1", settled = true),
            accountHistory(signedIn, MyGallrSyncStatus.SYNCED, cloudBookmarksSettled = true),
        )
        assertEquals(
            AccountHistory("u1", settled = false),
            accountHistory(signedIn, MyGallrSyncStatus.RETRY_NEEDED, cloudBookmarksSettled = false),
        )
        assertEquals(
            AccountHistory.GUEST,
            accountHistory(AuthState.Anonymous, MyGallrSyncStatus.DEVICE_ONLY, cloudBookmarksSettled = false),
        )
        assertEquals(
            AccountHistory(accountId = null, settled = false),
            accountHistory(AuthState.Loading, MyGallrSyncStatus.DEVICE_ONLY, cloudBookmarksSettled = true),
        )
    }
}
