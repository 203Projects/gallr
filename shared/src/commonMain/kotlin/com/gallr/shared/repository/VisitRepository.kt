package com.gallr.shared.repository

import com.gallr.shared.data.model.ExhibitionVisit
import kotlinx.coroutines.flow.Flow

/** Private exhibition-visit archive. Implementations must keep one record per exhibition. */
interface VisitRepository {
    fun observeVisits(): Flow<List<ExhibitionVisit>>

    suspend fun addVisits(visits: List<ExhibitionVisit>)

    suspend fun removeVisit(exhibitionId: String)
}
