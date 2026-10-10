package com.gallr.app.ui.route.publicroutes

import com.gallr.app.viewmodel.PublicRouteMessage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Spec 089 US9: the 추천 동선 section and preview wording (DD2, DD5, DD6, DD20, DD21, D24, D25). */
class PublicRoutesPresentationTest {
    private val today = LocalDate(2026, 10, 8)

    @Test
    fun theHeadingStatesTheOrder() {
        assertEquals("추천 동선", publicRoutesHeading(AppLanguage.KO))
        assertEquals("POPULAR ROUTES", publicRoutesHeading(AppLanguage.EN))
        assertEquals("최근 30일 복사 많은 순", publicRoutesOrderNote(AppLanguage.KO))
        assertEquals("Most copied in the last 30 days", publicRoutesOrderNote(AppLanguage.EN))
    }

    @Test
    fun aRowNamesItsDistrictsStopsAuthorAndCopies() {
        assertEquals("한남동–이태원동 · 4곳 · 하나 · 복사 12", publicRouteRowLine(row(), today, AppLanguage.KO))
        assertEquals(
            "Hannam-dong–Itaewon-dong · 4 STOPS · 하나 · COPIED 12",
            publicRouteRowLine(row(), today, AppLanguage.EN),
        )
    }

    @Test
    fun anEditorRowSaysEditorInsteadOfTheName() {
        assertEquals("한남동–이태원동 · 4곳 · 에디터 · 복사 12", publicRouteRowLine(row(editor = true), today, AppLanguage.KO))
        assertEquals(
            "Hannam-dong–Itaewon-dong · 4 STOPS · EDITOR · COPIED 12",
            publicRouteRowLine(row(editor = true), today, AppLanguage.EN),
        )
    }

    @Test
    fun oneDistrictNoAuthorAndNoCopiesAreLeftOut() {
        val plain = row(lastKo = "한남동", lastEn = "Hannam-dong", author = null, copies = 0)
        assertEquals("한남동 · 4곳", publicRouteRowLine(plain, today, AppLanguage.KO))
        assertEquals("Hannam-dong · 4 STOPS", publicRouteRowLine(plain, today, AppLanguage.EN))
    }

    @Test
    fun aRouteThatOnlyWorksLaterSaysFromWhen() {
        val later = row(firstSharedDay = LocalDate(2026, 10, 12))
        assertEquals("한남동–이태원동 · 4곳 · 하나 · 복사 12 · 10월 12일부터", publicRouteRowLine(later, today, AppLanguage.KO))
        assertEquals(
            "Hannam-dong–Itaewon-dong · 4 STOPS · 하나 · COPIED 12 · FROM OCT 12",
            publicRouteRowLine(later, today, AppLanguage.EN),
        )
    }

    @Test
    fun theSectionControlsAndError() {
        assertEquals("동선 더 보기", publicRoutesExpandLabel(expanded = false, AppLanguage.KO))
        assertEquals("접기", publicRoutesExpandLabel(expanded = true, AppLanguage.KO))
        assertEquals("MORE ROUTES", publicRoutesExpandLabel(expanded = false, AppLanguage.EN))
        assertEquals("SHOW LESS", publicRoutesExpandLabel(expanded = true, AppLanguage.EN))
        assertEquals("펼쳐짐", publicRoutesExpandedState(expanded = true, AppLanguage.KO))
        assertEquals("접힘", publicRoutesExpandedState(expanded = false, AppLanguage.KO))
        assertEquals("Expanded", publicRoutesExpandedState(expanded = true, AppLanguage.EN))
        assertEquals("Collapsed", publicRoutesExpandedState(expanded = false, AppLanguage.EN))
        assertEquals("추천 동선을 불러오지 못했어요", publicRoutesLoadFailedMessage(AppLanguage.KO))
        assertEquals("COULDN’T LOAD POPULAR ROUTES", publicRoutesLoadFailedMessage(AppLanguage.EN))
    }

    @Test
    fun thePreviewSaysWhichDayItShows() {
        assertNull(publicRoutePreviewDateLine(today, today, AppLanguage.KO))
        assertEquals("10월 12일 기준", publicRoutePreviewDateLine(LocalDate(2026, 10, 12), today, AppLanguage.KO))
        assertEquals("AS OF OCT 12", publicRoutePreviewDateLine(LocalDate(2026, 10, 12), today, AppLanguage.EN))
    }

    @Test
    fun thePreviewBylineAndActions() {
        assertEquals("에디터 · 4곳", publicRoutePreviewByline(row(editor = true), AppLanguage.KO))
        assertEquals("하나 · 4 STOPS", publicRoutePreviewByline(row(), AppLanguage.EN))
        assertEquals("4곳", publicRoutePreviewByline(row(author = null), AppLanguage.KO))
        assertEquals("내 동선에서 열기", openInMyRoutesLabel(AppLanguage.KO))
        assertEquals("OPEN IN MY ROUTES", openInMyRoutesLabel(AppLanguage.EN))
    }

