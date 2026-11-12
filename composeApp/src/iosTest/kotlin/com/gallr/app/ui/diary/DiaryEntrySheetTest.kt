package com.gallr.app.ui.diary

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.viewmodel.DiaryEntryActions
import com.gallr.app.viewmodel.DiaryEntryEditorState
import com.gallr.app.viewmodel.DiaryVisibility
import com.gallr.app.viewmodel.diaryEntryEditor
import com.gallr.app.viewmodel.withAskAgain
import com.gallr.app.viewmodel.withDate
import com.gallr.app.viewmodel.withFeelingToggled
import com.gallr.app.viewmodel.withNote
import com.gallr.app.viewmodel.withTagToggled
import com.gallr.app.viewmodel.withVisibility
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.ThoughtRejectionReason
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

// Value: protects=the entry sheet offers only the dates the run allows, waits for a date on a closed show, refuses a
// fourth tag aloud, keeps the note counter and the date picker off the accent, and words the delete confirm by whether
// a 감상 goes with it (spec 103 US1, contracts/diary-ui.md); fails_when=DiaryEntrySheetContent drops a chip rule, enables
// 저장 without a date, loses the limit line, tints the counter or the picker, or shows the wrong confirm;
// why_new=no sheet existed; seam=DiaryEntrySheetContent with the ViewModel's own editor transitions; no_claim=the
// ModalBottomSheet's drag and the unsaved-changes confirm

/** Spec 103 US1: the diary entry sheet. */
@OptIn(ExperimentalTestApi::class)
class DiaryEntrySheetTest {
    private val today = LocalDate(2026, 11, 12)

