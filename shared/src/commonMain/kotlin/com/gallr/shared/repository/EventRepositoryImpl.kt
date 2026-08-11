package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.EventApi
import com.gallr.shared.util.runSuspendCatching
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

class EventRepositoryImpl(
    private val api: EventApi,
    private val nowProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
    },
) : EventRepository {
    override suspend fun getActiveEvents(): Result<List<Event>> =
        runSuspendCatching {
            val today = nowProvider()
            api
                .fetchEvents()
                .filter { it.isVisibleOn(today) }
                .sortedBy { it.startDate }
        }

    override suspend fun getEventById(id: String): Result<Event?> = runSuspendCatching { api.fetchEventById(id) }

    override suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>> =
        runSuspendCatching { api.fetchExhibitionsForEvent(id) }
}
