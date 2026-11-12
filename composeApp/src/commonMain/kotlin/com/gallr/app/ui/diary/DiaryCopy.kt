package com.gallr.app.ui.diary

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.diary.DIARY_NOTE_MAX_LENGTH
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import kotlinx.datetime.LocalDate

// Diary wording (contracts/diary-ui.md). English labels and buttons are upper case, explanations sentence case.

private val EN_MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

/** A visit day without the year: `11월 9일` / `NOV 9`. */
internal fun diaryDayLabel(
    date: LocalDate,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "${date.month.ordinal + 1}월 ${date.day}일"
        AppLanguage.EN -> "${EN_MONTHS[date.month.ordinal]} ${date.day}"
    }

/** A feeling as a chip or row label. */
internal fun diaryFeelingLabel(
    feeling: VisitFeeling,
    language: AppLanguage,
): String = feeling.label(language).upperInEnglish(language)

/** A tag as a chip label. */
internal fun diaryTagLabel(
    tag: VisitTag,
    language: AppLanguage,
): String = tag.label(language).upperInEnglish(language)

private fun String.upperInEnglish(language: AppLanguage) = if (language == AppLanguage.EN) uppercase() else this

internal object DiaryRowCopy {
    fun noEntry(language: AppLanguage) = if (language == AppLanguage.KO) "다녀왔어요" else "I'VE BEEN"

    fun dated(
        date: LocalDate,
        feeling: VisitFeeling?,
        language: AppLanguage,
    ): String {
        val visited =
            when (language) {
                AppLanguage.KO -> "${diaryDayLabel(date, language)} 다녀옴"
                AppLanguage.EN -> "VISITED ${diaryDayLabel(date, language)}"
            }
        return if (feeling == null) visited else "$visited · ${diaryFeelingLabel(feeling, language)}"
    }

    fun undated(language: AppLanguage) = if (language == AppLanguage.KO) "다녀옴 · 날짜 미정" else "VISITED · DATE NOT SET"

    fun edit(language: AppLanguage) = if (language == AppLanguage.KO) "수정" else "EDIT"

    fun saveFailed(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "! 기록을 저장하지 못했어요. 다시 시도해 주세요."
        } else {
            "! Couldn't save the entry. Please try again."
        }

    fun deleteFailed(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "! 기록을 삭제하지 못했어요. 다시 시도해 주세요."
        } else {
            "! Couldn't delete the entry. Please try again."
        }
}

internal object DiarySheetCopy {
    fun whenHeading(language: AppLanguage) = if (language == AppLanguage.KO) "언제 다녀오셨어요?" else "WHEN DID YOU GO?"

    fun today(language: AppLanguage) = if (language == AppLanguage.KO) "오늘" else "TODAY"

    fun yesterday(language: AppLanguage) = if (language == AppLanguage.KO) "어제" else "YESTERDAY"

    fun pickDate(language: AppLanguage) = if (language == AppLanguage.KO) "날짜 선택" else "PICK A DATE"

    fun chooseDate(language: AppLanguage) = if (language == AppLanguage.KO) "날짜를 정해 주세요" else "Choose the date"

    fun feelingHeading(language: AppLanguage) = if (language == AppLanguage.KO) "어땠어요?" else "HOW WAS IT?"

    fun tagHeading(language: AppLanguage) = if (language == AppLanguage.KO) "무엇이 남았나요?" else "WHAT STAYED WITH YOU?"

    fun tagLimit(language: AppLanguage) =
        if (language == AppLanguage.KO) "태그는 3개까지 고를 수 있어요" else "You can pick up to three"

    fun noteHeading(language: AppLanguage) = if (language == AppLanguage.KO) "메모" else "NOTE"

    fun notePlaceholder(language: AppLanguage) =
        if (language == AppLanguage.KO) "기억하고 싶은 것을 적어 보세요" else "Write what you want to remember"

    fun noteCounter(length: Int) = "$length/$DIARY_NOTE_MAX_LENGTH"

    fun noteOverLimit(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "! 280자 이내로 줄이면 공개할 수 있어요"
        } else {
            "! Shorten it to 280 characters to publish"
        }

    fun save(language: AppLanguage) = if (language == AppLanguage.KO) "저장" else "SAVE"

    fun deleteEntry(language: AppLanguage) = if (language == AppLanguage.KO) "기록 삭제" else "DELETE ENTRY"

    fun deleteConfirm(
        removesPublicNote: Boolean,
        language: AppLanguage,
    ) = when (language) {
        AppLanguage.KO -> {
            if (removesPublicNote) "이 기록을 삭제할까요? 공개한 감상도 함께 삭제돼요." else "이 기록을 삭제할까요?"
        }

        AppLanguage.EN -> {
            if (removesPublicNote) {
                "Delete this entry? Your published note will be deleted too."
            } else {
                "Delete this entry?"
            }
        }
    }

    fun delete(language: AppLanguage) = if (language == AppLanguage.KO) "삭제" else "DELETE"

    fun cancel(language: AppLanguage) = if (language == AppLanguage.KO) "취소" else "CANCEL"

    fun ok(language: AppLanguage) = if (language == AppLanguage.KO) "확인" else "OK"

    fun discardQuestion(language: AppLanguage) =
        if (language == AppLanguage.KO) "저장하지 않고 닫을까요?" else "Close without saving?"

    fun close(language: AppLanguage) = if (language == AppLanguage.KO) "닫기" else "CLOSE"

    fun keepEditing(language: AppLanguage) = if (language == AppLanguage.KO) "계속 쓰기" else "KEEP EDITING"
}
