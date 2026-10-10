package com.gallr.shared.repository

import com.gallr.shared.data.network.PersonalRouteApiException
import com.gallr.shared.data.network.PersonalRouteRemoteSource
import com.gallr.shared.observability.AppLog
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteException
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

private val routeLog = AppLog.tagged("PersonalRouteRepository")

/** [PersonalRouteRepository] over the route functions, with one failure boundary for every call. */
class PersonalRouteRepositoryImpl(
    private val source: PersonalRouteRemoteSource,
) : PersonalRouteRepository {
    override suspend fun save(route: PersonalRoute): Result<PersonalRoute> = call("route_save") { source.save(route) }

    override suspend fun publish(id: String): Result<PersonalRoute> = call("route_publish") { source.publish(id) }

    override suspend fun listMine(): Result<List<PersonalRouteSummary>> = call("route_list_mine") { source.listMine() }

    override suspend fun loadMine(id: String): Result<PersonalRoute> = call("route_load_mine") { source.loadMine(id) }

    override suspend fun delete(id: String): Result<Unit> = call("route_delete") { source.delete(id) }

    override suspend fun requestListing(id: String): Result<PersonalRouteSummary> =
        call("route_listing_request") { source.requestListing(id) }

    override suspend fun withdrawListing(id: String): Result<PersonalRouteSummary> =
        call("route_listing_withdraw") { source.withdrawListing(id) }

    override suspend fun listPublic(limit: Int): Result<List<PublicRouteSummary>> =
        call("public_routes_list") { source.listPublic(limit) }

    override suspend fun loadPublicStops(id: String): Result<PublicRouteStops?> =
        call("public_route_load") { source.loadPublicStops(id) }

    override suspend fun copyPublic(id: String): Result<PersonalRoute> =
        call("public_route_copy") { source.copyPublic(id) }

    override suspend fun report(
        id: String,
        reason: RouteReportReason,
    ): Result<Unit> = call("public_route_report") { source.report(id, reason) }

    private suspend fun <T> call(
        operation: String,
        block: suspend () -> T,
    ): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val failure = error.toFailure()
            if (failure == PersonalRouteFailure.Network || failure == PersonalRouteFailure.Unexpected) {
                routeLog.warn(operation, error)
            }
            Result.failure(PersonalRouteException(failure))
        }

    private fun Throwable.toFailure(): PersonalRouteFailure =
        when (this) {
            is PersonalRouteApiException -> failure
            is HttpRequestTimeoutException, is IOException -> PersonalRouteFailure.Network
            else -> PersonalRouteFailure.Unexpected
        }
}
