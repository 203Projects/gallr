package com.gallr.app.viewmodel

import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteException
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteListingState
import com.gallr.shared.route.RouteReportReason
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

/** Records calls and answers like the server: saves keep `is_published`, publishing sets it (spec 089). */
class FakePersonalRouteRepository(
    private val savedAt: Instant = Instant.parse("2026-10-08T04:00:00Z"),
) : PersonalRouteRepository {
    val saved = mutableListOf<PersonalRoute>()
    val published = mutableListOf<String>()
    val loaded = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    /** Rows returned by [listMine]; a failure makes the next listing fail. */
    var summaries: List<PersonalRouteSummary> = emptyList()
    var listFailure: PersonalRouteFailure? = null
    var listMineCalls = 0
    var deleteFailure: PersonalRouteFailure? = null
    private val publishedIds = mutableSetOf<String>()

    /** Failures returned by the next save calls, in order; empty means success. */
    val saveFailures = ArrayDeque<PersonalRouteFailure>()
    var publishFailure: PersonalRouteFailure? = null

    fun markAlreadyPublished(id: String) {
        publishedIds += id
    }

    override suspend fun save(route: PersonalRoute): Result<PersonalRoute> {
        saved += route
        saveFailures.removeFirstOrNull()?.let { return Result.failure(PersonalRouteException(it)) }
        val stored = route.copy(name = route.trimmedName, isPublished = route.id in publishedIds, revision = savedAt)
        return Result.success(stored)
    }

    override suspend fun publish(id: String): Result<PersonalRoute> {
        published += id
        publishFailure?.let { return Result.failure(PersonalRouteException(it)) }
        publishedIds += id
        summaries = summaries.map { if (it.id == id) it.copy(isPublished = true) else it }
        val route = saved.last { it.id == id }
        return Result.success(route.copy(name = route.trimmedName, isPublished = true, revision = savedAt))
    }

    override suspend fun listMine(): Result<List<PersonalRouteSummary>> {
        listMineCalls += 1
        listFailure?.let { return Result.failure(PersonalRouteException(it)) }
        return Result.success(summaries)
    }

    override suspend fun loadMine(id: String): Result<PersonalRoute> {
        loaded += id
        val route =
            saved.lastOrNull { it.id == id }
                ?: return Result.failure(PersonalRouteException(PersonalRouteFailure.NotFound))
        val current = route.copy(name = route.trimmedName, isPublished = id in publishedIds, revision = savedAt)
        return Result.success(current)
    }

    override suspend fun delete(id: String): Result<Unit> {
        deleteFailure?.let { return Result.failure(PersonalRouteException(it)) }
        deleted += id
        summaries = summaries.filterNot { it.id == id }
        return Result.success(Unit)
    }

    // Public routes (spec 089 US7–US10)

    val listingRequests = mutableListOf<String>()
    val listingWithdrawals = mutableListOf<String>()

    /** Failures returned by the next listing calls, in order; empty means success. */
    val listingFailures = ArrayDeque<PersonalRouteFailure>()

    /** True when the account requesting is an active editor, so requests are approved at once. */
    var authorIsEditor = false

    override suspend fun requestListing(id: String): Result<PersonalRouteSummary> {
        listingRequests += id
        listingFailures.removeFirstOrNull()?.let { return Result.failure(PersonalRouteException(it)) }
        val state = if (authorIsEditor) RouteListingState.Approved else RouteListingState.Requested
        return Result.success(updateListing(id, state))
    }

    override suspend fun withdrawListing(id: String): Result<PersonalRouteSummary> {
        listingWithdrawals += id
        listingFailures.removeFirstOrNull()?.let { return Result.failure(PersonalRouteException(it)) }
        return Result.success(updateListing(id, RouteListingState.Unlisted))
    }

    private fun updateListing(
        id: String,
        state: RouteListingState,
    ): PersonalRouteSummary {
        val row =
            summaries.firstOrNull { it.id == id }
                ?: PersonalRouteSummary(id, "동선 $id", 2, true, false, savedAt)
        val updated =
            row.copy(
                listingState = state,
                declineReason = null,
                declineNote = null,
                authorIsEditor = authorIsEditor,
            )
        summaries = summaries.map { if (it.id == id) updated else it }
        return updated
    }

    /** Rows returned by [listPublic]; [publicListFailures] fail the next reads in order. */
    var publicRoutes: List<PublicRouteSummary> = emptyList()
    val publicListFailures = ArrayDeque<PersonalRouteFailure>()
    var publicListCalls = 0

    override suspend fun listPublic(limit: Int): Result<List<PublicRouteSummary>> {
        publicListCalls += 1
        publicListFailures.removeFirstOrNull()?.let { return Result.failure(PersonalRouteException(it)) }
        return Result.success(publicRoutes.take(limit))
    }

    /** Stops for each listed route id; a missing entry reads as no longer shown. */
    val publicStops = mutableMapOf<String, PersonalRoute>()

    /** Ids of listed routes the server reports as the reader's own. */
    val publicMine = mutableSetOf<String>()

    /** Route ids whose stops were read, in order. */
    val publicStopsReads = mutableListOf<String>()
    val copied = mutableListOf<String>()
    val copyFailures = ArrayDeque<PersonalRouteFailure>()

    /** When set, copies wait for it after being recorded, so a test can act while a copy runs. */
    var copyGate: CompletableDeferred<Unit>? = null

    override suspend fun loadPublicStops(id: String): Result<PublicRouteStops?> {
        publicStopsReads += id
        return Result.success(
            publicStops[id]?.let { PublicRouteStops(it, isMine = id in publicMine, authorDisplayName = "작가") },
        )
    }

    override suspend fun copyPublic(id: String): Result<PersonalRoute> {
        copied += id
        copyGate?.await()
        copyFailures.removeFirstOrNull()?.let { return Result.failure(PersonalRouteException(it)) }
        val route = publicStops[id] ?: return Result.failure(PersonalRouteException(PersonalRouteFailure.NotListed))
        return Result.success(route)
    }

    val reports = mutableListOf<Pair<String, RouteReportReason>>()
    val reportFailures = ArrayDeque<PersonalRouteFailure>()

    override suspend fun report(
        id: String,
        reason: RouteReportReason,
    ): Result<Unit> {
        reportFailures.removeFirstOrNull()?.let { return Result.failure(PersonalRouteException(it)) }
        reports += id to reason
        return Result.success(Unit)
    }
}
