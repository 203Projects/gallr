package com.gallr.app.ui.diary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.viewmodel.DiaryUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.diary.Diary
import com.gallr.shared.diary.DiaryEntry
import com.gallr.shared.diary.DiaryFilter
import com.gallr.shared.diary.DiaryMonth
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.diary.VisitFeeling
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

// Value: protects=MY 일기 reads as the contract lays it out: month headings newest first with 날짜 미정 last, rows as
// one button with date, feeling, note line and status word, the filter chips, the ended-saved strip by count, the
// bulk add kept, and the empty, empty-under-filter and failure copy (spec 103 US2, FR-014); fails_when=DiaryList drops
// or reorders a group, splits a row, loses a callback or shows the wrong empty copy; why_new=the screen is new;
// seam=DiaryList with a hoisted DiaryUiState; no_claim=what the entry sheet does once a row opens it

/** Spec 103 US2: the diary list in MY. */
@OptIn(ExperimentalTestApi::class)
class DiaryListTest {
    @Test
    fun monthsReadNewestFirstAndTheUndatedGroupClosesTheList() =
        runComposeUiTest {
            setContent {
                DiaryList(
                    state =
                        state(
                            diary(
                                months =
                                    listOf(
                                        DiaryMonth(2026, 11, listOf(entry("a", LocalDate(2026, 11, 9)))),
                                        DiaryMonth(2026, 10, listOf(entry("b", LocalDate(2026, 10, 3)))),
                                    ),
                                undated = listOf(entry("c", visitDate = null)),
                            ),
                        ),
                    actions = NoDiaryListActions,
                )
            }

            val headings =
                onAllNodes(isHeading()).fetchSemanticsNodes().map { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                }
            assertEquals(listOf("2026년 11월", "2026년 10월", "날짜 미정"), headings)
        }

    @Test
    fun englishMonthsAreSpelledOut() =
        runComposeUiTest {
            setContent {
                DiaryList(
                    state =
                        state(
                            diary(months = listOf(DiaryMonth(2026, 11, listOf(entry("a", LocalDate(2026, 11, 9)))))),
                            language = AppLanguage.EN,
                        ),
                    actions = NoDiaryListActions,
                )
            }

            onNodeWithText("NOVEMBER 2026").assertExists()
            onNode(hasText("NOV 9 · LOVED IT", substring = true) and hasClickAction()).assertExists()
        }

    @Test
    fun aRowIsOneButtonWithItsDateFeelingNoteLineAndStatusWord() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            val published =
                entry("a", LocalDate(2026, 11, 9), note = "빛이 좋았다\n둘째 줄")
                    .copy(noteStatus = PublicNoteStatus.PUBLISHED)
            setContent {
                DiaryList(
                    state = state(diary(months = listOf(DiaryMonth(2026, 11, listOf(published))))),
                    actions = NoDiaryListActions.copy(onOpenEntry = { opened += it.exhibitionId }),
                )
            }

