package com.gallr.app.ui.diary

import com.gallr.app.viewmodel.DiaryEntryFailure
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.diary.DIARY_NOTE_MAX_LENGTH
import com.gallr.shared.diary.ThoughtRejectionReason
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

    fun publishFailed(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "! 공개 설정을 저장하지 못했어요. 다시 시도해 주세요."
        } else {
            "! Couldn't save who can see the note. Please try again."
        }

    /** 감상이 공개됐어요, shown once after an approval, in the sheet and above MY 일기. */
    fun approvalLine(language: AppLanguage) = if (language == AppLanguage.KO) "감상이 공개됐어요" else "Your note is now public"
}

/** The words for a save, publish or delete that failed after the sheet closed, wherever the sheet was opened. */
internal fun diaryEntryFailureText(
    failure: DiaryEntryFailure,
    language: AppLanguage,
): String =
    when (failure) {
        DiaryEntryFailure.SAVE -> DiaryRowCopy.saveFailed(language)
        DiaryEntryFailure.DELETE -> DiaryRowCopy.deleteFailed(language)
        DiaryEntryFailure.PUBLISH -> DiaryRowCopy.publishFailed(language)
    }

/** The chosen date in the calendar's header, in the app's language: `2026년 11월 3일` / `NOV 3, 2026`. */
internal fun diaryCalendarHeadline(
    date: LocalDate,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "${date.year}년 ${diaryDayLabel(date, language)}"
        AppLanguage.EN -> "${diaryDayLabel(date, language)}, ${date.year}"
    }

/** The reason staff gave for not publishing a 감상 (contracts/diary-ui.md "Status words"). */
internal fun diaryRejectionReasonLabel(
    reason: ThoughtRejectionReason,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (reason) {
                ThoughtRejectionReason.NOT_ABOUT_EXHIBITION -> "전시와 관련 없는 내용"
                ThoughtRejectionReason.PROMOTIONAL -> "광고·홍보"
                ThoughtRejectionReason.INAPPROPRIATE_LANGUAGE -> "부적절한 표현"
                ThoughtRejectionReason.PERSONAL_INFORMATION -> "개인정보 포함"
                ThoughtRejectionReason.OTHER -> "기타"
            }
        }

        AppLanguage.EN -> {
            when (reason) {
                ThoughtRejectionReason.NOT_ABOUT_EXHIBITION -> "Not about the exhibition"
                ThoughtRejectionReason.PROMOTIONAL -> "Promotional"
                ThoughtRejectionReason.INAPPROPRIATE_LANGUAGE -> "Inappropriate language"
                ThoughtRejectionReason.PERSONAL_INFORMATION -> "Contains personal information"
                ThoughtRejectionReason.OTHER -> "Other"
            }
        }
    }

/** `사유: <reason>` / `Reason: <reason>` under 공개 안 됨. */
internal fun diaryRejectionLine(
    reason: ThoughtRejectionReason,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "사유: ${diaryRejectionReasonLabel(reason, language)}"
        AppLanguage.EN -> "Reason: ${diaryRejectionReasonLabel(reason, language)}"
    }

/** 누가 볼 수 있나요? (owner decision 10). */
internal object DiaryPublishCopy {
    fun heading(language: AppLanguage) = if (language == AppLanguage.KO) "누가 볼 수 있나요?" else "WHO CAN SEE IT?"

    fun onlyMe(language: AppLanguage) = if (language == AppLanguage.KO) "나만 보기" else "ONLY ME"

    fun exhibitionPage(language: AppLanguage) = if (language == AppLanguage.KO) "전시 페이지에 공개" else "EXHIBITION PAGE"

    fun privateLine(language: AppLanguage) =
        if (language == AppLanguage.KO) "메모는 나만 볼 수 있어요." else "Only you can see this note."

    fun publicLine(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "공개하면 검토 후 이 전시 페이지에 내 이름과 함께 보여요. 기분과 태그는 공개되지 않아요."
        } else {
            "If you publish, this note appears on the exhibition page with your name after review. " +
                "Your feeling and tags stay private."
        }

    fun emptyNoteLine(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "메모를 쓰면 전시 페이지에 공개할 수 있어요"
        } else {
            "Write a note to show it on the exhibition page"
        }

    fun signIn(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "로그인하면 전시 페이지에 공개할 수 있어요"
        } else {
            "Sign in to show your note on the exhibition page"
        }

    fun askAgainWithEdit(language: AppLanguage) =
        if (language == AppLanguage.KO) "수정한 내용으로 다시 요청" else "ASK AGAIN WITH THE EDIT"

    fun editAndAskAgain(language: AppLanguage) = if (language == AppLanguage.KO) "수정해서 다시 요청" else "EDIT AND ASK AGAIN"

    /** Before a save that leaves no note: the server withdraws the 감상 of an entry without one. */
    fun noNoteWarning(language: AppLanguage) =
        if (language == AppLanguage.KO) {
            "메모를 비우면 공개한 감상도 함께 삭제돼요. 저장할까요?"
        } else {
            "Without a note, your published note will be deleted too. Save anyway?"
        }

    /** 감상 남기기 under the 감상 list (no emoji). */
    fun writeNote(language: AppLanguage) = if (language == AppLanguage.KO) "감상 남기기" else "WRITE A NOTE"
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
