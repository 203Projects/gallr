package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.EventApi
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

class EventRepositoryImpl(
    private val api: EventApi,
    private val nowProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
    },
) : EventRepository {

    override suspend fun getActiveEvents(): Result<List<Event>> = runCatching {
        val today = nowProvider()
        api.fetchEvents()
            .filter { it.isActiveOn(today) }
            .sortedBy { it.startDate }
    }

    override suspend fun getEventById(id: String): Result<Event?> =
        runCatching { api.fetchEventById(id) }

    override suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>> =
        runCatching { api.fetchExhibitionsForEvent(id) }
}
