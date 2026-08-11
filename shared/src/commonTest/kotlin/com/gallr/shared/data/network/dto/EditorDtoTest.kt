package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorDtoTest {
    @Test
    fun `maps biography and curatorial statement independently`() {
        val editor =
            EditorDto(
                id = "mina-kim",
                nameKo = "김미나",
                titleKo = "에디터",
                bioKo = "개인 약력",
                bioEn = "Personal biography",
                curationDescriptionKo = "큐레이션 문장",
                curationDescriptionEn = "Curatorial statement",
            ).toDomain()

        assertEquals("Personal biography", editor.localizedBio(AppLanguage.EN))
        assertEquals(
            "Curatorial statement",
            editor.localizedCurationDescription(AppLanguage.EN),
        )
    }

    @Test
    fun `older payload fallback preserves the existing public introduction`() {
        val editor =
            EditorDto(
                id = "gallr-editors",
                nameKo = "gallr 에디터즈",
                titleKo = "에디터",
                bioKo = "gallr 팀이 선정한 상시 큐레이션",
                bioEn = "Always-on curation selected by gallr",
            ).toDomain()

        assertEquals(
            "Always-on curation selected by gallr",
            editor.localizedCurationDescription(AppLanguage.EN),
        )
    }
}
