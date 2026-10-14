package com.gallr.shared.route

/**
 * The loaded rows with [page] added after them. Pages are read by offset while copy counts and approvals keep
 * changing, so a route can move into a later page: a route already loaded is kept where it is and not shown twice.
 */
fun appendPage(
    loaded: List<BrowsedPublicRoute>,
    page: PublicRoutePage,
): List<BrowsedPublicRoute> {
    val seen = loaded.mapTo(mutableSetOf()) { it.summary.id }
    return loaded + page.routes.filter { seen.add(it.summary.id) }
}

/**
 * The offset of the next page: every row requested so far, not every row kept, so a dropped duplicate never makes
 * the next request read the same window again.
 */
fun nextOffset(
    pagesLoaded: Int,
    pageSize: Int,
): Int = pagesLoaded * pageSize

/** The selected district while some listed route still passes through it; otherwise every district (null). */
fun resolveDistrict(
    selectedKo: String?,
    districts: List<PublicRouteDistrict>,
): String? = selectedKo?.takeIf { selected -> districts.any { it.nameKo == selected } }
