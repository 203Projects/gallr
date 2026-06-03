package com.gallr.shared.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseImageTransformTest {

    private val storageBase =
        "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/object/public/exhibition-images"

    @Test
    fun `rewrites object public url to render endpoint with default contain resize and derived height`() {
        val url = "$storageBase/0080_kumhomuseumofart.jpg"

        val result = supabaseImageTransform(url, width = 600)

        // Default resize is `contain` (preserves aspect, no server crop) with an
        // explicit derived height so the source always fits and Compose owns the
        // only crop. Derived height = min(width * 3, 2500) = 1800 for width 600.
        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/0080_kumhomuseumofart.jpg?width=600&quality=75&resize=contain&height=1800",
            result,
        )
    }

    @Test
    fun `preserves existing query string on the object url`() {
        val url = "$storageBase/x.jpg?token=abc"

        val result = supabaseImageTransform(url, width = 600)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?token=abc&width=600&quality=75&resize=contain&height=1800",
            result,
        )
    }

    @Test
    fun `honors a custom quality`() {
        val result = supabaseImageTransform("$storageBase/x.jpg", width = 600, quality = 90)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?width=600&quality=90&resize=contain&height=1800",
            result,
        )
    }

    @Test
    fun `cover resize with explicit width and height crops to the exact box`() {
        // The 16:9 hero deliberately uses cover + an explicit height so the server
        // crops to exactly the visible box (no client-side double-crop).
        val result = supabaseImageTransform(
            "$storageBase/x.jpg",
            width = 1600,
            resize = RESIZE_COVER,
            height = 900,
        )

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?width=1600&quality=75&resize=cover&height=900",
            result,
        )
    }

    @Test
    fun `cover resize without an explicit height omits the height param`() {
        // cover is only requested with both dimensions; absent a height we do NOT
        // synthesize one (the derived-height guard is contain-specific).
        val result = supabaseImageTransform("$storageBase/x.jpg", width = 600, resize = RESIZE_COVER)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?width=600&quality=75&resize=cover",
            result,
        )
    }

    @Test
    fun `an explicit height overrides the derived contain height`() {
        val result = supabaseImageTransform("$storageBase/x.jpg", width = 600, height = 400)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?width=600&quality=75&resize=contain&height=400",
            result,
        )
    }

    @Test
    fun `derived contain height is clamped to the supabase max dimension`() {
        // width 1600 -> derived 4800 -> clamped to 2500.
        val result = supabaseImageTransform("$storageBase/x.jpg", width = 1600)

        assertEquals(2500, heightParam(result!!))
        assertEquals(1600, widthParam(result))
    }

    @Test
    fun `clamps width to the supabase 1 to 2500 range`() {
        val tooBig = supabaseImageTransform("$storageBase/x.jpg", width = 9000)
        val tooSmall = supabaseImageTransform("$storageBase/x.jpg", width = 0)

        assertEquals(2500, widthParam(tooBig!!))
        assertEquals(1, widthParam(tooSmall!!))
    }

    @Test
    fun `clamps quality to the supabase 20 to 100 range`() {
        val tooLow = supabaseImageTransform("$storageBase/x.jpg", width = 600, quality = 5)
        val tooHigh = supabaseImageTransform("$storageBase/x.jpg", width = 600, quality = 200)

        assertEquals(20, qualityParam(tooLow!!))
        assertEquals(100, qualityParam(tooHigh!!))
    }

    @Test
    fun `clamps an explicit height to the supabase 1 to 2500 range`() {
        val tooBig = supabaseImageTransform("$storageBase/x.jpg", width = 600, height = 9000)
        val tooSmall = supabaseImageTransform("$storageBase/x.jpg", width = 600, height = 0)

        assertEquals(2500, heightParam(tooBig!!))
        assertEquals(1, heightParam(tooSmall!!))
    }

    @Test
    fun `returns null for a null input`() {
        assertNull(supabaseImageTransform(null, width = 600))
    }

    @Test
    fun `returns input unchanged for a blank url`() {
        assertEquals("   ", supabaseImageTransform("   ", width = 600))
    }

    @Test
    fun `leaves a non-supabase-storage url untouched`() {
        val url = "https://images.example.com/a.jpg"

        assertEquals(url, supabaseImageTransform(url, width = 600))
    }

    @Test
    fun `leaves an already-render url untouched`() {
        val url = "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
            "exhibition-images/x.jpg?width=300&quality=75&resize=cover"

        assertEquals(url, supabaseImageTransform(url, width = 600))
    }

    @Test
    fun `default resize is contain not cover`() {
        val result = supabaseImageTransform("$storageBase/x.jpg", width = 600)

        assertTrue(result!!.contains("resize=contain"))
        assertTrue(!result.contains("resize=cover"))
    }

    private fun widthParam(url: String): Int =
        Regex("[?&]width=(\\d+)").find(url)!!.groupValues[1].toInt()

    private fun heightParam(url: String): Int =
        Regex("[?&]height=(\\d+)").find(url)!!.groupValues[1].toInt()

    private fun qualityParam(url: String): Int =
        Regex("[?&]quality=(\\d+)").find(url)!!.groupValues[1].toInt()
}
