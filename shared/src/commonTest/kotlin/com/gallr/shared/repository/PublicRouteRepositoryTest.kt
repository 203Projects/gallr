package com.gallr.shared.repository

import com.gallr.shared.data.network.PersonalRouteApiException
import com.gallr.shared.data.network.PersonalRouteRemoteSource
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteListingState
import com.gallr.shared.route.RouteReportReason
import com.gallr.shared.route.routeFailure
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Spec 089 public routes: the repository passes listing, list, copy and report results through one boundary. */
class PublicRouteRepositoryTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val route = PersonalRoute(id = "p1", name = "한남 산책", stops = emptyList(), isPublished = true)
    private val summary =
        PersonalRouteSummary("r1", "동선", 2, true, false, now, listingState = RouteListingState.Requested)
    private val publicRow =
        PublicRouteSummary(
            id = "p1",
            name = "한남 산책",
            stopCount = 4,
            firstDistrictKo = "한남동",
            firstDistrictEn = "Hannam-dong",
            lastDistrictKo = "이태원동",
            lastDistrictEn = "Itaewon-dong",
            authorDisplayName = null,
            isEditor = true,
            copyCount30d = 3,
            firstSharedDay = LocalDate(2026, 10, 8),
            approvedAt = now,
        )

    @Test
    fun successesPassThrough() =
        runTest {
            val source = Source()
            val repository = PersonalRouteRepositoryImpl(source)

            assertEquals(summary, repository.requestListing("r1").getOrThrow())
            assertEquals(summary, repository.withdrawListing("r1").getOrThrow())
            assertEquals(listOf(publicRow), repository.listPublic().getOrThrow())
            assertEquals(
                PublicRouteStops(route, isMine = false, authorDisplayName = "owner-1"),
                repository.loadPublicStops("p1").getOrThrow(),
            )
            assertEquals(route, repository.copyPublic("p1").getOrThrow())
            assertEquals(Unit, repository.report("p1", RouteReportReason.Other).getOrThrow())
            assertEquals(10, source.requestedLimit, "the list asks for at most ten rows")
            assertEquals(RouteReportReason.Other, source.reportedReason)
        }

    @Test
    fun aRouteThatIsNotShownIsNull() =
        runTest {
            assertNull(PersonalRouteRepositoryImpl(Source(stops = null)).loadPublicStops("gone").getOrThrow())
        }

    @Test
    fun failuresKeepTheirMeaning() =
        runTest {
            val notListedError = PersonalRouteApiException(PersonalRouteFailure.NotListed)
            val notListed = PersonalRouteRepositoryImpl(Source(fail = notListedError))
            val offline = PersonalRouteRepositoryImpl(Source(fail = IOException("offline")))

            assertEquals(PersonalRouteFailure.NotListed, notListed.copyPublic("p1").failure())
            assertEquals(PersonalRouteFailure.Network, offline.listPublic().failure())
            assertEquals(PersonalRouteFailure.Network, offline.report("p1", RouteReportReason.Other).failure())
            assertEquals(PersonalRouteFailure.Network, offline.requestListing("r1").failure())
        }

    private fun Result<*>.failure(): PersonalRouteFailure? = exceptionOrNull()?.routeFailure()

    private inner class Source(
        private val stops: PublicRouteStops? = PublicRouteStops(route, isMine = false, authorDisplayName = "owner-1"),
        private val fail: Throwable? = null,
    ) : PersonalRouteRemoteSource {
        var requestedLimit: Int? = null
        var reportedReason: RouteReportReason? = null

        private fun check() {
            fail?.let { throw it }
        }

        override suspend fun save(route: PersonalRoute): PersonalRoute = error("not used here")

        override suspend fun publish(id: String): PersonalRoute = error("not used here")

        override suspend fun delete(id: String) = error("not used here")

        override suspend fun listMine(): List<PersonalRouteSummary> = error("not used here")

        override suspend fun loadMine(id: String): PersonalRoute = error("not used here")

        override suspend fun requestListing(id: String): PersonalRouteSummary = summary.also { check() }

        override suspend fun withdrawListing(id: String): PersonalRouteSummary = summary.also { check() }

        override suspend fun listPublic(limit: Int): List<PublicRouteSummary> {
            check()
            requestedLimit = limit
            return listOf(publicRow)
        }

        override suspend fun loadPublicStops(id: String): PublicRouteStops? = stops.also { check() }

        override suspend fun copyPublic(id: String): PersonalRoute = route.also { check() }

        override suspend fun report(
            id: String,
            reason: RouteReportReason,
        ) {
            check()
            reportedReason = reason
        }
    }
}
