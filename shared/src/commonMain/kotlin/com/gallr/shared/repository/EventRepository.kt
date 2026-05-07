package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition

interface EventRepository {
    suspend fun getActiveEvents(): Result<List<Event>>
    suspend fun getEventById(id: String): Result<Event?>
    suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>>
}
