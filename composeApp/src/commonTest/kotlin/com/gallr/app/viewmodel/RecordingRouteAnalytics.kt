package com.gallr.app.viewmodel

/** Captures the personal-route analytics calls in order, e.g. "published:3". */
class RecordingRouteAnalytics : RouteAnalytics {
    val events = mutableListOf<String>()

    override suspend fun draftStarted() {
        events += "draft_started"
    }

    override suspend fun published(stopCount: Int) {
        events += "published:$stopCount"
    }

    override suspend fun shared(stopCount: Int) {
        events += "shared:$stopCount"
    }

    override suspend fun publicRoutesViewed(rowsShown: Int) {
        events += "public_routes_viewed:$rowsShown"
    }
}
