package com.gallr.app.ui.tabs.home

import com.gallr.app.ui.discovery.recommendationReasonLabel
import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.curatornote.CuratorEyeCard
import com.gallr.shared.curatornote.closingBadge
import com.gallr.shared.curatornote.curatorEyeFactsLine
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeCollection
import com.gallr.shared.home.HomeCollectionKind
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.route.PublicRouteSummary
import kotlin.math.abs

/**
 * The For You rail's cards: the top picks with their reasons as eyebrows. The rail sits under a title that already
 * says these are picks, so the implied Featured reason is dropped; and on a cold start, when the picks are the
 * editorial list the hero and the editors' rail already show, those exhibitions are left out so the rail adds
 * something. With a personal basis every pick stays: the reasons are what the visitor came for.
 */
internal fun forYouRailCards(
    state: RecommendationUiState,
    shownElsewhere: Set<String>,
    language: AppLanguage,
): List<RailCard> {
    val ready = state as? RecommendationUiState.Ready ?: return emptyList()
    val picks = if (ready.basis.isEmpty) ready.items.filter { it.exhibition.id !in shownElsewhere } else ready.items
    return picks.take(FOR_YOU_RAIL_LIMIT).map { pick ->
        val specific = pick.evidence.filterNot { it == RecommendationEvidence.Featured }
        RailCard(
            exhibition = pick.exhibition,
            eyebrow = if (specific.isEmpty()) null else recommendationReasonLabel(specific, language),
        )
    }
}

private const val FOR_YOU_RAIL_LIMIT = 6

/** Fixed wording on the home tab; the greeting, the date line and the subtitles are built per state. */
internal data class HomeCopy(
    val forYouTitle: String,
    val forYouAction: String,
    val editorPicksTitle: String,
    val editorPicksSubtitle: String,
    val followedTitle: String,
    val followedSubtitle: String,
    val curationTitle: String,
    val curationSubtitle: String,
    val themesTitle: String,
    val themesSubtitle: String,
    val emptyMessage: String,
    val refresh: String,
    val heroLabel: String,
    val featuredSubtitle: String,
    val tasteLabel: String,
    val curatorEyeTitle: String,
    val curatorEyeSubtitle: String,
    val routesTitle: String,
    val routesSubtitle: String,
)

internal fun homeCopy(
    language: AppLanguage,
    greetingName: String?,
): HomeCopy =
    when (language) {
        AppLanguage.KO -> {
            HomeCopy(
                forYouTitle = if (greetingName != null) "$greetingName 님의 취향" else "내 취향 추천",
                forYouAction = "모두 보기",
                editorPicksTitle = "에디터 추천",
                editorPicksSubtitle = "gallr 에디터가 직접 고른 이번 주 전시",
                followedTitle = "팔로우한 갤러리",
                followedSubtitle = "내가 팔로우한 곳에서 지금 열리는 전시",
                curationTitle = "큐레이션",
                curationSubtitle = "많이 보고, 곧 끝나고, 새로 열고, 무료인 전시",
                themesTitle = "상황별·주제별",
                themesSubtitle = "오늘 기분과 동네, 주제로 묶은 전시",
                emptyMessage = "추천 전시가 없습니다.",
                refresh = "새로고침",
                heroLabel = "이번 주 추천",
                featuredSubtitle = "gallr가 이번 주에 권하는 전시",
                tasteLabel = "내 취향",
                curatorEyeTitle = "큐레이터의 시선",
                curatorEyeSubtitle = "전시를 보기 전에 읽는 짧은 노트",
                routesTitle = "지금 주목받는 동선",
                routesSubtitle = "최근 30일에 가장 많이 담긴 동선",
            )
        }

        AppLanguage.EN -> {
            HomeCopy(
                forYouTitle = if (greetingName != null) "PICKED FOR $greetingName" else "FOR YOU",
                forYouAction = "SEE ALL",
                editorPicksTitle = "EDITOR'S PICKS",
                editorPicksSubtitle = "Chosen this week by the gallr editors",
                followedTitle = "GALLERIES YOU FOLLOW",
                followedSubtitle = "Showing now at the galleries you follow",
                curationTitle = "CURATIONS",
                curationSubtitle = "Most viewed, closing soon, newly open and free",
                themesTitle = "BY MOOD AND THEME",
                themesSubtitle = "Grouped by the day you are having, by area and by subject",
                emptyMessage = "No featured exhibitions right now.",
                refresh = "Refresh",
                heroLabel = "THIS WEEK'S PICKS",
                featuredSubtitle = "What gallr recommends this week",
                tasteLabel = "YOUR TASTE",
                curatorEyeTitle = "CURATOR'S EYE",
                curatorEyeSubtitle = "Short notes to read before you go",
                routesTitle = "ROUTES GETTING ATTENTION",
                routesSubtitle = "MOST COPIED IN THE LAST 30 DAYS",
            )
        }
    }

