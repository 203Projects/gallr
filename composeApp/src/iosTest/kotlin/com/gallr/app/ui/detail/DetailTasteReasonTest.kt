package com.gallr.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test

// Value: protects=an exhibition opened from a 내 취향 card shows the card's reason under its title, read with it,
// and a page opened any other way shows none (spec 101 FR-011); fails_when=ExhibitionDetailScreen drops
// tasteReason, renders it outside the title's merged node, or shows a stale reason; why_new=TasteReasonLineTest
// covers only the words and App.kt wiring has no test; seam=none; no_claim=which openings pass a reason

/** Spec 101 FR-011: the reason a 내 취향 card gave travels to the exhibition page with the title. */
@OptIn(ExperimentalTestApi::class)
class DetailTasteReasonTest {
    @Test
    fun theTasteReasonIsReadWithTheTitleAndIsAbsentWithoutOne() =
        runComposeUiTest {
            var reason by mutableStateOf<String?>(REASON)
            setContent {
                ExhibitionDetailScreen(
                    exhibition = exhibition,
                    lang = AppLanguage.KO,
                    isBookmarked = false,
                    onBookmarkToggle = {},
                    onBack = {},
                    tasteReason = reason,
                )
            }

            // One merged node carries both, so a screen reader hears the reason with its title.
            onNode(hasText(TITLE) and hasText(REASON)).assertExists()

            reason = null
            waitForIdle()

            onNodeWithText(REASON).assertDoesNotExist()
            onNode(hasText(TITLE) and hasText(REASON)).assertDoesNotExist()
        }

    private val exhibition =
        Exhibition(
            id = "picked",
            nameKo = TITLE,
            nameEn = "Picked show",
            venueNameKo = "장소",
            venueNameEn = "Venue",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "",
            regionEn = "",
            openingDate = LocalDate(2026, 9, 1),
            closingDate = LocalDate(2026, 11, 30),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )

    private companion object {
        const val TITLE = "고른 전시"
        const val REASON = "좋아하는 스타일: 미니멀"
    }
}
