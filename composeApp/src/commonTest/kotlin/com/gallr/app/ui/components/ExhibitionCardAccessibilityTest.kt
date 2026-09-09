package com.gallr.app.ui.components

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ExhibitionCardAccessibilityTest {
    @Test
    fun `card accessibility label identifies localized exhibition venue and dates`() {
        val exhibition =
            Exhibition(
                id = "one",
                nameKo = "전시",
                nameEn = "Exhibition",
                venueNameKo = "미술관",
                venueNameEn = "Museum",
                cityKo = "서울",
                cityEn = "Seoul",
                regionKo = "종로구",
                regionEn = "Jongno-gu",
                openingDate = LocalDate(2026, 8, 1),
                closingDate = LocalDate(2026, 8, 31),
                isFeatured = false,
                latitude = 37.57,
                longitude = 126.98,
                descriptionKo = "",
                descriptionEn = "",
                addressKo = "",
                addressEn = "",
                coverImageUrl = null,
            )

        assertEquals(
            "Exhibition, Museum, Aug 1 – Aug 31, 2026",
            exhibitionCardAccessibilityLabel(exhibition, AppLanguage.EN),
        )
        assertEquals(
            "전시, 미술관, 2026.08.01 – 2026.08.31",
            exhibitionCardAccessibilityLabel(exhibition, AppLanguage.KO),
        )
        assertEquals(
            "Exhibition, Museum, Aug 1 – Aug 31, 2026, " +
                "WHY THIS · BECAUSE YOU SAVED “Thread Routes” · SAME ARTIST: KIMSOOJA",
            exhibitionCardAccessibilityLabel(
                exhibition = exhibition,
                language = AppLanguage.EN,
                contextLabel = "WHY THIS · BECAUSE YOU SAVED “Thread Routes” · SAME ARTIST: KIMSOOJA",
            ),
        )
        assertEquals("북마크 추가", bookmarkContentDescription(false, AppLanguage.KO))
        assertEquals("Remove bookmark", bookmarkContentDescription(true, AppLanguage.EN))
    }
}
