package com.gallr.shared.route

import com.gallr.shared.data.model.AppLanguage
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * One row of the ranked public list (추천 동선), in the order the server ranked it (spec 089 US9). The server decides
 * eligibility and rank; the app only labels the row.
 */
data class PublicRouteSummary(
    val id: String,
    val name: String,
    val stopCount: Int,
    val firstDistrictKo: String,
    val firstDistrictEn: String,
    val lastDistrictKo: String,
    val lastDistrictEn: String,
    /** The author's current display name, or null when they have none. */
    val authorDisplayName: String?,
    /** True while the author holds an active editor membership. */
    val isEditor: Boolean,
    /** Copies of the currently approved version in the last 30 days. */
    val copyCount30d: Int,
    /** The first Seoul date on which every stop is running; after today when the route only works later. */
    val firstSharedDay: LocalDate,
    /** When the current version was approved; identifies the version copies count for. */
    val approvedAt: Instant,
)

/**
 * A listed route's stops read by id. [isMine] is decided by the server for the account that made the read, so the
 * reader's own route can be recognised without exposing the author's account id (DD22); [authorDisplayName] is the
 * author's profile name at read time, empty when unset.
 */
data class PublicRouteStops(
    val route: PersonalRoute,
    val isMine: Boolean,
    val authorDisplayName: String,
)

/** The two orders of the 동선 둘러보기 screen (spec 092); [wire] is the browse function's `p_sort`. */
enum class PublicRouteSort(
    val wire: String,
) {
    /** The 추천 동선 ranking: 30-day copies of the approved version, then the newer approval. */
    POPULAR("popular"),

    /** The newest approval first. */
    NEWEST("newest"),
}

/** What the reader asked the browse screen for; a null [districtKo] is every district (전체). */
data class PublicRouteBrowseQuery(
    val sort: PublicRouteSort = PublicRouteSort.POPULAR,
    val districtKo: String? = null,
)

/** A district some listed route passes through. [nameKo] is the filter key; [nameEn] is display text only. */
data class PublicRouteDistrict(
    val nameKo: String,
    val nameEn: String,
    val routeCount: Int,
) {
    fun localizedName(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> nameEn.ifBlank { nameKo }
            AppLanguage.KO -> nameKo
        }
}

/** A browse row: the 추천 동선 row and up to three covers of its stops, in stop order. */
data class BrowsedPublicRoute(
    val summary: PublicRouteSummary,
    val coverUrls: List<String>,
)

/** One page of the browse screen; [hasMore] is true when at least one more route follows it. */
data class PublicRoutePage(
    val routes: List<BrowsedPublicRoute>,
    val hasMore: Boolean,
)
