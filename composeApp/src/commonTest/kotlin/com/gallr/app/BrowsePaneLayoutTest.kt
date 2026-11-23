package com.gallr.app

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowsePaneLayoutTest {
    private val limits = BrowsePaneLimits(600, 240, 160, 280..420)

    @Test
    fun compactWindowShowsOnePane() {
        val frame = IntRect(0, 0, 443, 994)
        assertEquals(BrowsePaneFrames(frame), browsePaneFrames(frame, emptyList(), true, limits))
    }

    @Test
    fun wideWindowWithoutFoldDisplaysMasterAndDetail() {
        val frame = IntRect(0, 0, 852, 883)
        assertEquals(
            BrowsePaneFrames(IntRect(366, 0, 852, 883), IntRect(0, 0, 366, 883)),
            browsePaneFrames(frame, emptyList(), true, limits),
        )
        assertNull(browsePaneFrames(frame, emptyList(), false, limits).master)
    }

    @Test
    fun shortWideWindowShowsOnePane() {
        val frame = IntRect(0, 0, 850, 128)
        assertEquals(BrowsePaneFrames(frame), browsePaneFrames(frame, emptyList(), true, limits))
    }

    @Test
    fun separatingVerticalHingeUsesLocalOriginsAndLeavesItsBoundsClear() {
        val frame = IntRect(24, 32, 876, 858)
        val hinge = BrowseFold(IntRect(420, 0, 452, 900), vertical = true)
        assertEquals(
            BrowsePaneFrames(IntRect(428, 0, 852, 826), IntRect(0, 0, 396, 826)),
            browsePaneFrames(frame, listOf(hinge), true, limits),
        )
    }

    @Test
    fun tabletopFoldSeparatesTopMasterAndBottomDetail() {
        val frame = IntRect(0, 36, 880, 840)
        val hinge = BrowseFold(IntRect(0, 420, 880, 430), vertical = false)
        assertEquals(
            BrowsePaneFrames(IntRect(0, 394, 880, 804), IntRect(0, 0, 880, 384)),
            browsePaneFrames(frame, listOf(hinge), true, limits),
        )
    }

    @Test
    fun foldThatLeavesATinyPaneFallsBackToOneSafePane() {
        val frame = IntRect(0, 0, 852, 830)
        val hinge = BrowseFold(IntRect(0, 100, 852, 110), vertical = false)
        assertEquals(
            BrowsePaneFrames(IntRect(0, 110, 852, 830)),
            browsePaneFrames(frame, listOf(hinge), true, limits),
        )
    }

    @Test
    fun zeroWidthSeparatingFoldStillDefinesTwoPanes() {
        val frame = IntRect(0, 32, 852, 858)
        val fold = BrowseFold(IntRect(426, 0, 426, 900), vertical = true)
        assertEquals(
            BrowsePaneFrames(IntRect(426, 0, 852, 826), IntRect(0, 0, 426, 826)),
            browsePaneFrames(frame, listOf(fold), true, limits),
        )
    }

    @Test
    fun offWindowFoldDoesNotChangeNormalLayout() {
        val frame = IntRect(20, 40, 872, 843)
        val unrelated = BrowseFold(IntRect(480, 900, 490, 1300), vertical = true)
        assertEquals(
            browsePaneFrames(frame, emptyList(), true, limits),
            browsePaneFrames(frame, listOf(unrelated), true, limits),
        )
    }

    @Test
    fun bottomTabsStayClearOfASeparatingHinge() {
        val frame = IntRect(12, 720, 852, 776)
        val hinge = BrowseFold(IntRect(369, 0, 403, 792), vertical = true)
        val tabs = browseTabFrames(frame, listOf(hinge), 4, 48)
        assertEquals(
            listOf(
                IntRect(0, 0, 178, 56),
                IntRect(178, 0, 357, 56),
                IntRect(391, 0, 615, 56),
                IntRect(615, 0, 840, 56),
            ),
            tabs,
        )
    }
}
