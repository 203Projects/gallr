package com.gallr.shared.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

class EditorLocalizationTest {

    private fun editor(
        nameKo: String = "김민정",
        nameEn: String = "Minjung Kim",
        titleKo: String = "큐레이터",
        titleEn: String = "Curator",
        bioKo: String = "한국어 소개",
        bioEn: String = "English bio",
        curationDescriptionKo: String = "한국어 큐레이션 문장",
        curationDescriptionEn: String = "English curatorial statement",
        isActive: Boolean = true,
        activeFrom: LocalDate = LocalDate(2026, 1, 1),
        activeTo: LocalDate? = null,
    ) = Editor(
        id = "minjung-kim",
        nameKo = nameKo,
        nameEn = nameEn,
        titleKo = titleKo,
        titleEn = titleEn,
        bioKo = bioKo,
        bioEn = bioEn,
        curationDescriptionKo = curationDescriptionKo,
        curationDescriptionEn = curationDescriptionEn,
        isActive = isActive,
        activeFrom = activeFrom,
        activeTo = activeTo,
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

    @Test
    fun `localized curation statement is independent from bio and falls back to Korean`() {
        assertEquals(
            "English curatorial statement",
            editor().localizedCurationDescription(AppLanguage.EN),
        )
        assertEquals(
            "한국어 큐레이션 문장",
            editor(curationDescriptionEn = "").localizedCurationDescription(AppLanguage.EN),
        )
        assertEquals(
            "한국어 큐레이션 문장",
            editor().localizedCurationDescription(AppLanguage.KO),
        )
        assertEquals("English bio", editor().localizedBio(AppLanguage.EN))
    }
}
