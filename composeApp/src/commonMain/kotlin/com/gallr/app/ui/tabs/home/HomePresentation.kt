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
import com.gallr.shared.recommendation.hasPersonalEvidence
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.taste.TastePickState
import kotlin.math.abs

/** What the 내 취향 slot on Home shows (spec 101 FR-004, FR-009). */
internal sealed interface ForYouSection {
    /** Nothing personal is expected, or the run failed: the slot takes no space and the featured banner leads. */
    data object Hidden : ForYouSection

    /**
     * Picks are still being computed for a visitor who has something to go on: banner-sized skeletons hold the slot at
     * its final height so the banner does not swap from featured to 내 취향 (spec 101 US7).
     */
    data class Loading(
        /** The visitor picked a taste, so the chip row under the banner is certain and is held too. */
        val showsTasteRow: Boolean,
    ) : ForYouSection

    /** One to six cards, each explained by the visitor's own pick, save, visit or follow. */
    data class Picks(
        val cards: List<RailCard>,
    ) : ForYouSection

    /** Nothing personal to show: a card that opens the taste sheet instead of editorial cards. */
    data class Prompt(
        val kind: TastePromptKind,
    ) : ForYouSection
}

internal enum class TastePromptKind {
    /** No pick, save, visit or follow yet, or the visitor skipped the sheet. */
    NO_SIGNAL,

    /** The visitor picked a taste but nothing running matches it. */
    NOTHING_MATCHES,
}

/**
 * The 내 취향 slot: only cards with personal evidence, never an editorial card under a taste heading. The
 * implied Featured reason is dropped from eyebrows because the section title already says these are picks.
 */
internal fun forYouSection(
    state: RecommendationUiState,
    language: AppLanguage,
    pending: PendingTaste = PendingTaste.NONE,
): ForYouSection {
    val ready =
        when (state) {
            RecommendationUiState.Loading -> return pending.loadingSection()
            RecommendationUiState.Error -> return ForYouSection.Hidden
            RecommendationUiState.Empty -> return ForYouSection.Prompt(TastePromptKind.NO_SIGNAL)
            is RecommendationUiState.Ready -> state
        }
    val personal = ready.items.filter { it.hasPersonalEvidence }
    if (personal.isEmpty()) {
        val kind = if (ready.basis.pickedCount > 0) TastePromptKind.NOTHING_MATCHES else TastePromptKind.NO_SIGNAL
        return ForYouSection.Prompt(kind)
    }
    return ForYouSection.Picks(
        personal.take(FOR_YOU_RAIL_LIMIT).map { pick ->
            val specific = pick.evidence.filterNot { it == RecommendationEvidence.Featured }
            RailCard(
                exhibition = pick.exhibition,
                eyebrow = if (specific.isEmpty()) null else recommendationReasonLabel(specific, language),
            )
        },
    )
}

/** What the visitor has given to go on before the run finishes, which decides the shape held while it computes. */
enum class PendingTaste {
    /** No pick, save, visit or follow (or not known yet): the featured banner leads at once. */
    NONE,

    /** Saves, visits or follows but no picked taste: the banner is held, the chip row may not come. */
    HISTORY,

    /** A picked taste: the banner and the chip row are both held. */
    PICKED,
}

/** Unknown history counts as none, so a visitor with nothing never sees a skeleton swap out. */
internal fun pendingTaste(
    hasHistory: Boolean?,
    taste: TastePickState,
): PendingTaste =
    when {
        !taste.seed.isEmpty -> PendingTaste.PICKED
        hasHistory == true -> PendingTaste.HISTORY
        else -> PendingTaste.NONE
    }

private fun PendingTaste.loadingSection(): ForYouSection =
    when (this) {
        PendingTaste.NONE -> ForYouSection.Hidden
        PendingTaste.HISTORY -> ForYouSection.Loading(showsTasteRow = false)
        PendingTaste.PICKED -> ForYouSection.Loading(showsTasteRow = true)
    }

