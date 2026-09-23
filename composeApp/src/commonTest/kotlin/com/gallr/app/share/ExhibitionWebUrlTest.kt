package com.gallr.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// Mirrors web/tests/slug.test.js: the QR on a share card must land on the page the
// Eleventy build generates for the same exhibition.
class ExhibitionWebUrlTest {
    @Test
    fun `slugify matches the web build for latin text`() {
        assertEquals("void-forms", slugifyForWeb("Void Forms"))
        assertEquals("line-form", slugifyForWeb("Line & Form"))
        assertEquals("trim-spaces", slugifyForWeb("  Trim   Spaces  "))
        assertEquals("already-hyphen", slugifyForWeb("Already-hyphen"))
        assertEquals("uppercase", slugifyForWeb("UPPERCASE"))
        assertEquals("", slugifyForWeb(""))
        assertEquals("void-forms", slugifyForWeb("VOID — FORMS"))
    }

    @Test
    fun `slugify keeps Korean and CJK letters`() {
        assertEquals("한국-단색화의-계보", slugifyForWeb("한국 단색화의 계보"))
        assertEquals("風徑無住-between-the-feathers", slugifyForWeb("風徑無住: Between the Feathers"))
    }

    @Test
    fun `slugify applies NFKC like the web build`() {
        assertEquals("void-1", slugifyForWeb("ＶＯＩＤ　１"))
    }

    @Test
    fun `slug prefers English name and appends the first four id characters`() {
        val id = "abcd1234-5678-9012-3456-789012345678"
        assertEquals("void-forms-abcd", exhibitionWebSlug(nameEn = "Void Forms", nameKo = "보이드 폼", id = id))
        assertEquals("보이드-폼-abcd", exhibitionWebSlug(nameEn = "", nameKo = "보이드 폼", id = id))
        assertEquals("abcd", exhibitionWebSlug(nameEn = "", nameKo = "", id = id))
        assertNotEquals(
            exhibitionWebSlug(nameEn = "Annual Show", nameKo = "", id = "1111aaaa"),
            exhibitionWebSlug(nameEn = "Annual Show", nameKo = "", id = "2222bbbb"),
        )
    }

    @Test
    fun `web url points at the exhibition page on gallrmap`() {
        assertEquals(
            "https://gallrmap.com/exhibitions/afterimage-choi-ean-1308/",
            exhibitionWebUrl(nameEn = "Afterimage | Choi Ean", nameKo = "Afterimage | 최이안", id = "13088ec8-93d7"),
        )
    }
}
