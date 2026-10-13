package com.gallr.app.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MapPinImageTest {
    private val cover = "https://example.supabase.co/storage/v1/object/public/exhibition-images/a|b:c.jpg?v=2"

    @Test
    fun `an image id carries the cover and the saved state back out`() {
        val saved = parseMapPinImageId(mapPinImageId(cover, saved = true))
        val unsaved = parseMapPinImageId(mapPinImageId(cover, saved = false))

        assertEquals(MapPinImage(coverUrl = cover, saved = true), saved)
        assertEquals(MapPinImage(coverUrl = cover, saved = false), unsaved)
    }

    @Test
    fun `a pin without a cover still has an image`() {
        val missing = parseMapPinImageId(mapPinImageId(null, saved = false))
        val blank = parseMapPinImageId(mapPinImageId("  ", saved = true))

        assertEquals(MapPinImage(coverUrl = null, saved = false), missing)
        assertEquals(MapPinImage(coverUrl = null, saved = true), blank)
    }

    @Test
    fun `saved and unsaved pins of one cover are different images`() {
        assertNotEquals(mapPinImageId(cover, saved = true), mapPinImageId(cover, saved = false))
    }

    @Test
    fun `ids from the map style are not pin images`() {
        assertNull(parseMapPinImageId("airport-15"))
        assertNull(parseMapPinImageId(""))
        assertNull(parseMapPinImageId("gallr-pin:x:$cover"))
    }

    @Test
    fun `a group is pictured by its first exhibition that has a cover`() {
        assertEquals("b.jpg", representativeCoverUrl(listOf(null, " ", "b.jpg", "c.jpg")))
        assertNull(representativeCoverUrl(listOf(null, "")))
        assertNull(representativeCoverUrl(emptyList()))
    }
}
