package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.TastePickMode
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 101 contracts/taste-ui.md: the taste sheet's words, in both languages. */
class TastePickSheetCopyTest {
    @Test
    fun `the sheet introduces itself and promises the picks stay on the device`() {
        val ko = tastePickSheetCopy(AppLanguage.KO, TastePickMode.FIRST_RUN, remaining = 3, selectionCount = 0)
        val en = tastePickSheetCopy(AppLanguage.EN, TastePickMode.FIRST_RUN, remaining = 3, selectionCount = 0)

        assertEquals("어떤 전시를 좋아하세요?", ko.title)
        assertEquals("3개 이상 고르면 내 취향이 시작돼요 · 이 기기에만 저장", ko.subline)
        assertEquals("또는 끌리는 전시를 고르세요", ko.coversLabel)
        assertEquals("What exhibitions do you like?", en.title)
        assertEquals("Pick three or more to start · Saved on this device only", en.subline)
        assertEquals("Or pick exhibitions you like", en.coversLabel)
    }

    @Test
    fun `without term chips the covers are not offered as an alternative`() {
        val ko =
            tastePickSheetCopy(
                AppLanguage.KO,
                TastePickMode.FIRST_RUN,
                remaining = 3,
                selectionCount = 0,
                hasTerms = false,
            )
        val en =
            tastePickSheetCopy(
                AppLanguage.EN,
                TastePickMode.FIRST_RUN,
                remaining = 3,
                selectionCount = 0,
                hasTerms = false,
            )

        assertEquals("끌리는 전시를 고르세요", ko.coversLabel)
        assertEquals("Pick exhibitions you like", en.coversLabel)
    }

    @Test
    fun `the first run button counts picks and says how many more are needed`() {
        val short = tastePickSheetCopy(AppLanguage.KO, TastePickMode.FIRST_RUN, remaining = 2, selectionCount = 1)
        val ready = tastePickSheetCopy(AppLanguage.EN, TastePickMode.FIRST_RUN, remaining = 0, selectionCount = 4)

        assertEquals("시작하기 (1)", short.confirm)
        assertEquals("2개 더 골라 주세요", short.remainingHint)
        assertEquals("건너뛰기", short.skip)
        assertEquals("START (4)", ready.confirm)
        assertNull(ready.remainingHint)
        assertEquals("SKIP", ready.skip)
    }

    @Test
    fun `editing saves and offers no skip`() {
        val edit = tastePickSheetCopy(AppLanguage.KO, TastePickMode.EDIT, remaining = 0, selectionCount = 1)

        assertEquals("저장", edit.confirm)
        assertNull(edit.skip)
        assertEquals(
            "SAVE",
            tastePickSheetCopy(AppLanguage.EN, TastePickMode.EDIT, remaining = 0, selectionCount = 0).confirm,
        )
    }

    @Test
    fun `the seeing less row and its release read in both languages`() {
        val ko = tastePickSheetCopy(AppLanguage.KO, TastePickMode.EDIT, remaining = 0, selectionCount = 1)
        val en = tastePickSheetCopy(AppLanguage.EN, TastePickMode.EDIT, remaining = 0, selectionCount = 1)

        assertEquals("덜 보기" to "되돌리기", ko.seeingLess to ko.release)
        assertEquals("SEEING LESS" to "UNDO", en.seeingLess to en.release)
    }
}
