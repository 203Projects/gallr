package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 101 US7: the 내 취향 placeholder holds the banner's final height, so the picks arrive without a jump. */
@OptIn(ExperimentalTestApi::class)
class BannerSkeletonTest {
    @Test
    fun theSkeletonHasTheHeightOfTheBannerItStandsFor() =
        runComposeUiTest {
            setContent {
                // Side by side at a phone's width: the test window is too short to stack two banners.
                Row {
                    BannerPagerSkeleton(
                        description = "computing",
                        modifier = Modifier.width(PHONE_WIDTH).testTag("skeleton"),
                    )
                    BannerPager(
                        cards = listOf(RailCard(exhibition("a")), RailCard(exhibition("b"))),
                        lang = AppLanguage.KO,
                        bookmarkedIds = emptySet(),
                        onTap = { _, _ -> },
                        onBookmarkToggle = {},
                        onPageShown = { _, _ -> },
                        modifier = Modifier.width(PHONE_WIDTH).testTag("banner"),
                    )
                }
            }

            val skeleton = onNodeWithTag("skeleton").getBoundsInRoot()
            val banner = onNodeWithTag("banner").getBoundsInRoot()
            assertTrue(banner.bottom > banner.top)
            assertEquals(banner.bottom - banner.top, skeleton.bottom - skeleton.top)
            // A screen reader hears one line instead of empty boxes.
            onNodeWithContentDescription("computing").assertExists()
        }

    private fun exhibition(id: String) =
        Exhibition(
            id = id,
            nameKo = id,
            nameEn = id,
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 8, 1),
            closingDate = LocalDate(2026, 9, 30),
            isFeatured = true,
            latitude = 37.57,
            longitude = 126.98,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )

    private companion object {
        val PHONE_WIDTH = 390.dp
    }
}