    @Test
    fun thePreviewCopyButtonAndNotices() {
        assertEquals("내 동선으로 복사", copyToMyRouteLabel(busy = false, AppLanguage.KO))
        assertEquals("복사 중…", copyToMyRouteLabel(busy = true, AppLanguage.KO))
        assertEquals("COPY TO MY ROUTE", copyToMyRouteLabel(busy = false, AppLanguage.EN))
        assertEquals("COPYING…", copyToMyRouteLabel(busy = true, AppLanguage.EN))
        assertEquals("이 동선은 더 이상 공개 목록에 없어요", publicRouteNoLongerListedMessage(AppLanguage.KO))
        assertEquals("! THIS ROUTE IS NO LONGER LISTED", publicRouteNoLongerListedMessage(AppLanguage.EN))
        assertEquals("동선을 불러오지 못했어요", publicRouteLoadFailedMessage(AppLanguage.KO))
        assertEquals("COULDN’T LOAD THIS ROUTE", publicRouteLoadFailedMessage(AppLanguage.EN))
        assertEquals("더 보기", publicRouteMenuLabel(AppLanguage.KO))
        assertEquals("MORE", publicRouteMenuLabel(AppLanguage.EN))
    }

    @Test
    fun theReportSheetAndMenuWording() {
        assertEquals("이 동선을 신고하는 이유", reportSheetTitle(AppLanguage.KO))
        assertEquals("WHY ARE YOU REPORTING THIS ROUTE?", reportSheetTitle(AppLanguage.EN))
        assertEquals("부적절한 이름·내용", reportReasonLabel(RouteReportReason.Inappropriate, AppLanguage.KO))
        assertEquals("홍보·광고", reportReasonLabel(RouteReportReason.Promotional, AppLanguage.KO))
        assertEquals("잘못된 정보", reportReasonLabel(RouteReportReason.WrongInformation, AppLanguage.KO))
        assertEquals("기타", reportReasonLabel(RouteReportReason.Other, AppLanguage.KO))
        assertEquals(
            "INAPPROPRIATE NAME OR CONTENT",
            reportReasonLabel(RouteReportReason.Inappropriate, AppLanguage.EN),
        )
        assertEquals("PROMOTION OR AD", reportReasonLabel(RouteReportReason.Promotional, AppLanguage.EN))
        assertEquals("WRONG INFORMATION", reportReasonLabel(RouteReportReason.WrongInformation, AppLanguage.EN))
        assertEquals("OTHER", reportReasonLabel(RouteReportReason.Other, AppLanguage.EN))
        assertEquals("신고하기", reportSendLabel(busy = false, AppLanguage.KO))
        assertEquals("보내는 중…", reportSendLabel(busy = true, AppLanguage.KO))
        assertEquals("REPORT", reportSendLabel(busy = false, AppLanguage.EN))
        assertEquals("SENDING…", reportSendLabel(busy = true, AppLanguage.EN))
        assertEquals("신고", reportMenuLabel(reported = false, AppLanguage.KO))
        assertEquals("신고함", reportMenuLabel(reported = true, AppLanguage.KO))
        assertEquals("REPORT", reportMenuLabel(reported = false, AppLanguage.EN))
        assertEquals("REPORTED", reportMenuLabel(reported = true, AppLanguage.EN))
    }

    @Test
    fun copyAndReportSnackbars() {
        assertEquals("신고했어요", publicRouteMessageText(PublicRouteMessage.REPORTED, AppLanguage.KO))
        assertEquals("REPORTED", publicRouteMessageText(PublicRouteMessage.REPORTED, AppLanguage.EN))
        assertEquals("신고하지 못했어요", publicRouteMessageText(PublicRouteMessage.REPORT_FAILED, AppLanguage.KO))
        assertEquals(
            "COULDN’T SEND THE REPORT",
            publicRouteMessageText(PublicRouteMessage.REPORT_FAILED, AppLanguage.EN),
        )
        assertEquals("복사하지 못했어요", publicRouteMessageText(PublicRouteMessage.COPY_FAILED, AppLanguage.KO))
        assertEquals("COULDN’T COPY", publicRouteMessageText(PublicRouteMessage.COPY_FAILED, AppLanguage.EN))
        assertEquals("초안이 바뀌었어요", publicRouteMessageText(PublicRouteMessage.DRAFT_CHANGED, AppLanguage.KO))
        assertEquals("YOUR DRAFT CHANGED", publicRouteMessageText(PublicRouteMessage.DRAFT_CHANGED, AppLanguage.EN))
        assertNull(publicRouteMessageRetry(PublicRouteMessage.REPORTED, AppLanguage.KO))
        assertEquals("다시 시도", publicRouteMessageRetry(PublicRouteMessage.COPY_FAILED, AppLanguage.KO))
        assertEquals("RETRY", publicRouteMessageRetry(PublicRouteMessage.DRAFT_CHANGED, AppLanguage.EN))
    }

    private fun row(
        editor: Boolean = false,
        author: String? = "하나",
        lastKo: String = "이태원동",
        lastEn: String = "Itaewon-dong",
        copies: Int = 12,
        firstSharedDay: LocalDate = today,
    ) = PublicRouteSummary(
        id = "p1",
        name = "한남 산책",
        stopCount = 4,
        firstDistrictKo = "한남동",
        firstDistrictEn = "Hannam-dong",
        lastDistrictKo = lastKo,
        lastDistrictEn = lastEn,
        authorDisplayName = author,
        isEditor = editor,
        copyCount30d = copies,
        firstSharedDay = firstSharedDay,
        approvedAt = Instant.parse("2026-10-07T00:00:00Z"),
    )
}
