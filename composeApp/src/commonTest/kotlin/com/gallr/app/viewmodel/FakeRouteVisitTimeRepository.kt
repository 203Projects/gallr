package com.gallr.app.viewmodel

import com.gallr.shared.repository.RouteVisitTimeRepository
import com.gallr.shared.repository.StoredVisitTime
import com.gallr.shared.route.RouteVisitTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the composer's chosen visit time in memory (spec 096). */
class FakeRouteVisitTimeRepository(
    initial: StoredVisitTime? = null,
) : RouteVisitTimeRepository {
    private val stored = MutableStateFlow(initial)

    override val visitTime: StateFlow<StoredVisitTime?> = stored

    override suspend fun set(
        draftId: String,
        visitTime: RouteVisitTime?,
    ) {
        stored.value = visitTime?.let { StoredVisitTime(draftId, it) }
    }
}
