package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.TastePickMode
import com.gallr.shared.data.model.AppLanguage

/** The taste sheet's words (spec 101 contracts/taste-ui.md). */
internal data class TastePickSheetCopy(
    val title: String,
    val subline: String,
    val coversLabel: String,
    val confirm: String,
    /** Null when the button is enabled or the sheet is being edited. */
    val remainingHint: String?,
    /** Null when editing: only a first-run sheet can be skipped. */
    val skip: String?,
    /** Label of the row of terms the visitor's 덜 보기 hides count against. */
    val seeingLess: String,
    /** The action on each of those chips: the term stops counting, the hides stay. */
    val release: String,
)

internal fun tastePickSheetCopy(
    language: AppLanguage,
    mode: TastePickMode,
    remaining: Int,
    selectionCount: Int,
    /** False when the catalogue offers no term chips: the covers are then the only choice, not an alternative. */
    hasTerms: Boolean = true,
): TastePickSheetCopy {
    val ko = language == AppLanguage.KO
    val firstRun = mode == TastePickMode.FIRST_RUN
    return TastePickSheetCopy(
        title = if (ko) "어떤 전시를 좋아하세요?" else "What exhibitions do you like?",
        subline =
            if (ko) {
                "3개 이상 고르면 내 취향이 시작돼요 · 이 기기에만 저장"
            } else {
                "Pick three or more to start · Saved on this device only"
            },
        coversLabel =
            when {
                hasTerms && ko -> "또는 끌리는 전시를 고르세요"
                hasTerms -> "Or pick exhibitions you like"
                ko -> "끌리는 전시를 고르세요"
                else -> "Pick exhibitions you like"
            },
        confirm =
            when {
                !firstRun && ko -> "저장"
                !firstRun -> "SAVE"
                ko -> "시작하기 ($selectionCount)"
                else -> "START ($selectionCount)"
            },
        remainingHint =
            when {
                !firstRun || remaining == 0 -> null
                ko -> "${remaining}개 더 골라 주세요"
                else -> "Pick $remaining more"
            },
        skip =
            when {
                !firstRun -> null
                ko -> "건너뛰기"
                else -> "SKIP"
            },
        seeingLess = if (ko) "덜 보기" else "SEEING LESS",
        release = if (ko) "되돌리기" else "UNDO",
    )
}
