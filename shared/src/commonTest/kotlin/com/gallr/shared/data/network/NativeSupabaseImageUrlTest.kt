package com.gallr.shared.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSupabaseImageUrlTest {
    private val storageBase =
        "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/object/public/exhibition-images"

    @Test
    fun `keeps public object urls unchanged so native image loaders own sizing`() {
        val url = "$storageBase/0080_kumhomuseumofart.jpg"

        val result = nativeSupabaseImageUrl(url)

        assertEquals(url, result)
        assertTrue(!result!!.contains("/storage/v1/render/image/"))
    }

    @Test
    fun `normalizes legacy render urls back to public object urls`() {
        val url =
            "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/render/image/public/" +
                "exhibition-images/0080_kumhomuseumofart.jpg?width=600&quality=75&resize=contain&height=1800"

        val result = nativeSupabaseImageUrl(url)

        assertEquals("$storageBase/0080_kumhomuseumofart.jpg", result)
    }

    @Test
    fun `leaves non storage urls unchanged`() {
        val url = "https://images.example.com/a.jpg?width=600"

        assertEquals(url, nativeSupabaseImageUrl(url))
    }

    @Test
    fun `returns null for a null input`() {
        assertNull(nativeSupabaseImageUrl(null))
    }

    @Test
    fun `returns blank input unchanged`() {
        assertEquals("   ", nativeSupabaseImageUrl("   "))
    }
}
