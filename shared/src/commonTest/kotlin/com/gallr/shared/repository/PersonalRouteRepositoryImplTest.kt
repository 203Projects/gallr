package com.gallr.shared.repository

import com.gallr.shared.data.network.PersonalRouteApiException
import com.gallr.shared.data.network.PersonalRouteRemoteSource
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason
import com.gallr.shared.route.routeFailure
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** Spec 089: the route repository turns every failure into a typed [PersonalRouteFailure]. */
class PersonalRouteRepositoryImplTest {
    private val route = PersonalRoute(id = "route-1", name = "동선", stops = emptyList())

    @Test
    fun successesPassThrough() =
        runTest {
            val summary = PersonalRouteSummary("route-1", "동선", 2, false, false, Instant.parse("2026-10-08T00:00:00Z"))
            val repository = PersonalRouteRepositoryImpl(FakeSource(result = { route }, summaries = listOf(summary)))

            assertEquals(route, repository.save(route).getOrThrow())
            assertEquals(route, repository.publish("route-1").getOrThrow())
            assertEquals(route, repository.loadMine("route-1").getOrThrow())
            assertEquals(listOf(summary), repository.listMine().getOrThrow())
            assertEquals(Unit, repository.delete("route-1").getOrThrow())
        }

    @Test
    fun serverFailuresKeepTheirMeaning() =
        runTest {
            val repository =
                PersonalRouteRepositoryImpl(
                    FakeSource(result = { throw PersonalRouteApiException(PersonalRouteFailure.Revoked) }),
                )

            assertEquals(PersonalRouteFailure.Revoked, repository.save(route).exceptionOrNull()?.routeFailure())
        }

    @Test
    fun transportFailuresBecomeNetworkAndBadBodiesUnexpected() =
        runTest {
            suspend fun failureOf(error: Throwable) =
                PersonalRouteRepositoryImpl(FakeSource(result = { throw error }))
                    .save(route)
                    .exceptionOrNull()
                    ?.routeFailure()

            assertEquals(PersonalRouteFailure.Network, failureOf(IOException("offline")))
            assertEquals(PersonalRouteFailure.Network, failureOf(HttpRequestTimeoutException("url", 10_000)))
            assertEquals(PersonalRouteFailure.Unexpected, failureOf(SerializationException("bad body")))
            assertEquals(PersonalRouteFailure.Unexpected, failureOf(IllegalStateException("other")))
        }

    @Test
    fun cancellationIsNotSwallowed() =
        runTest {
            val cancelling = FakeSource(result = { throw CancellationException("cancelled") })
            val repository = PersonalRouteRepositoryImpl(cancelling)

            assertFailsWith<CancellationException> { repository.save(route) }
        }

    private class FakeSource(
        private val result: () -> PersonalRoute,
        private val summaries: List<PersonalRouteSummary> = emptyList(),
    ) : PersonalRouteRemoteSource {
        override suspend fun save(route: PersonalRoute) = result()

        override suspend fun publish(id: String) = result()

        override suspend fun loadMine(id: String) = result()

        override suspend fun listMine(): List<PersonalRouteSummary> {
            result()
            return summaries
        }

        override suspend fun delete(id: String) {
            result()
        }

        override suspend fun requestListing(id: String): PersonalRouteSummary = error("not used here")

        override suspend fun withdrawListing(id: String): PersonalRouteSummary = error("not used here")

        override suspend fun listPublic(limit: Int): List<PublicRouteSummary> = error("not used here")

        override suspend fun loadPublicStops(id: String): PublicRouteStops? = error("not used here")

        override suspend fun copyPublic(id: String): PersonalRoute = error("not used here")

        override suspend fun report(
            id: String,
            reason: RouteReportReason,
        ) = error("not used here")
    }
}
