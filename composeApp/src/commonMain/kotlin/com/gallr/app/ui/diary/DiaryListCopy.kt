package com.gallr.app.ui.diary

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.diary.PublicNoteStatus

// MY 일기 wording (contracts/diary-ui.md "MY → 일기" and "Status words"). English labels are upper case, explanations
// sentence case.

private val EN_MONTH_NAMES =
    listOf(
        "JANUARY",
        "FEBRUARY",
        "MARCH",
        "APRIL",
        "MAY",
        "JUNE",
        "JULY",
        "AUGUST",
        "SEPTEMBER",
        "OCTOBER",
        "NOVEMBER",
        "DECEMBER",
    )

/** A note's status word, as rows, the entry sheet and the 공개한 감상 filter show it. */
internal fun diaryNoteStatusLabel(
    status: PublicNoteStatus,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (status) {
                PublicNoteStatus.PRIVATE -> "비공개"
                PublicNoteStatus.SENDING -> "보내는 중"
                PublicNoteStatus.RETRY -> "보내지 못했어요 · 다시 시도"
                PublicNoteStatus.IN_REVIEW -> "검토 중"
                PublicNoteStatus.PUBLISHED -> "공개됨"
                PublicNoteStatus.NOT_PUBLISHED -> "공개 안 됨"
            }
        }

        AppLanguage.EN -> {
            when (status) {
                PublicNoteStatus.PRIVATE -> "PRIVATE"
                PublicNoteStatus.SENDING -> "SENDING"
                PublicNoteStatus.RETRY -> "NOT SENT · RETRY"
                PublicNoteStatus.IN_REVIEW -> "IN REVIEW"
                PublicNoteStatus.PUBLISHED -> "PUBLISHED"
                PublicNoteStatus.NOT_PUBLISHED -> "NOT PUBLISHED"
            }
        }
    }

internal object DiaryListCopy {
    /** A month group's heading: `2026년 11월` / `NOVEMBER 2026`. */
    fun month(
        year: Int,
        month: Int,
        language: AppLanguage,
    ) = when (language) {
        AppLanguage.KO -> "${year}년 ${month}월"
        AppLanguage.EN -> "${EN_MONTH_NAMES[month - 1]} $year"
    }

    fun undated(language: AppLanguage) = if (language == AppLanguage.KO) "날짜 미정" else "DATE NOT SET"

    fun all(language: AppLanguage) = if (language == AppLanguage.KO) "전체" else "ALL"

    fun publishedNotes(language: AppLanguage) = if (language == AppLanguage.KO) "공개한 감상" else "PUBLISHED NOTES"

    fun addPastVisits(language: AppLanguage) = if (language == AppLanguage.KO) "+ 지난 전시 추가" else "+ ADD PAST VISITS"

    fun unavailable(language: AppLanguage) = if (language == AppLanguage.KO) "전시 정보 없음" else "EXHIBITION UNAVAILABLE"

    fun empty(language: AppLanguage) =
        if (language == AppLanguage.KO) "다녀온 전시를 기록해 보세요" else "Record the shows you've been to"

    fun emptyAction(language: AppLanguage) = if (language == AppLanguage.KO) "지난 전시 추가" else "ADD PAST VISITS"

    fun emptyUnderFilter(language: AppLanguage) =
        if (language == AppLanguage.KO) "공개한 감상이 아직 없어요" else "No published notes yet"

    fun loadFailed(language: AppLanguage) =
        if (language == AppLanguage.KO) "일기를 불러오지 못했어요." else "Couldn't load your diary."

    fun retry(language: AppLanguage) = if (language == AppLanguage.KO) "다시 시도" else "RETRY"

    fun endedSaved(
        count: Int,
        language: AppLanguage,
    ) = when (language) {
        AppLanguage.KO -> "저장한 전시 중 끝난 전시 ${count}개"
        AppLanguage.EN -> "$count SAVED ${if (count == 1) "SHOW HAS" else "SHOWS HAVE"} ENDED"
    }

    fun endedSavedAdd(language: AppLanguage) = if (language == AppLanguage.KO) "추가하기" else "ADD"

    fun close(language: AppLanguage) = if (language == AppLanguage.KO) "닫기" else "CLOSE"

    /** The bulk add's title when it lists only the saved shows that have ended. */
    fun endedSavedTitle(language: AppLanguage) = if (language == AppLanguage.KO) "끝난 저장 전시" else "ENDED SAVED SHOWS"

    fun addEntries(
        count: Int,
        language: AppLanguage,
    ) = when (language) {
        AppLanguage.KO -> "${count}개 기록 추가"
        AppLanguage.EN -> "ADD $count ${if (count == 1) "ENTRY" else "ENTRIES"}"
    }
}
