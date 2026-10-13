package com.gallr.app.ui.tabs.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec 091 (R4): the dot follows the first card that reads as fully on screen; short rails only. */
class RailDotsTest {
    @Test
    fun theDotFollowsTheFirstFullyVisibleCard() {
        assertEquals(0, highlightedCard(firstVisibleIndex = 0, firstVisibleOffsetPx = 0, spacingPx = 16, count = 6))
        // Hidden by less than half the gap: still the first card.
        assertEquals(0, highlightedCard(firstVisibleIndex = 0, firstVisibleOffsetPx = 8, spacingPx = 16, count = 6))
        // Hidden by more than half the gap: the next card is the first fully visible one.
        assertEquals(1, highlightedCard(firstVisibleIndex = 0, firstVisibleOffsetPx = 9, spacingPx = 16, count = 6))
        assertEquals(3, highlightedCard(firstVisibleIndex = 2, firstVisibleOffsetPx = 120, spacingPx = 16, count = 6))
    }

    @Test
    fun theLastCardIsNeverOvershot() {
        assertEquals(5, highlightedCard(firstVisibleIndex = 5, firstVisibleOffsetPx = 200, spacingPx = 16, count = 6))
        assertEquals(0, highlightedCard(firstVisibleIndex = 0, firstVisibleOffsetPx = 0, spacingPx = 16, count = 0))
    }

    @Test
    fun onlyShortRailsShowDots() {
        assertFalse(showsRailDots(1))
        assertTrue(showsRailDots(2))
        assertTrue(showsRailDots(10))
        assertFalse(showsRailDots(11))
    }
}
