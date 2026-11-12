package com.gallr.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarkBounceTest {
    @Test
    fun `saving bounces the heart`() {
        assertEquals(BOOKMARK_BOUNCE_SCALE, bookmarkHeartScale(bouncing = true, reduceMotion = false))
        assertEquals(1f, bookmarkHeartScale(bouncing = false, reduceMotion = false))
    }

    @Test
    fun `under reduced motion the heart never grows`() {
        assertEquals(1f, bookmarkHeartScale(bouncing = true, reduceMotion = true))
        assertEquals(1f, bookmarkHeartScale(bouncing = false, reduceMotion = true))
    }
}