/** The page title: addresses a signed-in visitor by name, otherwise the city. */
internal fun homeGreeting(
    greetingName: String?,
    language: AppLanguage,
): String =
    when {
        greetingName != null && language == AppLanguage.KO -> "$greetingName 님, 이번 주 볼 만한 전시"
        greetingName != null -> "This week for $greetingName"
        language == AppLanguage.KO -> "이번 주 서울의 전시"
        else -> "This week in Seoul"
    }

/** One sentence for a screen reader: section, hook, insight, exhibition, facts, closing badge and position. */
internal fun curatorEyeCardDescription(
    card: CuratorEyeCard,
    index: Int,
    count: Int,
    language: AppLanguage,
): String {
    val section = if (language == AppLanguage.KO) "큐레이터의 시선" else "Curator's eye"
    return listOfNotNull(
        section,
        card.note.hook(language),
        card.note.firstInsight(language),
        card.exhibition.localizedName(language),
        curatorEyeFactsLine(card, language),
        closingBadge(card.daysToClosing),
        "${index + 1}/$count",
    ).joinToString(", ")
}

/** Where the hero pager stands: `01 / 06`, zero-padded so the width never jumps between pages. */
internal fun pagerCounter(
    pageIndex: Int,
    pageCount: Int,
): String = "${(pageIndex + 1).twoDigits()} / ${pageCount.twoDigits()}"

/** The line under a collection's headline: what gathers these exhibitions and how many there are. */
internal fun collectionSubtitle(
    collection: HomeCollection,
    language: AppLanguage,
): String {
    val count = collection.count
    return when (collection.kind) {
        HomeCollectionKind.MOST_VIEWED -> {
            if (language == AppLanguage.KO) "이번 주 기준 · ${count}개 전시" else "THIS WEEK · $count EXHIBITIONS"
        }

        HomeCollectionKind.FREE_ADMISSION -> {
            if (language == AppLanguage.KO) "에디터 확인 · ${count}개 전시" else "EDITOR CONFIRMED · $count EXHIBITIONS"
        }

        HomeCollectionKind.CLOSING_THIS_WEEK -> {
            if (language == AppLanguage.KO) "마지막 기회 · ${count}개 전시" else "LAST CHANCE · $count EXHIBITIONS"
        }

        HomeCollectionKind.OPENING_THIS_WEEK -> {
            if (language == AppLanguage.KO) "새로 문을 여는 전시 ${count}개" else "$count NEW OPENINGS"
        }

        HomeCollectionKind.SITUATION -> {
            val tags = collection.localizedTags(language).joinToString(" ") { "#${it.replace(' ', '_')}" }
            if (language == AppLanguage.KO) "$tags · ${count}개 전시" else "$tags · $count EXHIBITIONS"
        }

        HomeCollectionKind.NEIGHBORHOOD,
        HomeCollectionKind.THEME,
        -> {
            val tag = "#${collection.localizedTitle(language).replace(' ', '_')}"
            if (language == AppLanguage.KO) "$tag · ${count}개 전시" else "${tag.uppercase()} · $count EXHIBITIONS"
        }
    }
}

/** Under the For You title: what the picks rest on, or the nudge that tunes them; null while nothing is ready. */
internal fun forYouSubtitle(
    state: RecommendationUiState,
    language: AppLanguage,
): String? {
    val ready = state as? RecommendationUiState.Ready ?: return null
    if (ready.items.isEmpty()) return null
    return when {
        ready.basis.isEmpty && language == AppLanguage.KO -> "전시를 저장하면 취향에 맞춰 골라드려요"
        ready.basis.isEmpty -> "Save exhibitions to tune these picks"
        else -> basisLine(ready.basis, language)
    }
}

private fun basisLine(
    basis: RecommendationBasis,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            "저장 ${basis.savedCount} · 방문 ${basis.visitedCount} · 팔로우 ${basis.followedCount} 기반"
        }

        AppLanguage.EN -> {
            "BASED ON ${basis.savedCount} SAVED · ${basis.visitedCount} VISITED · ${basis.followedCount} FOLLOWED"
        }
    }

private fun Int.twoDigits(): String = toString().padStart(2, '0')

/** `3곳` / `3 stops` for a route card on the 지금 주목받는 동선 rail (spec 091). */
internal fun routeStopCount(
    stopCount: Int,
    language: AppLanguage,
): String =
    when {
        language == AppLanguage.KO -> "${stopCount}곳"
        stopCount == 1 -> "1 stop"
        else -> "$stopCount stops"
    }

