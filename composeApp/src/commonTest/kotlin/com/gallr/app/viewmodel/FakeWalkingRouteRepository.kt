package com.gallr.app.viewmodel

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteWaypoint
import com.gallr.shared.map.WalkingLeg
import com.gallr.shared.map.WalkingLegs
import com.gallr.shared.repository.WalkingRouteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Records what the route screens ask for; a test publishes walking legs as if the service had answered (spec 093). */
class FakeWalkingRouteRepository : WalkingRouteRepository {
    private val known = MutableStateFlow(WalkingLegs.EMPTY)
    override val legs: StateFlow<WalkingLegs> = known.asStateFlow()

    /** Each resolve, as the waypoints it was given. */
    val resolved = mutableListOf<List<RouteWaypoint>>()
    var failure: Throwable? = null

    /** Each resolve as its exhibition ids. */
    val resolvedIds: List<List<String?>> get() = resolved.map { batch -> batch.map(RouteWaypoint::exhibitionId) }

    override suspend fun resolve(stops: List<RouteWaypoint>): Result<Unit> {
        resolved += stops
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    fun publish(
        from: GeoPoint,
        to: GeoPoint,
        distanceMeters: Int,
    ) {
        known.update { it.plus(from, to, WalkingLeg(distanceMeters, listOf(from, to))) }
    }
}