/**
 * Whether the 내 취향 row of chips shows under the banner: when there are tags to show, or picks to edit even
 * though none of them yields a tag (picks made of covers alone), so the sheet is always one tap away.
 */
internal fun showsTasteRow(
    state: RecommendationUiState,
    tagCount: Int,
): Boolean {
    val ready = state as? RecommendationUiState.Ready ?: return false
    return tagCount > 0 || ready.basis.pickedCount > 0
}

internal data class TastePromptCopy(
    val message: String,
    val action: String,
)

internal fun tastePromptCopy(
    kind: TastePromptKind,
    language: AppLanguage,
): TastePromptCopy =
    when (kind) {
        TastePromptKind.NO_SIGNAL -> {
            if (language == AppLanguage.KO) {
                TastePromptCopy("전시를 고르거나 저장하면 취향에 맞춰 골라드려요", "취향 고르기")
            } else {
                TastePromptCopy("Pick or save exhibitions and the picks follow your taste", "PICK YOUR TASTE")
            }
        }

        TastePromptKind.NOTHING_MATCHES -> {
            if (language == AppLanguage.KO) {
                TastePromptCopy("선택한 취향에 맞는 전시가 아직 없어요", "취향 바꾸기")
            } else {
                TastePromptCopy("Nothing running matches your picks yet", "CHANGE PICKS")
            }
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
    /** Opens 동선 둘러보기, every listed route (spec 092). */
    val routesAction: String,
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
                curatorEyeTitle = "에디터의 시선",
                curatorEyeSubtitle = "전시를 보기 전에 읽는 짧은 노트",
                routesTitle = "지금 주목받는 동선",
                routesSubtitle = "최근 30일에 가장 많이 담긴 동선",
                routesAction = "전체 보기",
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
                curatorEyeTitle = "EDITOR'S EYE",
                curatorEyeSubtitle = "Short notes to read before you go",
                routesTitle = "ROUTES GETTING ATTENTION",
                routesSubtitle = "MOST COPIED IN THE LAST 30 DAYS",
                routesAction = "SEE ALL",
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
    val section = if (language == AppLanguage.KO) "에디터의 시선" else "Editor's eye"
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

/**
 * Under the For You title: what the picks rest on; null while nothing is ready or nothing is personal, because
 * then the slot shows the taste prompt instead (spec 101 FR-004).
 */
internal fun forYouSubtitle(
    state: RecommendationUiState,
    language: AppLanguage,
): String? {
    val ready = state as? RecommendationUiState.Ready ?: return null
    if (ready.items.isEmpty() || ready.basis.isEmpty) return null
    return basisLine(ready.basis, language)
}

/**
 * A basis line in the visitor's language, drawn invisibly under the skeleton bar while picks compute. It is never
 * read: it only gives the held line the height the real one will take, which differs between Hangul and Latin text.
 */
internal fun forYouSubtitlePlaceholder(language: AppLanguage): String =
    basisLine(RecommendationBasis(savedCount = 0, visitedCount = 0, followedCount = 0, pickedCount = 1), language)

private fun basisLine(
    basis: RecommendationBasis,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            val picked = if (basis.pickedCount > 0) "선택 ${basis.pickedCount} · " else ""
            "${picked}저장 ${basis.savedCount} · 방문 ${basis.visitedCount} · 팔로우 ${basis.followedCount} 기반"
        }

        AppLanguage.EN -> {
            val picked = if (basis.pickedCount > 0) "${basis.pickedCount} PICKED · " else ""
            "BASED ON ${picked}${basis.savedCount} SAVED · ${basis.visitedCount} VISITED · ${basis.followedCount} FOLLOWED"
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

/**
 * Picks lead when at least one card rests on the visitor's own signals, or while such picks are still computing;
 * otherwise the editors' list does.
 */
internal fun homeLead(section: ForYouSection): HomeLead =
    when (section) {
        is ForYouSection.Picks, is ForYouSection.Loading -> HomeLead.FOR_YOU
        ForYouSection.Hidden, is ForYouSection.Prompt -> HomeLead.FEATURED
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
