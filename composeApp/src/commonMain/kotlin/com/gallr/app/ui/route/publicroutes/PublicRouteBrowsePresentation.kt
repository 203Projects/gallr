package com.gallr.app.ui.route.publicroutes

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.BrowsedPublicRoute
import com.gallr.shared.route.PublicRouteDistrict
import com.gallr.shared.route.PublicRouteSort
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate

/** The 동선 둘러보기 screen title (spec 092). */
internal fun publicRouteBrowseTitle(language: AppLanguage): String = language.pick("동선 둘러보기", "BROWSE ROUTES")

/** The action on the Home rail and the Profile 추천 동선 heading that opens the browse screen (FR-002, FR-003). */
internal fun publicRoutesSeeAllLabel(language: AppLanguage): String = language.pick("전체 보기", "SEE ALL")

/** Shown when no route is listed: on the Profile section and as the browse screen's empty state. */
internal fun publicRoutesNoneYetMessage(language: AppLanguage): String =
    language.pick("아직 공유된 동선이 없어요", "NO SHARED ROUTES YET")

/** A row read as one description (FR-016): its name, then the 추천 동선 row line. */
internal fun publicRouteBrowseRowDescription(
    route: PublicRouteSummary,
    today: LocalDate,
    language: AppLanguage,
): String = "${route.name}, ${publicRouteRowLine(route, today, language)}"

/** The covers a row shows, in stop order (FR-004). */
internal fun browseRowCovers(route: BrowsedPublicRoute): List<String> = route.coverUrls.take(BROWSE_ROW_COVERS)

internal fun publicRouteSortLabel(
    sort: PublicRouteSort,
    language: AppLanguage,
): String =
    when (sort) {
        PublicRouteSort.POPULAR -> language.pick("인기", "POPULAR")
        PublicRouteSort.NEWEST -> language.pick("최신", "NEWEST")
    }

/** The first district chip, which clears the filter. */
internal fun publicRouteAllDistrictsLabel(language: AppLanguage): String = language.pick("전체", "ALL")

/** The filtered empty state: "성수동을 지나는 동선이 없어요" / "을지로를 …". */
internal fun publicRouteDistrictEmptyMessage(
    district: PublicRouteDistrict,
    language: AppLanguage,
): String =
    language.pick(
        "${district.nameKo}${objectParticle(district.nameKo)} 지나는 동선이 없어요",
        "NO ROUTES THROUGH ${district.localizedName(language).uppercase()}",
    )

internal fun publicRouteClearFilterLabel(language: AppLanguage): String = language.pick("필터 지우기", "CLEAR FILTER")

/** The end of the list when a later page could not be read; 다시 시도 reads it again. */
internal fun publicRouteLoadMoreFailedMessage(language: AppLanguage): String =
    language.pick("더 불러오지 못했어요", "COULDN’T LOAD MORE ROUTES")

/** 을 after a final consonant, 를 after a vowel; both when the word does not end in a Hangul syllable. */
private fun objectParticle(word: String): String {
    val last = word.lastOrNull() ?: return "을(를)"
    if (last !in HANGUL_SYLLABLES) return "을(를)"
    val hasFinalConsonant = (last - HANGUL_SYLLABLES.first) % FINAL_CONSONANT_COUNT != 0
    return if (hasFinalConsonant) "을" else "를"
}

private val HANGUL_SYLLABLES = '가'..'힣'
private const val FINAL_CONSONANT_COUNT = 28
private const val BROWSE_ROW_COVERS = 3

private fun AppLanguage.pick(
    korean: String,
    english: String,
): String = if (this == AppLanguage.KO) korean else english
