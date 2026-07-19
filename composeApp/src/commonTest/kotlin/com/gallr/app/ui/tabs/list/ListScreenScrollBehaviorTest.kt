package com.gallr.app.ui.tabs.list

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListScreenScrollBehaviorTest {

    @Test
    fun `dragging toward later items hides filters after first item`() {
        val visible = filterVisibilityAfterUserScroll(
            currentlyVisible = true,
            scrollDeltaY = -12f,
            firstVisibleItemIndex = 1,
        )

        assertFalse(visible)
    }

    @Test
    fun `dragging toward earlier items shows filters`() {
        val visible = filterVisibilityAfterUserScroll(
            currentlyVisible = false,
            scrollDeltaY = 12f,
            firstVisibleItemIndex = 5,
        )

        assertTrue(visible)
    }

    @Test
    fun `dragging from the first item keeps filters visible`() {
        val visible = filterVisibilityAfterUserScroll(
            currentlyVisible = true,
            scrollDeltaY = -12f,
            firstVisibleItemIndex = 0,
        )

        assertTrue(visible)
    }

    @Test
    fun `no user drag keeps current filter visibility`() {
        val visible = filterVisibilityAfterUserScroll(
            currentlyVisible = false,
            scrollDeltaY = 0f,
            firstVisibleItemIndex = 5,
        )

        assertFalse(visible)
    }
}
