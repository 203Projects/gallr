package com.gallr.shared.repository

import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason

/**
 * The signed-in author's routes on the server (spec 089). Every failure is returned as a [Result] whose error
 * is a [com.gallr.shared.route.PersonalRouteException]; read it with `routeFailure()`.
 */
interface PersonalRouteRepository {
    /** Saves the name and the ordered stops; the server copies stop details from the catalogue. */
    suspend fun save(route: PersonalRoute): Result<PersonalRoute>

    /** Makes the route readable by anyone with its link; publishing again is harmless. */
    suspend fun publish(id: String): Result<PersonalRoute>

    suspend fun listMine(): Result<List<PersonalRouteSummary>>

    suspend fun loadMine(id: String): Result<PersonalRoute>

    suspend fun delete(id: String): Result<Unit>

    /** Asks for public listing of a published route; the row comes back with its new listing state. */
    suspend fun requestListing(id: String): Result<PersonalRouteSummary>

    /** Withdraws a listing request or takes an approved route off the list. */
    suspend fun withdrawListing(id: String): Result<PersonalRouteSummary>

    /** The ranked public list (추천 동선), at most [limit] rows; works without an account. */
    suspend fun listPublic(limit: Int = PUBLIC_ROUTE_LIMIT): Result<List<PublicRouteSummary>>

    /** A listed route's stops and owner for the preview, or null when it is no longer shown; works without an account. */
    suspend fun loadPublicStops(id: String): Result<PublicRouteStops?>

    /** Counts this account's copy of a listed route and returns its stops to put in the draft. */
    suspend fun copyPublic(id: String): Result<PersonalRoute>

    suspend fun report(
        id: String,
        reason: RouteReportReason,
    ): Result<Unit>

    companion object {
        /** The public list never holds more than ten routes (spec 089 FR-049). */
        const val PUBLIC_ROUTE_LIMIT = 10
    }
}