/** `종로구 → 용산구` / `Jongno-gu → Yongsan-gu`; one district when the route starts and ends in the same one. */
internal fun routeDistricts(
    route: PublicRouteSummary,
    language: AppLanguage,
): String {
    val first = if (language == AppLanguage.KO) route.firstDistrictKo else route.firstDistrictEn
    val last = if (language == AppLanguage.KO) route.lastDistrictKo else route.lastDistrictEn
    return if (first == last) first else "$first → $last"
}

/** The author line of a route card: the display name, or the unknown-author copy. */
internal fun routeAuthorLine(
    route: PublicRouteSummary,
    language: AppLanguage,
): String {
    val name = route.authorDisplayName?.takeIf { it.isNotBlank() }
    return when {
        name == null && language == AppLanguage.KO -> "이름 없는 작성자"
        name == null -> "Unnamed author"
        route.isEditor && language == AppLanguage.KO -> "에디터 · $name"
        route.isEditor -> "EDITOR · $name"
        else -> name
    }
}

/** `복사 24회` / `24 COPIES` for a route copied in the last 30 days; null when nobody has, so the card says nothing. */
internal fun routeCopyCount(
    copies: Int,
    language: AppLanguage,
): String? =
    when {
        copies <= 0 -> null
        language == AppLanguage.KO -> "복사 ${copies}회"
        copies == 1 -> "1 COPY"
        else -> "$copies COPIES"
    }

/** One sentence for a screen reader: section, position, name, author, copies, stops and districts. */
internal fun routeCardDescription(
    route: PublicRouteSummary,
    index: Int,
    count: Int,
    title: String,
    language: AppLanguage,
): String =
    listOfNotNull(
        title,
        "${index + 1} / $count",
        route.name,
        routeAuthorLine(route, language),
        routeCopyCount(route.copyCount30d, language),
        routeStopCount(route.stopCount, language),
        routeDistricts(route, language),
    ).joinToString(", ")

/** What leads the Home tab under the greeting (spec 091 FR-015). */
internal enum class HomeLead {
    /** The visitor's own picks as the banner; the featured list follows as a rail. */
    FOR_YOU,

    /** The featured list as the banner; picks without a personal basis stay a rail. */
    FEATURED,
}

/** Picks lead only when they rest on the visitor's own saves, visits or follows; otherwise the editors' list does. */
internal fun homeLead(recommendations: RecommendationUiState): HomeLead {
    val ready = recommendations as? RecommendationUiState.Ready ?: return HomeLead.FEATURED
    return if (ready.items.isNotEmpty() && !ready.basis.isEmpty) HomeLead.FOR_YOU else HomeLead.FEATURED
}

/** The banner's last line: where it is and when it runs. */
internal fun bannerMetaLine(
    exhibition: Exhibition,
    language: AppLanguage,
): String = "${exhibition.localizedVenueName(language)} · ${exhibition.localizedDateRange(language)}"

private val CURATION_KINDS =
    setOf(
        HomeCollectionKind.MOST_VIEWED,
        HomeCollectionKind.CLOSING_THIS_WEEK,
        HomeCollectionKind.OPENING_THIS_WEEK,
        HomeCollectionKind.FREE_ADMISSION,
    )

/** The 큐레이션 grid: what visitors opened most, the timing collections and free admission, in feed order. */
internal fun curationTiles(collections: List<HomeCollection>): List<HomeCollection> =
    collections.filter { it.kind in CURATION_KINDS }

/** The 상황별·주제별 rail: situations, neighbourhoods and art terms, in feed order. */
internal fun themeTiles(collections: List<HomeCollection>): List<HomeCollection> =
    collections.filterNot { it.kind in CURATION_KINDS }

/** A headline up to this long fits a small tile on one line at the default type size, in Korean as well. */
private const val HEADLINE_SINGLE_LINE_LENGTH = 6

/**
 * A small tile's headline on two balanced lines: broken at the space nearest its middle, so a Korean headline never
 * splits inside a word. A single word, or one short enough for one line, is returned unchanged.
 */
internal fun balancedHeadline(title: String): String {
    if (title.length <= HEADLINE_SINGLE_LINE_LENGTH) return title
    val middle = (title.length - 1) / 2.0
    val breakAt =
        title.indices
            .filter { title[it] == ' ' }
            .minByOrNull { abs(it - middle) }
            ?: return title
    return title.substring(0, breakAt) + "\n" + title.substring(breakAt + 1)
}