            onNode(
                hasText("전시 a") and hasText("갤러리 a") and hasText("11월 9일 · 좋았어요") and hasText("빛이 좋았다") and
                    hasText("공개됨") and hasClickAction(),
            ).performClick()
            onNodeWithText("둘째 줄", substring = true).assertDoesNotExist()
            assertEquals(listOf("a"), opened)
        }

    @Test
    fun aPrivateNoteShowsNoStatusWordAndAShowWithNoRecordReadsUnavailable() =
        runComposeUiTest {
            val private = entry("a", LocalDate(2026, 11, 9), note = "메모").copy(noteStatus = PublicNoteStatus.PRIVATE)
            val unknown =
                DiaryEntry("gone", null, null, null, PublicNoteStatus.IN_REVIEW, showApprovalLine = false)
            setContent {
                DiaryList(
                    state =
                        state(
                            diary(months = listOf(DiaryMonth(2026, 11, listOf(private))), undated = listOf(unknown)),
                        ),
                    actions = NoDiaryListActions,
                )
            }

            onNodeWithText("비공개").assertDoesNotExist()
            onNode(hasText("전시 정보 없음") and hasText("검토 중")).assertExists()
        }

    @Test
    fun theFilterChipsSwitchAndOneIsAlwaysSelected() =
        runComposeUiTest {
            var filter by mutableStateOf(DiaryFilter.ALL)
            setContent {
                DiaryList(
                    state =
                        state(
                            diary(months = listOf(DiaryMonth(2026, 11, listOf(entry("a", LocalDate(2026, 11, 9)))))),
                            filter = filter,
                        ),
                    actions = NoDiaryListActions.copy(onSelectFilter = { filter = it }),
                )
            }

            onNodeWithText("전체").assertIsSelected()
            onNodeWithText("공개한 감상").performClick()
            assertEquals(DiaryFilter.PUBLISHED_NOTES, filter)
            onNodeWithText("공개한 감상").assertIsSelected()
            onNodeWithText("전체").assertIsNotSelected()
        }

    @Test
    fun theStripShowsByCountLeadsToAddingAndCloses() =
        runComposeUiTest {
            var shown by mutableStateOf(true)
            var added = 0
            var closed = 0
            setContent {
                DiaryList(
                    state =
                        state(
                            diary(
                                months = listOf(DiaryMonth(2026, 11, listOf(entry("a", LocalDate(2026, 11, 9))))),
                                endedSavedCount = 2,
                                showStrip = shown,
                            ),
                        ),
                    actions =
                        NoDiaryListActions.copy(
                            onAddEndedSaved = { added += 1 },
                            onDismissEndedSaved = {
                                closed += 1
                                shown = false
                            },
                        ),
                )
            }

            onNodeWithText("저장한 전시 중 끝난 전시 2개").assertExists()
            onNodeWithText("추가하기").performClick()
            assertEquals(1, added)
            onNodeWithContentDescription("닫기").performClick()
            assertEquals(1, closed)
            onNodeWithText("저장한 전시 중 끝난 전시", substring = true).assertDoesNotExist()
        }

    @Test
    fun theBulkAddStaysAboveTheList() =
        runComposeUiTest {
            var added = 0
            setContent {
                DiaryList(
                    state =
                        state(
                            diary(months = listOf(DiaryMonth(2026, 11, listOf(entry("a", LocalDate(2026, 11, 9)))))),
                        ),
                    actions = NoDiaryListActions.copy(onAddPastVisits = { added += 1 }),
                )
            }

            onNodeWithText("+ 지난 전시 추가").performClick()
            assertEquals(1, added)
        }

    @Test
    fun anEmptyDiaryInvitesTheBulkAdd() =
        runComposeUiTest {
            var added = 0
            setContent {
                DiaryList(
                    state = state(diary(isEmpty = true)),
                    actions = NoDiaryListActions.copy(onAddPastVisits = { added += 1 }),
                )
            }

            onNodeWithText("다녀온 전시를 기록해 보세요").assertExists()
            onNodeWithText("지난 전시 추가").performClick()
            assertEquals(1, added)
        }

    @Test
    fun anEmptyFilterSaysSoWithoutACall() =
        runComposeUiTest {
            setContent {
                DiaryList(
                    state = state(diary(), filter = DiaryFilter.PUBLISHED_NOTES),
                    actions = NoDiaryListActions,
                )
            }

            onNodeWithText("공개한 감상이 아직 없어요").assertExists()
            onNodeWithText("다녀온 전시를 기록해 보세요").assertDoesNotExist()
            // The filter stays, so the visitor can go back to every entry.
            onNodeWithText("전체").assertExists()
        }

    @Test
    fun aFailedReadOffersRetry() =
        runComposeUiTest {
            var retried = 0
            setContent {
                DiaryList(
                    state = DiaryUiState(loadFailed = true),
                    actions = NoDiaryListActions.copy(onRetry = { retried += 1 }),
                )
            }

            onNodeWithText("일기를 불러오지 못했어요.", substring = true).assertExists()
            onNodeWithText("다시 시도").performClick()
            assertEquals(1, retried)
        }

    private fun state(
        diary: Diary,
        filter: DiaryFilter = DiaryFilter.ALL,
        language: AppLanguage = AppLanguage.KO,
    ) = DiaryUiState(diary = diary, filter = filter, language = language, entryCount = 1)

    private fun diary(
        months: List<DiaryMonth> = emptyList(),
        undated: List<DiaryEntry> = emptyList(),
        endedSavedCount: Int = 0,
        showStrip: Boolean = false,
        isEmpty: Boolean = false,
    ) = Diary(months, undated, endedSavedCount, showStrip, isEmpty)

    private fun entry(
        id: String,
        visitDate: LocalDate?,
        note: String? = null,
    ): DiaryEntry {
        val snapshot =
            ExhibitionVisitSnapshot(
                nameKo = "전시 $id",
                nameEn = "Show $id",
                venueNameKo = "갤러리 $id",
                venueNameEn = "Gallery $id",
                openingDate = LocalDate(2026, 9, 1),
                closingDate = LocalDate(2026, 12, 31),
                coverImageUrl = null,
            )
        val visit =
            ExhibitionVisit(
                clientRecordId = "record-$id",
                exhibitionId = id,
                snapshot = snapshot,
                createdAt = Instant.parse("2026-11-10T00:00:00Z"),
                visitDate = visitDate,
                feelingWire = VisitFeeling.LOVED.wire,
                note = note,
            )
        return DiaryEntry(
            exhibitionId = id,
            snapshot = snapshot,
            visit = visit,
            publicNote = null,
            noteStatus = null,
            showApprovalLine = false,
        )
    }
}

private val NoDiaryListActions = DiaryListActions()