    @Test
    fun aRunningShowOffersTodayYesterdayAndThePickerWithTodayChosen() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))))

            onNodeWithText("오늘").assertIsSelected()
            onNodeWithText("어제").assertIsNotSelected()
            onNodeWithText("날짜 선택").assertIsNotSelected()
            onNodeWithText("저장").assertIsEnabled()
            onNodeWithText("기록 삭제").assertDoesNotExist()
        }

    @Test
    fun aClosedShowOffersNoTodayAndSaveWaitsForADate() =
        runComposeUiTest {
            // Closed yesterday, so 어제 is still inside the run.
            setSheet(editorFor(exhibition(LocalDate(2026, 10, 1), LocalDate(2026, 11, 11))))

            onNodeWithText("오늘").assertDoesNotExist()
            onNodeWithText("저장").assertIsNotEnabled()

            onNodeWithText("어제").performClick()

            onNodeWithText("어제").assertIsSelected()
            onNodeWithText("저장").assertIsEnabled()
        }

    @Test
    fun aShowOpeningTodayOffersOnlyToday() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(today, LocalDate(2026, 12, 31))))

            onNodeWithText("오늘").assertIsSelected()
            onNodeWithText("어제").assertDoesNotExist()
            onNodeWithText("날짜 선택").assertDoesNotExist()
        }

    @Test
    fun aDateChosenInThePickerShowsInItsChip() =
        runComposeUiTest {
            val editor = editorFor(exhibition(LocalDate(2026, 10, 1), LocalDate(2026, 11, 5)))
            setSheet(editor.withDate(LocalDate(2026, 11, 3)))

            onNodeWithText("11월 3일").assertIsSelected()
            onNodeWithText("날짜 선택").assertDoesNotExist()
        }

    @Test
    fun aFourthTagIsRefusedWithTheLimitLine() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))))

            onNodeWithText("작품이 좋았어요").performScrollTo().performClick()
            onNodeWithText("공간이 좋았어요").performScrollTo().performClick()
            onNodeWithText("즐거웠어요").performScrollTo().performClick()
            onNodeWithText("태그는 3개까지 고를 수 있어요").assertDoesNotExist()

            onNodeWithText("마음이 무거웠어요").performScrollTo().performClick()

            onNodeWithText("마음이 무거웠어요").assertIsNotSelected()
            onNodeWithText("태그는 3개까지 고를 수 있어요").assertExists()
        }

    @Test
    fun aFeelingTapSelectsItAndASecondTapClearsIt() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))))

            onNodeWithText("좋았어요").performScrollTo().performClick()
            onNodeWithText("좋았어요").assertIsSelected()
            onNodeWithText("아쉬웠어요").performClick()
            onNodeWithText("좋았어요").assertIsNotSelected()
            onNodeWithText("아쉬웠어요").assertIsSelected().performClick()
            onNodeWithText("아쉬웠어요").assertIsNotSelected()
        }

    @Test
    fun theNoteCounterIsNeverTheAccent() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))))

            onNodeWithText("0/280").performScrollTo()
            assertNoAccent(onNodeWithText("0/280").captureToImage().toPixelMap().let { map -> pixels(map) })

            onNode(hasSetTextAction()).performScrollTo().performTextInput("가".repeat(280))

            onNodeWithText("280/280").performScrollTo()
            assertNoAccent(onNodeWithText("280/280").captureToImage().toPixelMap().let { map -> pixels(map) })
        }

    @Test
    fun theDatePickerSelectsInOnBackgroundAndOnlyWithinTheRun() =
        runComposeUiTest {
            var colors: androidx.compose.material3.DatePickerColors? = null
            var onBackground = Color.Unspecified
            var background = Color.Unspecified
            setContent {
                // The default theme's primary is not monochrome, so this shows the sheet's own colours win.
                MaterialTheme {
                    colors = diaryDatePickerColors()
                    onBackground = MaterialTheme.colorScheme.onBackground
                    background = MaterialTheme.colorScheme.background
                }
            }
            waitForIdle()
            val picker = colors!!
            assertEquals(onBackground, picker.selectedDayContainerColor)
            assertEquals(background, picker.selectedDayContentColor)
            assertEquals(onBackground, picker.todayDateBorderColor)
            assertEquals(onBackground, picker.selectedYearContainerColor)
            assertNotEquals(GallrAccent.activeIndicator, picker.selectedDayContainerColor)

            val run = diarySelectableDates(earliest = LocalDate(2026, 10, 1), latest = LocalDate(2026, 11, 5))
            assertTrue(run.isSelectableDate(utcMillis(LocalDate(2026, 10, 1))))
            assertTrue(run.isSelectableDate(utcMillis(LocalDate(2026, 11, 5))))
            assertFalse(run.isSelectableDate(utcMillis(LocalDate(2026, 9, 30))))
            assertFalse(run.isSelectableDate(utcMillis(LocalDate(2026, 11, 6))))
            assertTrue(run.isSelectableYear(2026))
            assertFalse(run.isSelectableYear(2027))
        }

    @Test
    fun thePickerChipOpensACalendarWithOkAndCancel() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(LocalDate(2026, 10, 1), LocalDate(2026, 11, 5))))

            onNodeWithText("날짜 선택").performClick()

            onNodeWithText("확인").assertExists()
            onNodeWithText("취소").performClick()
            onNodeWithText("확인").assertDoesNotExist()
        }

    @Test
    fun deletingAnEntryWithoutAPublicNoteAsksPlainly() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            val actions = setSheet(editorFor(show, saved = visit(show)))

            onNodeWithText("기록 삭제").performClick()

            onNodeWithText("이 기록을 삭제할까요?").assertExists()
            onNodeWithText("이 기록을 삭제할까요? 공개한 감상도 함께 삭제돼요.").assertDoesNotExist()
            onNodeWithText("삭제").performClick()
            assertEquals(1, actions.deleted)
        }

    @Test
    fun deletingAnEntryWithAPublicPublicNoteSaysItGoesToo() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(editorFor(show, saved = visit(show), publicNote = note(show, PublicNoteModeration.APPROVED)))

            onNodeWithText("기록 삭제").performClick()

            onNodeWithText("이 기록을 삭제할까요? 공개한 감상도 함께 삭제돼요.").assertExists()
            onNodeWithText("취소").performClick()
            onNodeWithText("이 기록을 삭제할까요? 공개한 감상도 함께 삭제돼요.").assertDoesNotExist()
        }

    @Test
    fun anEntryFromAPublicNoteAsksForTheDate() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(editorFor(show, publicNote = note(show, PublicNoteModeration.APPROVED)))

            onNodeWithText("날짜를 정해 주세요").assertExists()
            onNodeWithText("작은 방의 빛").assertExists()
            // Asked, not required: changing only who can see it needs no invented date (e2e defect 8).
            onNodeWithText("저장").assertIsEnabled()
        }

    @Test
    fun everyWordIsEnglishWhenTheAppIsInEnglish() =
        runComposeUiTest {
            setSheet(
                editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))),
                language = AppLanguage.EN,
            )

            onNodeWithText("WHEN DID YOU GO?").assertExists()
            onNodeWithText("TODAY").assertIsSelected()
            onNodeWithText("YESTERDAY").assertExists()
            onNodeWithText("PICK A DATE").assertExists()
            onNodeWithText("HOW WAS IT?").assertExists()
            onNodeWithText("LOVED IT").assertExists()
            onNodeWithText("WHAT STAYED WITH YOU?").performScrollTo()
            onNodeWithText("THE WORK").assertExists()
            onNodeWithText("NOTE").performScrollTo()
            onNodeWithText("Write what you want to remember").assertExists()
            onNodeWithText("SAVE").assertIsEnabled()
        }

    // ── Who can see it (spec 103 US3, T100) ──

    @Test
    fun aGuestIsOfferedSignInInsteadOfTheChoice() =
        runComposeUiTest {
            var signIns = 0
            setSheet(
                editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))),
                onSignIn = { signIns += 1 },
            )

            onNodeWithText("누가 볼 수 있나요?").performScrollTo()
            onNodeWithText("나만 보기").assertDoesNotExist()
            onNodeWithText("로그인하면 전시 페이지에 공개할 수 있어요").performScrollTo().performClick()
            assertEquals(1, signIns)
        }

    @Test
    fun theLineUnderTheChoiceFollowsIt() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(editorFor(show, saved = visit(show).copy(note = "빛"), signedIn = true))

            onNodeWithText("나만 보기").performScrollTo().assertIsSelected()
            onNodeWithText("메모는 나만 볼 수 있어요.").assertExists()

            onNodeWithText("전시 페이지에 공개").performClick()

            onNodeWithText("전시 페이지에 공개").assertIsSelected()
            onNodeWithText("나만 보기").assertIsNotSelected()
            onNodeWithText(PUBLIC_LINE).performScrollTo().assertExists()
            onNodeWithText("메모는 나만 볼 수 있어요.").assertDoesNotExist()
        }

    @Test
    fun anEmptyNoteKeepsOnlyMeAndSaysWhatTheExhibitionPageNeeds() =
        runComposeUiTest {
            setSheet(editorFor(exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30)), signedIn = true))

            onNodeWithText("전시 페이지에 공개").performScrollTo().assertIsNotEnabled()
            onNodeWithText("나만 보기").assertIsSelected()
            onNodeWithText("메모를 쓰면 전시 페이지에 공개할 수 있어요").assertExists()
        }

    @Test
    fun aNoteNotPublishedShowsItsStatusTheReasonAndAskAgain() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            val rejected =
                note(show, PublicNoteModeration.REJECTED).copy(rejectionReason = ThoughtRejectionReason.PROMOTIONAL)
            val actions =
                setSheet(
                    editorFor(show, saved = visit(show).copy(note = "작은 방의 빛"), publicNote = rejected, signedIn = true),
                )

            onNodeWithText("공개 안 됨").performScrollTo().assertExists()
            onNodeWithText("사유: 광고·홍보").assertExists()
            onNodeWithText("수정해서 다시 요청").performScrollTo().performClick()
            assertTrue(actions.editor.askAgain)
            onNodeWithText("수정해서 다시 요청").assertDoesNotExist()
        }

    @Test
    fun anEditedPublishedNoteOffersToAskAgainWithTheEdit() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(
                editorFor(
                    show,
                    saved = visit(show).copy(note = "작은 방의 빛, 다시"),
                    publicNote = note(show, PublicNoteModeration.APPROVED),
                    signedIn = true,
                ),
            )

            onNodeWithText("공개됨").performScrollTo().assertExists()
            onNodeWithText("수정한 내용으로 다시 요청").performScrollTo().assertExists()
        }

    /** End-to-end defect 5: tapping 다시 요청 says what the save will do, and 나만 보기 takes it back. */
    @Test
    fun askingAgainLeavesAQuietLineThatOnlyMeTakesBack() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(
                editorFor(
                    show,
                    saved = visit(show).copy(note = "작은 방의 빛, 다시"),
                    publicNote = note(show, PublicNoteModeration.APPROVED),
                    signedIn = true,
                ),
            )

            onNodeWithText("저장하면 다시 검토를 요청해요.").assertDoesNotExist()
            onNodeWithText("수정한 내용으로 다시 요청").performScrollTo().performClick()
            onNodeWithText("수정한 내용으로 다시 요청").assertDoesNotExist()
            onNodeWithText("저장하면 다시 검토를 요청해요.").performScrollTo().assertExists()

            onNodeWithText("나만 보기").performScrollTo().performClick()
            onNodeWithText("저장하면 다시 검토를 요청해요.").assertDoesNotExist()
        }

    @Test
    fun askingAgainSpeaksEnglish() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(
                editorFor(
                    show,
                    saved = visit(show).copy(note = "작은 방의 빛, 다시"),
                    publicNote = note(show, PublicNoteModeration.APPROVED),
                    signedIn = true,
                ),
                language = AppLanguage.EN,
            )

            onNodeWithText("ASK AGAIN WITH THE EDIT").performScrollTo().performClick()
            onNodeWithText("Saving will ask for review again.").performScrollTo().assertExists()
        }

    @Test
    fun anUnseenApprovalShowsTheOneTimeLineAtTheTop() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            val approved = note(show, PublicNoteModeration.APPROVED).copy(approvalSeen = false)
            setSheet(
                editorFor(show, saved = visit(show).copy(note = "작은 방의 빛"), publicNote = approved, signedIn = true),
            )

            onNodeWithText("감상이 공개됐어요").assertExists()
        }

    @Test
    fun theChosenVisibilityChipIsNeverTheAccent() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(editorFor(show, saved = visit(show).copy(note = "빛"), signedIn = true))

            onNodeWithText("전시 페이지에 공개").performScrollTo().performClick()

            assertNoAccent(pixels(onNodeWithText("전시 페이지에 공개").captureToImage().toPixelMap()))
            assertNoAccent(pixels(onNodeWithText("나만 보기").captureToImage().toPixelMap()))
        }

    @Test
    fun savingAwayTheNoteOfAPublicNoteAsksFirst() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            val actions =
                setSheet(
                    editorFor(
                        show,
                        saved = visit(show).copy(note = "작은 방의 빛"),
                        publicNote = note(show, PublicNoteModeration.APPROVED),
                        signedIn = true,
                    ),
                )
            actions.updateNote("")

            onNodeWithText("저장").performClick()

            onNodeWithText(NO_NOTE_WARNING).assertExists()
            assertEquals(0, actions.saved)
            onAllNodesWithText("저장")[1].performClick()
            assertEquals(1, actions.saved)
        }

    @Test
    fun whoCanSeeItSpeaksEnglish() =
        runComposeUiTest {
            val show = exhibition(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
            setSheet(
                editorFor(show, saved = visit(show).copy(note = "light"), signedIn = true),
                language = AppLanguage.EN,
            )

            onNodeWithText("WHO CAN SEE IT?").performScrollTo()
            onNodeWithText("ONLY ME").assertIsSelected()
            onNodeWithText("EXHIBITION PAGE").assertIsNotSelected()
            onNodeWithText("Only you can see this note.").assertExists()
        }

    // ── The calendar speaks the app's language, not the device's (conductor decision b) ──

    @Test
    fun theCalendarFollowsTheAppLanguage() =
        runComposeUiTest {
            var language by mutableStateOf(AppLanguage.EN)
            val actions = SheetActions(editorFor(exhibition(LocalDate(2026, 10, 1), LocalDate(2026, 11, 5))))
            setContent {
                DiaryEntrySheetContent(
                    title = "빛의 방",
                    venue = "갤러리",
                    editor = actions.editor,
                    language = language,
                    actions = actions,
                    onSignIn = {},
                )
            }

            onNodeWithText("PICK A DATE").performClick()
            assertTrue(onAllNodesWithText("November 2026", substring = true).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText("CANCEL").performClick()

            language = AppLanguage.KO
            waitForIdle()
            onNodeWithText("날짜 선택").performClick()
            // The month header and every day speak Korean now, whatever the device's language.
            assertTrue(onAllNodesWithText("2026년 11월", substring = true).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText("November", substring = true).assertDoesNotExist()
        }

    @Test
    fun theCalendarHeadlineNamesTheChosenDateInTheAppLanguage() {
        assertEquals("2026년 11월 3일", diaryCalendarHeadline(LocalDate(2026, 11, 3), AppLanguage.KO))
        assertEquals("NOV 3, 2026", diaryCalendarHeadline(LocalDate(2026, 11, 3), AppLanguage.EN))
    }

    private companion object {
        const val PUBLIC_LINE = "공개하면 검토 후 이 전시 페이지에 내 이름과 함께 보여요. 기분과 태그는 공개되지 않아요."
        const val NO_NOTE_WARNING = "메모를 비우면 공개한 감상도 함께 삭제돼요. 저장할까요?"
    }

    /** The ViewModel's own editor transitions over a state the test can read; saves and deletes are counted. */
    private class SheetActions(
        initial: DiaryEntryEditorState,
    ) : DiaryEntryActions {
        var editor by mutableStateOf(initial)
        var saved = 0
        var deleted = 0

        override fun startEditing(focusNote: Boolean) = Unit

        override fun selectDate(date: LocalDate) {
            editor = editor.withDate(date)
        }

        override fun toggleFeeling(feeling: VisitFeeling) {
            editor = editor.withFeelingToggled(feeling)
        }

        override fun toggleTag(tag: VisitTag) {
            editor = editor.withTagToggled(tag)
        }

        override fun updateNote(text: String) {
            editor = editor.withNote(text)
        }

        override fun selectVisibility(visibility: DiaryVisibility) {
            editor = editor.withVisibility(visibility)
        }

        override fun askAgain() {
            editor = editor.withAskAgain()
        }

        override fun save() {
            saved += 1
        }

        override fun delete() {
            deleted += 1
        }

        override fun discardEditing() = Unit
    }

    private fun ComposeUiTest.setSheet(
        initial: DiaryEntryEditorState,
        language: AppLanguage = AppLanguage.KO,
        onSignIn: () -> Unit = {},
    ): SheetActions {
        val actions = SheetActions(initial)
        setContent {
            DiaryEntrySheetContent(
                title = "빛의 방",
                venue = "갤러리",
                editor = actions.editor,
                language = language,
                actions = actions,
                onSignIn = onSignIn,
            )
        }
        return actions
    }

    private fun editorFor(
        exhibition: Exhibition,
        saved: ExhibitionVisit? = null,
        publicNote: OwnPublicNote? = null,
        signedIn: Boolean = false,
        focusNote: Boolean = false,
    ): DiaryEntryEditorState =
        diaryEntryEditor(exhibition, saved, publicNote, today, focusNote = focusNote, isSignedIn = signedIn)!!

    private fun pixels(map: androidx.compose.ui.graphics.PixelMap): List<Color> =
        (0 until map.width).flatMap { x -> (0 until map.height).map { y -> map[x, y] } }

    private fun assertNoAccent(pixels: List<Color>) {
        val accent = GallrAccent.activeIndicator
        val tinted =
            pixels.filter {
                abs(it.red - accent.red) < 0.12f &&
                    abs(it.green - accent.green) < 0.12f &&
                    abs(it.blue - accent.blue) < 0.12f
            }
        assertTrue(pixels.isNotEmpty())
        assertTrue(tinted.isEmpty(), "${tinted.size} pixels in the accent colour")
    }

    private fun utcMillis(date: LocalDate) = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun visit(exhibition: Exhibition) =
        ExhibitionVisit(
            clientRecordId = "${exhibition.id}:1",
            exhibitionId = exhibition.id,
            snapshot = ExhibitionVisitSnapshot.from(exhibition),
            createdAt = Instant.parse("2026-11-01T00:00:00Z"),
            visitDate = LocalDate(2026, 11, 9),
        )

    private fun note(
        exhibition: Exhibition,
        moderation: PublicNoteModeration,
    ) = OwnPublicNote(
        exhibitionId = exhibition.id,
        thoughtId = "thought-1",
        content = "작은 방의 빛",
        moderation = moderation,
        rejectionReason = null,
        requestedAt = Instant.parse("2026-10-01T00:00:00Z"),
        approvedAt = null,
        approvalSeen = true,
    )

    private fun exhibition(
        opening: LocalDate,
        closing: LocalDate,
    ) = Exhibition(
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
        closingDate = closing,
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
