package com.gallr.shared.repository

import com.gallr.shared.route.RouteVisitTime
import kotlinx.coroutines.flow.Flow

/** The visit time chosen for one route draft (spec 096). */
data class StoredVisitTime(
    val draftId: String,
    val visitTime: RouteVisitTime,
)

/**
 * The composer's chosen visit time, kept on the device and outside the draft, so choosing a time never changes the
 * draft's revision or reaches the server (spec 096 FR-007, FR-008). One value: a new choice replaces any other.
 */
interface RouteVisitTimeRepository {
    val visitTime: Flow<StoredVisitTime?>

    /** Stores [visitTime] for [draftId]; null is "지금" and clears the stored choice. */
    suspend fun set(
        draftId: String,
        visitTime: RouteVisitTime?,
    )
}
