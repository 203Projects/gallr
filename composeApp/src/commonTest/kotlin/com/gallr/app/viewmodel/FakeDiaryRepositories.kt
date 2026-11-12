package com.gallr.app.viewmodel

import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.CompletableDeferred
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

    /** When set, a save waits here, so a test can look at the state while it is still going. */
    var gate: CompletableDeferred<Unit>? = null

    override fun observeVisits(): Flow<List<ExhibitionVisit>> = visits

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        val known = this.visits.value.mapTo(mutableSetOf()) { it.exhibitionId }
        this.visits.value = this.visits.value + visits.filter { known.add(it.exhibitionId) }
    }

    override suspend fun removeVisit(exhibitionId: String) {
        visits.value = visits.value.filterNot { it.exhibitionId == exhibitionId }
    }

    override suspend fun saveEntry(visit: ExhibitionVisit) {
        gate?.await()
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

    /** Every publish, unpublish and acknowledgement in order, as `publish:<id>`, `unpublish:<id>`, `ack:<id>`. */
    val calls = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun publish(exhibitionId: String) {
        failure?.let { throw it }
        calls += "publish:$exhibitionId"
    }

    override suspend fun unpublish(exhibitionId: String) {
        failure?.let { throw it }
        calls += "unpublish:$exhibitionId"
    }

    override suspend fun acknowledgeApproval(exhibitionId: String) {
        failure?.let { throw it }
        calls += "ack:$exhibitionId"
        notes.value = notes.value.map { if (it.exhibitionId == exhibitionId) it.copy(approvalSeen = true) else it }
    }
}
