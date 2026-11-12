package com.gallr.app.viewmodel

import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A diary store in memory: one entry per exhibition, and a switch that makes the next writes fail. */
class FakeDiaryVisitRepository(
    initial: List<ExhibitionVisit> = emptyList(),
) : VisitRepository {
    val visits = MutableStateFlow(initial)
    val saved = mutableListOf<ExhibitionVisit>()
    val deleted = mutableListOf<String>()
    var failure: Exception? = null

    override fun observeVisits(): Flow<List<ExhibitionVisit>> = visits

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        val known = this.visits.value.mapTo(mutableSetOf()) { it.exhibitionId }
        this.visits.value = this.visits.value + visits.filter { known.add(it.exhibitionId) }
    }

    override suspend fun removeVisit(exhibitionId: String) {
        visits.value = visits.value.filterNot { it.exhibitionId == exhibitionId }
    }

    override suspend fun saveEntry(visit: ExhibitionVisit) {
        failure?.let { throw it }
        saved += visit
        visits.value = visits.value.filterNot { it.exhibitionId == visit.exhibitionId } + visit
    }

    override suspend fun deleteEntry(exhibitionId: String) {
        failure?.let { throw it }
        deleted += exhibitionId
        removeVisit(exhibitionId)
    }
}

/** The signed-in author's own 감상, as a test sets them. */
class FakePublicNoteRepository(
    initial: List<OwnPublicNote> = emptyList(),
) : PublicNoteRepository {
    val notes = MutableStateFlow(initial)
    val pending = MutableStateFlow<Map<String, PendingNoteMutation>>(emptyMap())

    override fun observeOwnNotes(): Flow<List<OwnPublicNote>> = notes

    override fun observePendingNoteMutations(): Flow<Map<String, PendingNoteMutation>> = pending

    override suspend fun publish(exhibitionId: String) = Unit

    override suspend fun unpublish(exhibitionId: String) = Unit

    override suspend fun acknowledgeApproval(exhibitionId: String) = Unit
}
