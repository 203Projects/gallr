package com.gallr.shared.repository

import com.gallr.shared.data.network.WalkingLegResult
import com.gallr.shared.data.network.WalkingRouteSource
import com.gallr.shared.map.RouteWaypoint
import com.gallr.shared.map.WalkingLeg
import com.gallr.shared.map.WalkingLegs
import com.gallr.shared.map.legsToRequest
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val walkingLog = AppLog.tagged("WalkingRouteRepository")

/**
 * [WalkingRouteRepository] over the `walking-route` service. Requests run one at a time, so two screens asking for
 * the same route make one request; an unavailable leg is not kept, so the next resolve asks for it again (US3).
 */
class WalkingRouteRepositoryImpl(
    private val source: WalkingRouteSource,
) : WalkingRouteRepository {
    private val known = MutableStateFlow(WalkingLegs.EMPTY)
    private val requests = Mutex()

    override val legs: StateFlow<WalkingLegs> = known.asStateFlow()

    override suspend fun resolve(stops: List<RouteWaypoint>): Result<Unit> =
        requests.withLock {
            var failure: Throwable? = null
            for (batch in legsToRequest(stops, known.value)) {
                try {
                    store(batch, source.legs(batch))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    walkingLog.warn("walking_route_resolve_failed", error)
                    failure = error
                }
            }
            failure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

    private fun store(
        batch: List<RouteWaypoint>,
        results: List<WalkingLegResult>,
    ) {
        known.update { current ->
            batch.zipWithNext().zip(results).fold(current) { legs, (pair, result) ->
                if (result is WalkingLegResult.Routed) {
                    legs.plus(pair.first.point, pair.second.point, WalkingLeg(result.distanceMeters, result.geometry))
                } else {
                    legs
                }
            }
        }
    }
}
