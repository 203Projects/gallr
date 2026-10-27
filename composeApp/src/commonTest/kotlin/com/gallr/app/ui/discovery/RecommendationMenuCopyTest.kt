package com.gallr.app.ui.discovery

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 101 US3: the 내 취향 card menu and the snackbar after 덜 보기. */
class RecommendationMenuCopyTest {
    @Test
    fun `the menu offers save or unsave then less like this then share`() {
        val saved = recommendationMenuCopy(AppLanguage.KO, isBookmarked = true)
        val unsaved = recommendationMenuCopy(AppLanguage.KO, isBookmarked = false)

        assertEquals(listOf("저장 취소", "덜 보기", "공유"), listOf(saved.save, saved.lessLikeThis, saved.share))
        assertEquals("저장", unsaved.save)
        assertEquals(
            listOf("SAVE", "LESS LIKE THIS", "SHARE"),
            recommendationMenuCopy(
                AppLanguage.EN,
                isBookmarked = false,
            ).let { listOf(it.save, it.lessLikeThis, it.share) },
        )
    }

    @Test
    fun `the snackbar says how long the card stays hidden and offers undo`() {
        assertEquals(LessLikeThisSnackbarCopy("숨겼어요 · 종료일까지", "되돌리기"), lessLikeThisSnackbarCopy(AppLanguage.KO))
        assertEquals(
            LessLikeThisSnackbarCopy("Hidden until it closes", "UNDO"),
            lessLikeThisSnackbarCopy(AppLanguage.EN),
        )
    }
}
