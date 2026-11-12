package com.gallr.shared.repository

import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The signed-in author's own 감상 and the actions on them (spec 103 US3). A publish rides the account outbox behind
 * the entry it copies, so it works offline and while a session refresh is pending. Signed-in only: the actions throw
 * without an active account. Callers turn failures into UI state and logs.
 */
interface PublicNoteRepository {
    /** The account's own 감상 as the archive holds them; empty without an active account. */
    fun observeOwnNotes(): Flow<List<OwnPublicNote>>

    /** The latest queued publish or unpublish per exhibition, until the server takes it. */
    fun observePendingNoteMutations(): Flow<Map<String, PendingNoteMutation>>

    /** Asks for review of the entry's current note as the exhibition's 감상. */
    suspend fun publish(exhibitionId: String)

    /** Withdraws the 감상 and keeps the private note. */
    suspend fun unpublish(exhibitionId: String)

    /** Records that 감상이 공개됐어요 was shown. */
    suspend fun acknowledgeApproval(exhibitionId: String)
}

class PublicNoteRepositoryImpl(
    private val coordinator: MyGallrAccountSyncCoordinator,
) : PublicNoteRepository {
    override fun observeOwnNotes(): Flow<List<OwnPublicNote>> =
        coordinator.observeActiveArchive().map { it?.publicNotes.orEmpty() }

    override fun observePendingNoteMutations(): Flow<Map<String, PendingNoteMutation>> =
        coordinator.observePendingNoteMutations()

    override suspend fun publish(exhibitionId: String) = coordinator.publishNote(exhibitionId)

    override suspend fun unpublish(exhibitionId: String) = coordinator.unpublishNote(exhibitionId)

    override suspend fun acknowledgeApproval(exhibitionId: String) = coordinator.acknowledgeNoteApproval(exhibitionId)
}
