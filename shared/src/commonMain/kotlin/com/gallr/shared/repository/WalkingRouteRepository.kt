package com.gallr.shared.repository

import com.gallr.shared.map.RouteWaypoint
import com.gallr.shared.map.WalkingLegs
import kotlinx.coroutines.flow.StateFlow

/**
 * Walking paths between venues for the route screens (spec 093). [legs] grows as answers arrive; screens time their
 * routes with it through `RoutedLegEstimator`. Paths are worked out once by the service and kept here for the
 * session, so reopening a route asks for nothing.
 */
interface WalkingRouteRepository {
    val legs: StateFlow<WalkingLegs>

    /** Asks for every leg between consecutive venues in [stops] that is not known yet. Positions are never sent. */
    suspend fun resolve(stops: List<RouteWaypoint>): Result<Unit>
}
