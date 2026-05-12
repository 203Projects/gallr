package com.gallr.shared.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GuestEditorLocalizationTest {

    private fun editor(
        nameKo: String = "김민정",
        nameEn: String = "Minjung Kim",
        titleKo: String = "큐레이터",
        titleEn: String = "Curator",
        bioKo: String = "한국어 소개",
        bioEn: String = "English bio",
    ) = GuestEditor(
        id = "minjung-kim",
        nameKo = nameKo,
        nameEn = nameEn,
        titleKo = titleKo,
        titleEn = titleEn,
        bioKo = bioKo,
        bioEn = bioEn,
    )

    @Test
    fun `localizedName returns English when language is EN and English is set`() {
        assertEquals("Minjung Kim", editor().localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedName falls back to Korean when English is empty`() {
        assertEquals("김민정", editor(nameEn = "").localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedName returns Korean when language is KO`() {
        assertEquals("김민정", editor().localizedName(AppLanguage.KO))
    }

    @Test
    fun `localizedTitle follows the same fallback pattern as name`() {
        assertEquals("Curator", editor().localizedTitle(AppLanguage.EN))
        assertEquals("큐레이터", editor(titleEn = "").localizedTitle(AppLanguage.EN))
        assertEquals("큐레이터", editor().localizedTitle(AppLanguage.KO))
    }

    @Test
    fun `localizedBio follows the same fallback pattern as name`() {
        assertEquals("English bio", editor().localizedBio(AppLanguage.EN))
        assertEquals("한국어 소개", editor(bioEn = "").localizedBio(AppLanguage.EN))
        assertEquals("한국어 소개", editor().localizedBio(AppLanguage.KO))
    }
}
