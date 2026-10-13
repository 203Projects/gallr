package com.gallr.shared.route

import com.gallr.shared.fixture.DiscoveryFixture
import com.gallr.shared.map.NeighborhoodRoutePlanner
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RoutePlanResult
import com.gallr.shared.map.RoutePlanningRequest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 089 (E-D3): a route copied from the planner shows the same times in the composer. */
class PlannerEvaluatorParityTest {
    private val seoul = TimeZone.of("Asia/Seoul")

    @Test
    fun aPlannerRouteAndTheSameStopsEvaluatedInOrderHaveEqualSchedules() {
        listOf(LocalTime(9, 30), LocalTime(11, 0), LocalTime(14, 0)).forEach { start ->
            val request =
                RoutePlanningRequest(
                    origin = DiscoveryFixture.SAMCHEONG,
                    visitDate = DiscoveryFixture.referenceDate,
                    mode = RouteCurationMode.NEIGHBORHOOD,
                    stopCount = 3,
                    maxRadiusKm = 3.0,
                    startTime = start,
                )
            val result = NeighborhoodRoutePlanner().plan(DiscoveryFixture.exhibitions, emptySet(), request)
            val planned = (result as RoutePlanResult.Success).route

            val evaluation =
                requireNotNull(
                    RouteEvaluator.evaluate(
                        stops = planned.stops.map { requireNotNull(it.toRouteStop()) },
                        exhibitionsById = DiscoveryFixture.exhibitions.associateBy { it.id },
                        origin = request.origin,
                        now = LocalDateTime(request.visitDate, start).toInstant(seoul),
                        zone = seoul,
                    ),
                )

            assertEquals(request.visitDate, evaluation.plannedDay, "start $start")
            assertEquals(planned.stopSchedules.map { it.arrival }, evaluation.stops.map { it.arrival }, "start $start")
            val plannedVisits = planned.stopSchedules.map { it.visitStart to it.visitEnd }
            val evaluatedVisits = evaluation.stops.map { it.visitStart to it.visitEnd }
            assertEquals(plannedVisits, evaluatedVisits, "start $start")
            assertEquals(planned.estimatedTravelMinutes, evaluation.travelMinutes, "start $start")
            assertEquals(planned.estimatedWaitMinutes, evaluation.waitMinutes, "start $start")
        }
    }
}
