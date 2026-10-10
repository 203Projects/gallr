package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.DraftRouteRow
import com.gallr.app.viewmodel.MyRoutesError
import com.gallr.app.viewmodel.MyRoutesUiState
import com.gallr.app.viewmodel.SavedRoutesState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PersonalRouteSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 089 DR-D10: the 내 동선 list copy. */
class MyRoutesPresentationTest {
    private val updated = Instant.parse("2026-10-08T04:00:00Z")

    @Test
    fun rowsStateStopCountAndVisibility() {
        // DD13 (2026-10-08) replaced "공개됨 / 비공개" with the link and listing state; a revocation moves to line 3.
        assertEquals("3곳 · 링크 공개 · 목록 미등록", myRouteRowLabel(summary(published = true), AppLanguage.KO))
        assertEquals("3곳 · 나만 보기 · 목록 미등록", myRouteRowLabel(summary(), AppLanguage.KO))
        assertEquals("3곳 · 나만 보기 · 목록 미등록", myRouteRowLabel(summary(published = true, revoked = true), AppLanguage.KO))
        assertEquals(
            listOf("운영 정책으로 내려져 링크가 열리지 않아요"),
            myRouteListingReasons(summary(published = true, revoked = true), AppLanguage.KO),
        )
        assertEquals("3 STOPS · LINK SHARED · NOT LISTED", myRouteRowLabel(summary(published = true), AppLanguage.EN))
        assertEquals("3 STOPS · ONLY YOU · NOT LISTED", myRouteRowLabel(summary(), AppLanguage.EN))
        assertEquals("작성 중 · 2곳", myRoutesDraftLabel(2, AppLanguage.KO))
        assertEquals("DRAFT · 2 STOPS", myRoutesDraftLabel(2, AppLanguage.EN))
        assertEquals("DRAFT · 1 STOP", myRoutesDraftLabel(1, AppLanguage.EN))
        assertEquals("이름 없는 동선", myRouteDisplayName("  ", AppLanguage.KO))
        assertEquals("UNTITLED ROUTE", myRouteDisplayName("", AppLanguage.EN))
    }

    @Test
    fun sectionStates() {
        assertEquals("내 동선", myRoutesTitle(AppLanguage.KO))
        assertEquals("첫 동선을 만들어 친구에게 보내 보세요", myRoutesEmptyMessage(AppLanguage.KO))
        assertEquals("로그인하고 저장한 동선 보기", myRoutesSignInLabel(AppLanguage.KO))
        assertEquals("SIGN IN TO SEE SAVED ROUTES", myRoutesSignInLabel(AppLanguage.EN))
        assertEquals(listOf("열기", "공유", "삭제"), MyRouteAction.entries.map { myRouteActionLabel(it, AppLanguage.KO) })
        val english = MyRouteAction.entries.map { myRouteActionLabel(it, AppLanguage.EN) }
        assertEquals(listOf("OPEN", "SHARE", "DELETE"), english)
    }

    @Test
    fun theMyTabSectionNamesRoutesAndOffersANewOne() {
        assertEquals("동선", myRoutesSectionLabel(AppLanguage.KO))
        assertEquals("ROUTES", myRoutesSectionLabel(AppLanguage.EN))
        assertEquals("+ 새 동선 만들기", myRoutesNewRouteLabel(AppLanguage.KO))
        assertEquals("+ NEW ROUTE", myRoutesNewRouteLabel(AppLanguage.EN))
    }

    @Test
    fun theEmptyStateShowsOnlyWhenThereIsNoRouteToList() {
        val draft = DraftRouteRow("토요일", 2)
        assertTrue(myRoutesShowsEmptyState(MyRoutesUiState(saved = SavedRoutesState.SignedOut)))
        assertTrue(myRoutesShowsEmptyState(MyRoutesUiState(saved = SavedRoutesState.Loaded(emptyList()))))
        assertFalse(myRoutesShowsEmptyState(MyRoutesUiState(draftRow = draft, saved = SavedRoutesState.SignedOut)))
        assertFalse(myRoutesShowsEmptyState(MyRoutesUiState(saved = SavedRoutesState.Loaded(listOf(summary())))))
        assertFalse(myRoutesShowsEmptyState(MyRoutesUiState(saved = SavedRoutesState.Loading)))
        assertFalse(myRoutesShowsEmptyState(MyRoutesUiState(saved = SavedRoutesState.Error)))
    }

    @Test
    fun deleteDialogWarnsOnlyForPublicRoutes() {
        assertEquals(
            "이 동선을 삭제할까요? 공유한 링크도 더 이상 열리지 않아요",
            myRouteDeleteMessage(summary(published = true), AppLanguage.KO),
        )
        assertEquals("이 동선을 삭제할까요?", myRouteDeleteMessage(summary(), AppLanguage.KO))
        assertEquals(
            "Delete this route? The shared link will stop working too.",
            myRouteDeleteMessage(summary(published = true), AppLanguage.EN),
        )
    }

    @Test
    fun errors() {
        assertEquals("동선을 열지 못했어요 · 다시 시도", myRoutesErrorMessage(MyRoutesError.OPEN_FAILED, AppLanguage.KO))
        assertEquals("공유하지 못했어요 · 다시 시도", myRoutesErrorMessage(MyRoutesError.SHARE_FAILED, AppLanguage.KO))
        assertEquals("삭제하지 못했어요 · 다시 시도", myRoutesErrorMessage(MyRoutesError.DELETE_FAILED, AppLanguage.KO))
        assertEquals("내 동선을 불러오지 못했어요", myRoutesLoadFailedMessage(AppLanguage.KO))
    }

    private fun summary(
        published: Boolean = false,
        revoked: Boolean = false,
    ) = PersonalRouteSummary("route-1", "동선 1", 3, published, revoked, updated)
}
