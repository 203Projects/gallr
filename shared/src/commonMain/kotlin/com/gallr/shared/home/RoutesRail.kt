package com.gallr.shared.home

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.route.PublicRouteSummary

/** How many public routes the Home tab's 지금 주목받는 동선 rail shows (spec 091, R5). */
const val HOME_ROUTES_LIMIT = 3

/** How many stop covers a route card shows before it counts the rest. */
const val HOME_ROUTE_COVER_LIMIT = 4

/**
 * A route on the Home rail: the ranking's summary and the covers of its stops, in stop order, for the stops the
 * catalogue still shows with a cover. No covers means the stops could not be read; the card then shows none.
 */
data class HomeRoute(
    val summary: PublicRouteSummary,
    val stopCoverUrls: List<String> = emptyList(),
) {
    /** The covers the card shows. */
    val shownCovers: List<String> get() = stopCoverUrls.take(HOME_ROUTE_COVER_LIMIT)

    /** Stops beyond the shown covers, counted on the card as `+N`; zero when the card shows no covers. */
    val hiddenStopCount: Int
        get() = if (shownCovers.isEmpty()) 0 else (summary.stopCount - shownCovers.size).coerceAtLeast(0)
}

/**
 * The routes the Home rail shows: the public ranking's first [limit] rows in the order the ranking returned them.
 * The ranking already excludes routes that are not shown, so nothing is dropped here; an empty result hides the rail.
 */
fun routesRail(
    summaries: List<PublicRouteSummary>,
    limit: Int = HOME_ROUTES_LIMIT,
): List<PublicRouteSummary> = if (limit <= 0) emptyList() else summaries.take(limit)

/**
 * The rail's cards: each shown route with its stops' covers. [stopExhibitionIds] maps a route id to its stops in
 * order; a stop whose exhibition is not in [exhibitions] or has no cover is skipped, and a cover shows once.
 */
fun homeRoutes(
    summaries: List<PublicRouteSummary>,
    stopExhibitionIds: Map<String, List<String>>,
    exhibitions: List<Exhibition>,
    limit: Int = HOME_ROUTES_LIMIT,
): List<HomeRoute> {
    val coverById = exhibitions.mapNotNull { show -> show.coverImageUrl?.let { show.id to it } }.toMap()
    return routesRail(summaries, limit).map { summary ->
        HomeRoute(
            summary = summary,
            stopCoverUrls = stopExhibitionIds[summary.id].orEmpty().mapNotNull(coverById::get).distinct(),
        )
    }
}
