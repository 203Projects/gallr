package com.gallr.shared.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupabaseImageTransformTest {

    private val storageBase =
        "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/object/public/exhibition-images"

    @Test
    fun `rewrites object public url to render endpoint with resize params`() {
        val url = "$storageBase/0080_kumhomuseumofart.jpg"

        val result = supabaseImageTransform(url, width = 600)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/0080_kumhomuseumofart.jpg?width=600&quality=75&resize=cover",
            result,
        )
    }

    @Test
    fun `preserves existing query string on the object url`() {
        val url = "$storageBase/x.jpg?token=abc"

        val result = supabaseImageTransform(url, width = 600)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?token=abc&width=600&quality=75&resize=cover",
            result,
        )
    }

    @Test
    fun `honors a custom quality`() {
        val result = supabaseImageTransform("$storageBase/x.jpg", width = 600, quality = 90)

        assertEquals(
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/x.jpg?width=600&quality=90&resize=cover",
            result,
        )
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

    private fun widthParam(url: String): Int =
        Regex("[?&]width=(\\d+)").find(url)!!.groupValues[1].toInt()

    private fun qualityParam(url: String): Int =
        Regex("[?&]quality=(\\d+)").find(url)!!.groupValues[1].toInt()
}
