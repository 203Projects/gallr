package com.gallr.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HexColorTest {

    @Test
    fun `parseHexColor returns ARGB long for valid 6-digit hex with hash`() {
        assertEquals(0xFF0099FFL, parseHexColor("#0099FF"))
    }

    @Test
    fun `parseHexColor returns ARGB long for valid 6-digit hex without hash`() {
        assertEquals(0xFFFF5C5CL, parseHexColor("FF5C5C"))
    }

    @Test
    fun `parseHexColor is case insensitive`() {
        assertEquals(0xFF0099FFL, parseHexColor("#0099ff"))
    }

    @Test
    fun `parseHexColor returns null for null input`() {
        assertNull(parseHexColor(null))
    }

    @Test
    fun `parseHexColor returns null for empty string`() {
        assertNull(parseHexColor(""))
    }

    @Test
    fun `parseHexColor returns null for non-hex characters`() {
        assertNull(parseHexColor("#ZZZZZZ"))
    }

    @Test
    fun `parseHexColor returns null for wrong length`() {
        assertNull(parseHexColor("#FFF"))
        assertNull(parseHexColor("#FF00"))
        assertNull(parseHexColor("#FF00FFFF"))
    }
}
