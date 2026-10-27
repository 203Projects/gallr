package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlin.test.Test
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

        assertTrue(historySettled(AuthState.Anonymous, MyGallrSyncStatus.DEVICE_ONLY))
        assertFalse(historySettled(AuthState.Loading, MyGallrSyncStatus.DEVICE_ONLY))
        assertFalse(historySettled(signedIn, MyGallrSyncStatus.DEVICE_ONLY))
        assertFalse(historySettled(signedIn, MyGallrSyncStatus.SYNCING))
        assertTrue(historySettled(signedIn, MyGallrSyncStatus.SYNCED))
        assertTrue(historySettled(signedIn, MyGallrSyncStatus.RETRY_NEEDED))
    }
}
