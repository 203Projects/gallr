package com.gallr.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec 094 FR-011: the tab bar steps aside only while the keyboard is open. */
class TabBarVisibilityTest {
    @Test
    fun theBarShowsWithoutAKeyboard() {
        assertTrue(isTabBarVisible(imeBottomPx = 0))
    }

    @Test
    fun theBarStepsAsideWhileTheKeyboardIsOpen() {
        assertFalse(isTabBarVisible(imeBottomPx = 1))
        assertFalse(isTabBarVisible(imeBottomPx = 900))
    }
}
