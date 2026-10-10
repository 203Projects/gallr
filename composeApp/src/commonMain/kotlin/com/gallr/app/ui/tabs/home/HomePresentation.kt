package com.gallr.app.ui.tabs.home

import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.home.HomeCollection
import com.gallr.shared.home.HomeCollectionKind
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

/** Fixed wording on the home tab; the greeting, the date line and the subtitles are built per state. */
internal data class HomeCopy(
    val forYouTitle: String,
    val forYouAction: String,
    val editorPicksTitle: String,
    val editorPicksSubtitle: String,
    val followedTitle: String,
    val followedSubtitle: String,
    val collectionsTitle: String,
    val collectionsSubtitle: String,
    val emptyMessage: String,
    val refresh: String,
    val heroLabel: String,
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
                collectionsTitle = "테마로 보기",
                collectionsSubtitle = "마감, 개막, 동네, 그리고 주제로 묶었어요",
                emptyMessage = "추천 전시가 없습니다.",
                refresh = "새로고침",
                heroLabel = "이번 주 추천",
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
                collectionsTitle = "BY THEME",
                collectionsSubtitle = "Closings, openings, neighbourhoods and subjects",
                emptyMessage = "No featured exhibitions right now.",
                refresh = "Refresh",
                heroLabel = "THIS WEEK'S PICKS",
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

/** The eyebrow over the title: `10월 10일 토요일` / `SATURDAY, OCTOBER 10`. */
internal fun homeDateLine(
    today: LocalDate,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "${today.month.ordinal + 1}월 ${today.day}일 ${today.dayOfWeek.koreanName()}"
        AppLanguage.EN -> "${today.dayOfWeek.englishName()}, ${today.month.englishName()} ${today.day}"
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
        HomeCollectionKind.CLOSING_THIS_WEEK -> {
            if (language == AppLanguage.KO) "마지막 기회 · ${count}개 전시" else "LAST CHANCE · $count EXHIBITIONS"
        }

        HomeCollectionKind.OPENING_THIS_WEEK -> {
            if (language == AppLanguage.KO) "새로 문을 여는 전시 ${count}개" else "$count NEW OPENINGS"
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

private fun DayOfWeek.koreanName(): String =
    when (this) {
        DayOfWeek.MONDAY -> "월요일"
        DayOfWeek.TUESDAY -> "화요일"
        DayOfWeek.WEDNESDAY -> "수요일"
        DayOfWeek.THURSDAY -> "목요일"
        DayOfWeek.FRIDAY -> "금요일"
        DayOfWeek.SATURDAY -> "토요일"
        DayOfWeek.SUNDAY -> "일요일"
    }

private fun DayOfWeek.englishName(): String = name

private fun Month.englishName(): String = name
