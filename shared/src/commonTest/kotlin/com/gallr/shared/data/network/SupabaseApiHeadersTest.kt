package com.gallr.shared.data.network

import io.ktor.http.HeadersBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SupabaseApiHeadersTest {
    @Test
    fun `legacy jwt api key is sent as apikey and bearer for compatibility`() {
        val apiKey = "eyJlegacy.header.signature"
        val headers = HeadersBuilder()

        headers.appendSupabaseApiKey(apiKey)

        assertEquals(apiKey, headers["apikey"])
        assertEquals("Bearer $apiKey", headers["Authorization"])
    }

    @Test
    fun `publishable api key is never sent as a bearer token`() {
        val apiKey = "sb_publishable_public-test-key"
        val headers = HeadersBuilder()

        headers.appendSupabaseApiKey(apiKey)

        assertEquals(apiKey, headers["apikey"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun `secret api key is rejected from public clients`() {
        val headers = HeadersBuilder()

        assertFailsWith<IllegalArgumentException> {
            headers.appendSupabaseApiKey("sb_secret_must-not-ship")
        }
    }

    @Test
    fun `legacy service role jwt is rejected from public clients`() {
        val headers = HeadersBuilder()
        val serviceRoleJwt =
            "eyJhbGciOiJIUzI1NiJ9" +
                ".eyJyb2xlIjoic2VydmljZV9yb2xlIn0" +
                ".signature"

        assertFailsWith<IllegalArgumentException> {
            headers.appendSupabaseApiKey(serviceRoleJwt)
        }
    }

    @Test
    fun `blank api key is rejected before a request is built`() {
        val headers = HeadersBuilder()

        assertFailsWith<IllegalArgumentException> {
            headers.appendSupabaseApiKey("   ")
        }
    }
}
