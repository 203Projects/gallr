package com.gallr.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppNavigationStateTest {
    @Test
    fun `selecting a tab always returns to the tab destination`() {
        val state = AppNavigationState()
        state.showEvent("event-one")

        state.selectTab(2)

        assertEquals(2, state.selectedTab)
        assertEquals(AppDestination.Tabs, state.destination)
    }

    @Test
    fun `detail destinations retain their typed identifiers`() {
        val state = AppNavigationState()
        state.showEditor("editor-one")

        val destination = assertIs<AppDestination.EditorDetail>(state.destination)
        assertEquals("editor-one", destination.editorId)
    }
}
