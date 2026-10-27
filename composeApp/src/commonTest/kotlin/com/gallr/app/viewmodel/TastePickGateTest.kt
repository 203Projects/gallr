package com.gallr.app.viewmodel

import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec 101 FR-001, FR-002 and FR-006: when the taste sheet opens on its own. */
class TastePickGateTest {
    private val clear = TastePickBlockers(updateRequired = false, updateNudgeShowing = false, accountOpen = false)

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
    fun `the update screen the update nudge and sign in all come first`() {
        listOf(
            clear.copy(updateRequired = true),
            clear.copy(updateNudgeShowing = true),
            clear.copy(accountOpen = true),
        ).forEach { blockers ->
            assertFalse(shouldOfferTastePick(hasHistory = false, taste = TastePickState.EMPTY, blockers = blockers))
        }
    }
}
