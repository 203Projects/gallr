package com.gallr.shared.repository

import com.gallr.shared.data.model.ExhibitionVisit
import kotlinx.coroutines.flow.Flow

/** Private exhibition-visit archive: the diary. Implementations must keep one record per exhibition. */
interface VisitRepository {
    fun observeVisits(): Flow<List<ExhibitionVisit>>

    /** Adds visits for exhibitions that have none; an existing entry is left as it is. */
    suspend fun addVisits(visits: List<ExhibitionVisit>)

    /** Removes the visit only; for an account, a 감상 on the exhibition stays. */
    suspend fun removeVisit(exhibitionId: String)

    /** Creates or replaces the entry; a guest writes the device store, an account queues the update. */
    suspend fun saveEntry(visit: ExhibitionVisit)

    /** Removes the entry and, for an account, its public 감상. */
    suspend fun deleteEntry(exhibitionId: String)
}
