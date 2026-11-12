package com.gallr.app.ui.diary

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.ui.detail.ExhibitionDetailScreen
import com.gallr.app.viewmodel.DiaryEntryFailure
import com.gallr.app.viewmodel.DiaryEntryUiState
import com.gallr.app.viewmodel.DiaryVisitRowState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.diary.VisitFeeling
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

// Value: protects=the exhibition page's diary row reads the entry's state as one button, and an upcoming show has no
// row (spec 103 US1, FR-010); fails_when=DiaryVisitRow loses a state's words, splits into several nodes, or the detail
// screen shows the row for a show that has not opened; why_new=the visit prompt it replaces had no test;
// seam=DiaryVisitRow and ExhibitionDetailScreen with a hoisted DiaryEntryControl; no_claim=the sheet's contents

/** Spec 103 US1: the diary row on the exhibition page. */
@OptIn(ExperimentalTestApi::class)
class DiaryVisitRowTest {
    @Test
    fun withNoEntryTheRowInvitesTheVisitAsOneButton() =
        runComposeUiTest {
            var opened = 0
            setContent { DiaryVisitRow(DiaryVisitRowState.NoEntry, null, AppLanguage.KO, onClick = { opened += 1 }) }

            onNode(hasText("다녀왔어요") and hasText("→") and hasClickAction()).performClick()
            assertEquals(1, opened)
        }

    @Test
    fun aDatedEntryReadsItsDateAndFeelingWithEdit() =
        runComposeUiTest {
            setContent {
                DiaryVisitRow(
                    DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), VisitFeeling.LOVED),
                    null,
                    AppLanguage.KO,
                    onClick = {},
                )
            }

            onNode(hasText("11월 9일 다녀옴 · 좋았어요") and hasText("수정") and hasClickAction()).assertExists()
        }

    @Test
    fun aDatedEntryWithoutAFeelingLeavesTheFeelingOut() =
        runComposeUiTest {
            setContent {
                DiaryVisitRow(DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), null), null, AppLanguage.KO, {})
            }

            onNodeWithText("11월 9일 다녀옴").assertExists()
            onNodeWithText("11월 9일 다녀옴 · ", substring = true).assertDoesNotExist()
        }

    @Test
    fun anUndatedEntrySaysTheDateIsNotSet() =
        runComposeUiTest {
            setContent { DiaryVisitRow(DiaryVisitRowState.Undated, null, AppLanguage.KO, {}) }

            onNode(hasText("다녀옴 · 날짜 미정") and hasText("수정")).assertExists()
        }

    @Test
    fun aFailedSaveReplacesTheRowWithTheErrorLine() =
        runComposeUiTest {
            var opened = 0
            setContent {
                DiaryVisitRow(DiaryVisitRowState.NoEntry, DiaryEntryFailure.SAVE, AppLanguage.KO, { opened += 1 })
            }

            onNodeWithText("! 기록을 저장하지 못했어요. 다시 시도해 주세요.").assertExists().performClick()
            onNodeWithText("다녀왔어요").assertDoesNotExist()
            assertEquals(1, opened)
        }

    @Test
    fun aFailedPublishSaysWhoCanSeeTheNoteWasNotSaved() =
        runComposeUiTest {
            setContent {
                DiaryVisitRow(DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), null), DiaryEntryFailure.PUBLISH, AppLanguage.KO, {})
            }

            onNodeWithText("! 공개 설정을 저장하지 못했어요. 다시 시도해 주세요.").assertExists()
        }

    @Test
    fun theRowSpeaksEnglish() =
        runComposeUiTest {
            setContent {
                DiaryVisitRow(
                    DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), VisitFeeling.LOVED),
                    null,
                    AppLanguage.EN,
                    {},
                )
            }

            onNode(hasText("VISITED NOV 9 · LOVED IT") and hasText("EDIT")).assertExists()
        }

    @Test
    fun theExhibitionPageShowsTheRowForAnOpenedShowAndOpensTheSheet() =
        runComposeUiTest {
            val actions = RecordingDiaryEntryActions()
            setContent {
                ExhibitionDetailScreen(
                    exhibition = exhibition(LocalDate(2026, 1, 1)),
                    lang = AppLanguage.KO,
                    isBookmarked = false,
                    onBookmarkToggle = {},
                    onBack = {},
                    diaryEntry = DiaryEntryControl(DiaryEntryUiState(row = DiaryVisitRowState.NoEntry), actions),
                )
            }

            onNodeWithText("다녀왔어요").performClick()
            assertEquals(listOf("open"), actions.calls)
        }

    @Test
    fun theExhibitionPageShowsNoRowForAnUpcomingShow() =
        runComposeUiTest {
            setContent {
                ExhibitionDetailScreen(
                    exhibition = exhibition(LocalDate(2099, 1, 1)),
                    lang = AppLanguage.KO,
                    isBookmarked = false,
                    onBookmarkToggle = {},
                    onBack = {},
                    // Even a stale row state never shows on a show that has not opened.
                    diaryEntry =
                        DiaryEntryControl(DiaryEntryUiState(row = DiaryVisitRowState.NoEntry), RecordingDiaryEntryActions()),
                )
            }

            onNodeWithText("다녀왔어요").assertDoesNotExist()
        }

    private fun exhibition(opening: LocalDate) =
        Exhibition(
            id = "show",
            nameKo = "빛의 방",
            nameEn = "Room of Light",
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "",
            regionEn = "",
            openingDate = opening,
            closingDate = LocalDate(2099, 12, 31),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )
}
