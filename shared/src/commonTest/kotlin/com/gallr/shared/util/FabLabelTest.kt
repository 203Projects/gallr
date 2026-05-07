package com.gallr.shared.util

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class FabLabelTest {

    @Test
    fun `KO single token returns same token`() {
        assertEquals("루프랩", fabLabel("루프랩", AppLanguage.KO))
    }

    @Test
    fun `KO multi token returns first token only`() {
        assertEquals("루프랩", fabLabel("루프랩 부산 2025", AppLanguage.KO))
    }

    @Test
    fun `KO empty input returns empty string`() {
        assertEquals("", fabLabel("", AppLanguage.KO))
    }

    @Test
    fun `EN single token returns uppercased token`() {
        assertEquals("BIENNALE", fabLabel("Biennale", AppLanguage.EN))
    }

    @Test
    fun `EN two tokens returns uppercased and newline joined`() {
        assertEquals("LOOP\nLAB", fabLabel("Loop Lab", AppLanguage.EN))
    }

    @Test
    fun `EN three plus tokens returns only first two tokens`() {
        assertEquals("LOOP\nLAB", fabLabel("Loop Lab Busan 2025", AppLanguage.EN))
    }

    @Test
    fun `EN empty input returns empty string`() {
        assertEquals("", fabLabel("", AppLanguage.EN))
    }

    @Test
    fun `EN extra whitespace is collapsed before token selection`() {
        assertEquals("LOOP\nLAB", fabLabel("  Loop   Lab  ", AppLanguage.EN))
    }
}
