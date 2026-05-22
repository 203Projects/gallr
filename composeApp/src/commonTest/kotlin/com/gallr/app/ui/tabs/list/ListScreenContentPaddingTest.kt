package com.gallr.app.ui.tabs.list

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import kotlin.test.Test
import kotlin.test.assertEquals

class ListScreenContentPaddingTest {

    @Test
    fun `list content padding includes navigation bar inset at bottom`() {
        val padding = listScreenContentPadding(navigationBarInset = 34.dp)

        assertEquals(GallrSpacing.md, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(GallrSpacing.md, padding.calculateTopPadding())
        assertEquals(GallrSpacing.md, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(GallrSpacing.md + 34.dp, padding.calculateBottomPadding())
    }
}
