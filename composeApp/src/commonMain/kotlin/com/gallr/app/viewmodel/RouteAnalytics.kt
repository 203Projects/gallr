package com.gallr.app.viewmodel

/**
 * The personal-route author loop (spec 089 E-D6) and 추천 동선 views (US11, P12): counts only, with no route id,
 * author, reader or content. Recorded through the existing aggregate pipeline, which stays inactive until its
 * separate activation.
 */
interface RouteAnalytics {
    /** An empty draft got its first stop, or a new draft was started from a planner route. */
    suspend fun draftStarted()

    /** A route became public for the first time. */
    suspend fun published(stopCount: Int)

    /** The share sheet opened for a route. */
    suspend fun shared(stopCount: Int)

    /** 추천 동선 was shown with [rowsShown] rows on screen. */
    suspend fun publicRoutesViewed(rowsShown: Int)

    companion object {
        val None: RouteAnalytics =
            object : RouteAnalytics {
                override suspend fun draftStarted() = Unit

                override suspend fun published(stopCount: Int) = Unit

                override suspend fun shared(stopCount: Int) = Unit

                override suspend fun publicRoutesViewed(rowsShown: Int) = Unit
            }
    }
}
