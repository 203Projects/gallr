package com.gallr.shared.repository

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.network.WalkingLegResult
import com.gallr.shared.data.network.WalkingRouteSource
import com.gallr.shared.map.RouteWaypoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 093: walking legs are asked for once, kept for the session, and retried when they were unavailable. */
class WalkingRouteRepositoryTest {
    private val a = RouteWaypoint("a", GeoPoint(37.50, 127.00))
    private val b = RouteWaypoint("b", GeoPoint(37.51, 127.00))
    private val c = RouteWaypoint("c", GeoPoint(37.52, 127.00))

    @Test
    fun resolvedLegsAreKeptAndNotAskedForAgain() =
        runTest {
            val source = Source()
            val repository = WalkingRouteRepositoryImpl(source)

            assertTrue(repository.resolve(listOf(a, b, c)).isSuccess)
            assertTrue(repository.resolve(listOf(a, b, c)).isSuccess)

            assertEquals(listOf(listOf("a", "b", "c")), source.batches)
            assertEquals(1_000, repository.legs.value[a.point, b.point]?.distanceMeters)
            assertEquals(1_000, repository.legs.value[c.point, b.point]?.distanceMeters, "either direction")
        }

    @Test
    fun aChangedStopAsksOnlyForTheLegsItChanged() =
        runTest {
            val source = Source()
            val repository = WalkingRouteRepositoryImpl(source)
            repository.resolve(listOf(a, b))

            repository.resolve(listOf(a, b, c))

            assertEquals(listOf(listOf("a", "b"), listOf("b", "c")), source.batches)
        }

    @Test
    fun anUnavailableLegIsAskedForAgainNextTime() =
        runTest {
            val source = Source(unavailable = setOf("b" to "c"))
            val repository = WalkingRouteRepositoryImpl(source)

            repository.resolve(listOf(a, b, c))
            assertNull(repository.legs.value[b.point, c.point])

            source.unavailable = emptySet()
            repository.resolve(listOf(a, b, c))

            assertNotNull(repository.legs.value[b.point, c.point])
            assertEquals(listOf(listOf("a", "b", "c"), listOf("b", "c")), source.batches)
        }

    @Test
    fun aFailedRequestIsAFailureAndKeepsWhatWasKnown() =
        runTest {
            val source = Source()
            val repository = WalkingRouteRepositoryImpl(source)
            repository.resolve(listOf(a, b))
            source.failure = IOException("offline")

            val result = repository.resolve(listOf(a, b, c))

            assertTrue(result.isFailure)
            assertNotNull(repository.legs.value[a.point, b.point])
        }

    @Test
    fun legsArePublishedAsTheyArrive() =
        runTest {
            val source = Source()
            val repository = WalkingRouteRepositoryImpl(source)
            val seen = mutableListOf<Int>()
            val watching = launch { repository.legs.collect { seen += it.size } }
            testScheduler.runCurrent()

            repository.resolve(listOf(a, b, c))
            testScheduler.runCurrent()
            watching.cancel()

            assertEquals(listOf(0, 2), seen)
        }

    private class Source(
        var unavailable: Set<Pair<String, String>> = emptySet(),
        var failure: Throwable? = null,
    ) : WalkingRouteSource {
        val batches = mutableListOf<List<String>>()

        override suspend fun legs(waypoints: List<RouteWaypoint>): List<WalkingLegResult> {
            failure?.let { throw it }
            batches += waypoints.map { it.exhibitionId.orEmpty() }
            return waypoints.zipWithNext { from, to ->
                if ((from.exhibitionId to to.exhibitionId) in unavailable) {
                    WalkingLegResult.Unavailable
                } else {
                    WalkingLegResult.Routed(1_000, listOf(from.point, to.point))
                }
            }
        }
    }
}
