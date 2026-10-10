package com.gallr.app.ui.route.publicroutes

import com.gallr.app.viewmodel.PublicRouteMessage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason
import kotlinx.datetime.LocalDate

/** The 추천 동선 heading (DD6). */
internal fun publicRoutesHeading(language: AppLanguage): String = language.pick("추천 동선", "POPULAR ROUTES")

/** The order note under the heading, so readers know what the ranking means (DD6). */
internal fun publicRoutesOrderNote(language: AppLanguage): String =
    language.pick("최근 30일 복사 많은 순", "Most copied in the last 30 days")

/**
 * A row's second line (DD5, DD20): "{first}–{last} · {n}곳 · {에디터 | author} · 복사 {n}", with the author left out
 * when there is no display name, the count left out at zero, and "{M}월 {D}일부터" when the route only works later.
 */
internal fun publicRouteRowLine(
    route: PublicRouteSummary,
    today: LocalDate,
    language: AppLanguage,
): String {
    val parts =
        buildList {
            add(districts(route, language))
            add(language.pick("${route.stopCount}곳", "${route.stopCount} STOPS"))
            authorPart(route, language)?.let(::add)
            if (route.copyCount30d > 0) add(language.pick("복사 ${route.copyCount30d}", "COPIED ${route.copyCount30d}"))
            if (route.firstSharedDay > today) {
                add(language.pick("${koreanDate(route.firstSharedDay)}부터", "FROM ${englishDate(route.firstSharedDay)}"))
            }
        }
    return parts.joinToString(" · ")
}

private fun districts(
    route: PublicRouteSummary,
    language: AppLanguage,
): String {
    val first = language.pick(route.firstDistrictKo, route.firstDistrictEn.ifBlank { route.firstDistrictKo })
    val last = language.pick(route.lastDistrictKo, route.lastDistrictEn.ifBlank { route.lastDistrictKo })
    return if (first == last) first else "$first–$last"
}

private fun authorPart(
    route: PublicRouteSummary,
    language: AppLanguage,
): String? = if (route.isEditor) language.pick("에디터", "EDITOR") else route.authorDisplayName

internal fun publicRoutesExpandLabel(
    expanded: Boolean,
    language: AppLanguage,
): String = if (expanded) language.pick("접기", "SHOW LESS") else language.pick("동선 더 보기", "MORE ROUTES")

/** Read by screen readers as the expand button's state, separate from its action label. */
internal fun publicRoutesExpandedState(
    expanded: Boolean,
    language: AppLanguage,
): String = if (expanded) language.pick("펼쳐짐", "Expanded") else language.pick("접힘", "Collapsed")

internal fun publicRoutesLoadFailedMessage(language: AppLanguage): String =
    language.pick("추천 동선을 불러오지 못했어요", "COULDN’T LOAD POPULAR ROUTES")

/** The preview's date line when it is judged for a later day (DD21); null when it is judged for today. */
internal fun publicRoutePreviewDateLine(
    referenceDay: LocalDate,
    today: LocalDate,
    language: AppLanguage,
): String? {
    if (referenceDay <= today) return null
    return language.pick("${koreanDate(referenceDay)} 기준", "AS OF ${englishDate(referenceDay)}")
}

/** "에디터 · 4곳" or "{author} · 4곳"; just the count without a display name (DD1). */
internal fun publicRoutePreviewByline(
    route: PublicRouteSummary,
    language: AppLanguage,
): String {
    val count = language.pick("${route.stopCount}곳", "${route.stopCount} STOPS")
    return authorPart(route, language)?.let { "$it · $count" } ?: count
}

/** The preview's action on the reader's own route (DD22). */
internal fun openInMyRoutesLabel(language: AppLanguage): String = language.pick("내 동선에서 열기", "OPEN IN MY ROUTES")

/** The snackbar when the reader's own route could not be read for the composer; 다시 시도 asks again (DD22). */
internal fun openInMyRoutesFailedMessage(language: AppLanguage): String =
    language.pick("동선을 열지 못했어요", "COULDN’T OPEN THIS ROUTE")

/** The preview's copy button (DD19), reading "복사 중…" while the copy runs (DD8). */
internal fun copyToMyRouteLabel(
    busy: Boolean,
    language: AppLanguage,
): String = if (busy) language.pick("복사 중…", "COPYING…") else language.pick("내 동선으로 복사", "COPY TO MY ROUTE")

internal fun publicRouteNoLongerListedMessage(language: AppLanguage): String =
    language.pick("! 이 동선은 더 이상 공개 목록에 없어요", "! THIS ROUTE IS NO LONGER LISTED")

internal fun publicRouteLoadFailedMessage(language: AppLanguage): String =
    language.pick("동선을 불러오지 못했어요", "COULDN’T LOAD THIS ROUTE")

/** The preview header's ⋯ button (D26). */
internal fun publicRouteMenuLabel(language: AppLanguage): String = language.pick("더 보기", "MORE")

internal fun reportSheetTitle(language: AppLanguage): String =
    language.pick("이 동선을 신고하는 이유", "WHY ARE YOU REPORTING THIS ROUTE?")

internal fun reportReasonLabel(
    reason: RouteReportReason,
    language: AppLanguage,
): String =
    when (reason) {
        RouteReportReason.Inappropriate -> language.pick("부적절한 이름·내용", "INAPPROPRIATE NAME OR CONTENT")
        RouteReportReason.Promotional -> language.pick("홍보·광고", "PROMOTION OR AD")
        RouteReportReason.WrongInformation -> language.pick("잘못된 정보", "WRONG INFORMATION")
        RouteReportReason.Other -> language.pick("기타", "OTHER")
    }

internal fun reportSendLabel(
    busy: Boolean,
    language: AppLanguage,
): String = if (busy) language.pick("보내는 중…", "SENDING…") else language.pick("신고하기", "REPORT")

/** The ⋯ menu item; "신고함" (disabled) while the reader's report is open (DD10). */
internal fun reportMenuLabel(
    reported: Boolean,
    language: AppLanguage,
): String = if (reported) language.pick("신고함", "REPORTED") else language.pick("신고", "REPORT")

internal fun publicRouteMessageText(
    message: PublicRouteMessage,
    language: AppLanguage,
): String =
    when (message) {
        PublicRouteMessage.REPORTED -> language.pick("신고했어요", "REPORTED")
        PublicRouteMessage.REPORT_FAILED -> language.pick("신고하지 못했어요", "COULDN’T SEND THE REPORT")
        PublicRouteMessage.COPY_FAILED -> language.pick("복사하지 못했어요", "COULDN’T COPY")
        PublicRouteMessage.DRAFT_CHANGED -> language.pick("초안이 바뀌었어요", "YOUR DRAFT CHANGED")
    }

/** The snackbar action that repeats the failed call; null when there is nothing to retry. */
internal fun publicRouteMessageRetry(
    message: PublicRouteMessage,
    language: AppLanguage,
): String? = if (message == PublicRouteMessage.REPORTED) null else language.pick("다시 시도", "RETRY")

private fun koreanDate(date: LocalDate): String = "${date.month.ordinal + 1}월 ${date.day}일"

private fun englishDate(date: LocalDate): String = "${ENGLISH_MONTHS[date.month.ordinal]} ${date.day}"

private val ENGLISH_MONTHS =
    listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

private fun AppLanguage.pick(
    korean: String,
    english: String,
): String = if (this == AppLanguage.KO) korean else english
