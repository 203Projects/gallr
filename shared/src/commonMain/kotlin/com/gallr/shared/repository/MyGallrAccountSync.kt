@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.MyGallrAccountArchive
import com.gallr.shared.data.model.MyGallrAccountMutation
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.diary.DIARY_NOTE_MAX_LENGTH
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.mergeEntry
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

private val MY_GALLR_ACCOUNT_STATE_KEY = stringPreferencesKey("my_gallr_account_state_v1")
private const val MY_GALLR_ACCOUNT_STATE_SCHEMA_VERSION = 1
private val myGallrAccountSyncLog = AppLog.tagged("MyGallrAccountSync")

// Stored outbox kinds of spec 103; persisted in `my_gallr_account_state_v1`, so never rename one.
private const val UPDATE_VISIT_KIND = "update_visit"
private const val MERGE_VISIT_KIND = "merge_visit"
private const val DELETE_ENTRY_KIND = "delete_entry"
private const val PUBLISH_NOTE_KIND = "publish_note"
private const val UNPUBLISH_NOTE_KIND = "unpublish_note"
private const val ACKNOWLEDGE_NOTE_APPROVAL_KIND = "acknowledge_note_approval"

enum class MyGallrSyncStatus {
    DEVICE_ONLY,
    SYNCING,
    SYNCED,
    RETRY_NEEDED,
}

@Serializable
data class StoredMyGallrAccountState(
    val revision: Long = 0,
    val visits: List<ExhibitionVisit> = emptyList(),
    val followedGalleries: List<FollowedGallery> = emptyList(),
    val pendingMutations: List<StoredMyGallrMutation> = emptyList(),
    val publicNotes: List<OwnPublicNote> = emptyList(),
) {
    fun archive() = MyGallrAccountArchive(revision, visits, followedGalleries, publicNotes)
}

@Serializable
data class StoredMyGallrMutation(
    val mutationId: String,
    val kind: String,
    val visit: ExhibitionVisit? = null,
    val exhibitionId: String? = null,
    val gallery: FollowedGallery? = null,
    val galleryKey: String? = null,
    val knownExhibitionIds: Set<String> = emptySet(),
)

@Serializable
private data class StoredMyGallrAccountsPayload(
    val schemaVersion: Int = MY_GALLR_ACCOUNT_STATE_SCHEMA_VERSION,
    val accounts: Map<String, StoredMyGallrAccountState> = emptyMap(),
)

interface MyGallrAccountStore {
    fun observe(userId: String): Flow<StoredMyGallrAccountState>

    suspend fun get(userId: String): StoredMyGallrAccountState

    suspend fun update(
        userId: String,
        transform: (StoredMyGallrAccountState) -> StoredMyGallrAccountState,
    )
}

class DataStoreMyGallrAccountStore(
    private val dataStore: DataStore<Preferences>,
) : MyGallrAccountStore {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override fun observe(userId: String): Flow<StoredMyGallrAccountState> =
        dataStore.data.map { preferences ->
            decode(preferences[MY_GALLR_ACCOUNT_STATE_KEY]).accounts[userId]
                ?: StoredMyGallrAccountState()
        }

    override suspend fun get(userId: String): StoredMyGallrAccountState = observe(userId).first()

    override suspend fun update(
        userId: String,
        transform: (StoredMyGallrAccountState) -> StoredMyGallrAccountState,
    ) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        dataStore.edit { preferences ->
            val payload = decode(preferences[MY_GALLR_ACCOUNT_STATE_KEY])
            val current = payload.accounts[userId] ?: StoredMyGallrAccountState()
            preferences[MY_GALLR_ACCOUNT_STATE_KEY] =
                json.encodeToString(payload.copy(accounts = payload.accounts + (userId to transform(current))))
        }
    }

    private fun decode(encoded: String?): StoredMyGallrAccountsPayload {
        if (encoded == null) return StoredMyGallrAccountsPayload()
        val payload = json.decodeFromString<StoredMyGallrAccountsPayload>(encoded)
        require(payload.schemaVersion == MY_GALLR_ACCOUNT_STATE_SCHEMA_VERSION) {
            "Unsupported My Gallr account state schema"
        }
        return payload
    }
}

class MyGallrAccountSyncCoordinator(
    private val guestVisitRepository: VisitRepository,
    private val guestFollowedGalleryRepository: FollowedGalleryRepository,
    private val accountStore: MyGallrAccountStore,
    private val source: MyGallrAccountCommandSource,
    private val generateMutationId: () -> String = ::randomMyGallrMutationId,
) {
    private val activeUserId = MutableStateFlow<String?>(null)
    private val status = MutableStateFlow(MyGallrSyncStatus.DEVICE_ONLY)
    private val syncMutex = Mutex()

    fun observeStatus(): Flow<MyGallrSyncStatus> = status.asStateFlow()

    private val arrived = MutableStateFlow<MyGallrAccountArchive?>(null)

    /**
     * The active account's archive as the server last sent it, less the visits and follows this device's own changes
     * in that sync put there; null until one arrives. It is published before the device's copy changes, so what the
     * account brings is known before any list shows it (spec 101 US3: the account's history never lifts a hide).
     */
    val arrivedArchive: StateFlow<MyGallrAccountArchive?> = arrived.asStateFlow()

    fun observeActiveArchive(): Flow<MyGallrAccountArchive?> =
        activeUserId.flatMapLatest { userId ->
            if (userId == null) flowOf(null) else accountStore.observe(userId).map(StoredMyGallrAccountState::archive)
        }

    fun hasActiveAccount(): Boolean = activeUserId.value != null

    suspend fun activateAccount(userId: String) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        if (activeUserId.value != userId) arrived.value = null
        activeUserId.value = userId
        mergeGuestRecords(userId)
        flush(userId)
    }

    fun deactivateAccount() {
        activeUserId.value = null
        arrived.value = null
        status.value = MyGallrSyncStatus.DEVICE_ONLY
    }

    suspend fun refresh() {
        val userId = activeUserId.value ?: return
        flush(userId)
    }

    suspend fun addVisits(visits: List<ExhibitionVisit>) {
        if (visits.isEmpty()) return
        mutate(visits.map { MyGallrAccountMutation.AddVisit(generateMutationId(), it) })
    }

    suspend fun removeVisit(exhibitionId: String) {
        mutate(listOf(MyGallrAccountMutation.RemoveVisit(generateMutationId(), exhibitionId)))
    }

    suspend fun saveEntry(visit: ExhibitionVisit) {
        mutate(listOf(MyGallrAccountMutation.UpdateVisit(generateMutationId(), visit)))
    }

    suspend fun deleteEntry(exhibitionId: String) {
        mutate(listOf(MyGallrAccountMutation.DeleteEntry(generateMutationId(), exhibitionId)))
    }

    suspend fun publishNote(exhibitionId: String) {
        mutate(listOf(MyGallrAccountMutation.PublishNote(generateMutationId(), exhibitionId)))
    }

    suspend fun unpublishNote(exhibitionId: String) {
        mutate(listOf(MyGallrAccountMutation.UnpublishNote(generateMutationId(), exhibitionId)))
    }

    suspend fun acknowledgeNoteApproval(exhibitionId: String) {
        mutate(listOf(MyGallrAccountMutation.AcknowledgeNoteApproval(generateMutationId(), exhibitionId)))
    }

    /** The latest publish or unpublish per exhibition still in the active account's outbox; empty without one. */
    fun observePendingNoteMutations(): Flow<Map<String, PendingNoteMutation>> =
        activeUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyMap())
            } else {
                accountStore.observe(userId).map { it.pendingMutations.pendingNoteMutations() }
            }
        }

    suspend fun followGalleries(galleries: List<FollowedGallery>) {
        if (galleries.isEmpty()) return
        mutate(galleries.map { MyGallrAccountMutation.FollowGallery(generateMutationId(), it) })
    }

    suspend fun unfollowGallery(galleryKey: String) {
        mutate(listOf(MyGallrAccountMutation.UnfollowGallery(generateMutationId(), galleryKey)))
    }

    suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) {
        if (currentExhibitionIds.isEmpty()) return
        mutate(
            listOf(
                MyGallrAccountMutation.AcknowledgeGallery(
                    generateMutationId(),
                    galleryKey,
                    currentExhibitionIds,
                ),
            ),
        )
    }

    suspend fun assignGalleryId(
        galleryKey: String,
        galleryId: String,
    ) {
        val userId = activeUserId.value ?: return
        val gallery = accountStore.get(userId).followedGalleries.firstOrNull { it.galleryKey == galleryKey } ?: return
        if (gallery.galleryId == galleryId) return
        mutate(
            listOf(
                MyGallrAccountMutation.FollowGallery(
                    generateMutationId(),
                    gallery.copy(galleryId = galleryId),
                ),
            ),
        )
    }

    suspend fun setDeviceAlertPreference(
        galleryKey: String,
        enabled: Boolean,
    ) {
        val userId = activeUserId.value ?: return
        accountStore.update(userId) { state ->
            state.copy(
                followedGalleries =
                    state.followedGalleries.map { gallery ->
                        if (gallery.galleryKey == galleryKey) {
                            gallery.copy(newExhibitionAlertsEnabled = enabled)
                        } else {
                            gallery
                        }
                    },
            )
        }
    }

    private suspend fun mutate(mutations: List<MyGallrAccountMutation>) {
        val userId = activeUserId.value ?: error("My Gallr account is not active")
        accountStore.update(userId) { state ->
            mutations.fold(state) { updated, mutation ->
                updated.apply(mutation).copy(
                    pendingMutations = updated.pendingMutations + mutation.toStored(),
                )
            }
        }
        flush(userId)
    }

    /**
     * Guest entries go to the account as merges, so a device note joins the account's note instead of being lost.
     * Guest records are cleared only once the server has them, so an activation that comes back before then (a
     * session refresh pending, or offline) finds them again; an entry whose merge is still queued is not merged a
     * second time, which would join its note twice.
     */
    private suspend fun mergeGuestRecords(userId: String) {
        val queuedMerges =
            accountStore
                .get(userId)
                .pendingMutations
                .filter { it.kind == MERGE_VISIT_KIND }
                .mapNotNullTo(mutableSetOf()) { it.visit?.exhibitionId }
        val guestVisits = guestVisitRepository.observeVisits().first().filterNot { it.exhibitionId in queuedMerges }
        val guestGalleries = guestFollowedGalleryRepository.observeFollowedGalleries().first()
        if (guestVisits.isEmpty() && guestGalleries.isEmpty()) return
        val mutations =
            guestVisits.map { MyGallrAccountMutation.MergeVisit(generateMutationId(), it) } +
                guestGalleries.map { MyGallrAccountMutation.FollowGallery(generateMutationId(), it) }
        accountStore.update(userId) { state ->
            mutations.fold(state) { updated, mutation ->
                updated.apply(mutation).copy(
                    pendingMutations = updated.pendingMutations + mutation.toStored(),
                )
            }
        }
        flush(userId)
    }

    private suspend fun flush(userId: String): Boolean =
        syncMutex.withLock {
            if (activeUserId.value != userId) return@withLock false
            status.value = MyGallrSyncStatus.SYNCING
            val before = accountStore.get(userId)
            val sent = before.pendingMutations
            try {
                val sentMutations = sent.map(StoredMyGallrMutation::toDomain)
                val remote = source.sync(sentMutations)
                if (activeUserId.value == userId) arrived.value = remote.lessChangesFrom(sentMutations)
                val sentIds = sent.mapTo(mutableSetOf()) { it.mutationId }
                accountStore.update(userId) { current ->
                    val remaining = current.pendingMutations.filterNot { it.mutationId in sentIds }
                    val localAlerts =
                        current.followedGalleries.associate {
                            it.galleryKey to
                                it.newExhibitionAlertsEnabled
                        }
                    val remoteWithDevicePreferences =
                        remote.copy(
                            followedGalleries =
                                remote.followedGalleries.map { gallery ->
                                    gallery.copy(
                                        newExhibitionAlertsEnabled = localAlerts[gallery.galleryKey] == true,
                                    )
                                },
                        )
                    remaining.fold(
                        StoredMyGallrAccountState(
                            revision = remoteWithDevicePreferences.revision,
                            visits = remoteWithDevicePreferences.visits,
                            followedGalleries = remoteWithDevicePreferences.followedGalleries,
                            pendingMutations = remaining,
                            publicNotes = remoteWithDevicePreferences.publicNotes,
                        ),
                    ) { updated, mutation -> updated.apply(mutation.toDomain()) }
                }
                clearAcknowledgedGuestRecords(remote)
                status.value = MyGallrSyncStatus.SYNCED
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                myGallrAccountSyncLog.warn("sync_account_archive", error)
                status.value = MyGallrSyncStatus.RETRY_NEEDED
                false
            }
        }

    private suspend fun clearAcknowledgedGuestRecords(remote: MyGallrAccountArchive) {
        val remoteVisitIds = remote.visits.mapTo(mutableSetOf()) { it.exhibitionId }
        guestVisitRepository
            .observeVisits()
            .first()
            .filter { it.exhibitionId in remoteVisitIds }
            .forEach { guestVisitRepository.removeVisit(it.exhibitionId) }
        val remoteGalleryKeys = remote.followedGalleries.mapTo(mutableSetOf()) { it.galleryKey }
        guestFollowedGalleryRepository
            .observeFollowedGalleries()
            .first()
            .filter { it.galleryKey in remoteGalleryKeys }
            .forEach { guestFollowedGalleryRepository.unfollowGallery(it.galleryKey) }
    }
}

class AuthAwareVisitRepository(
    private val guestRepository: VisitRepository,
    private val coordinator: MyGallrAccountSyncCoordinator,
) : VisitRepository {
    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        coordinator.observeActiveArchive().flatMapLatest { archive ->
            if (archive == null) guestRepository.observeVisits() else flowOf(archive.visits)
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        if (coordinator.hasActiveAccount()) coordinator.addVisits(visits) else guestRepository.addVisits(visits)
    }

    override suspend fun removeVisit(exhibitionId: String) {
        if (coordinator.hasActiveAccount()) {
            coordinator.removeVisit(
                exhibitionId,
            )
        } else {
            guestRepository.removeVisit(exhibitionId)
        }
    }

    override suspend fun saveEntry(visit: ExhibitionVisit) {
        if (coordinator.hasActiveAccount()) coordinator.saveEntry(visit) else guestRepository.saveEntry(visit)
    }

    override suspend fun deleteEntry(exhibitionId: String) {
        if (coordinator.hasActiveAccount()) {
            coordinator.deleteEntry(exhibitionId)
        } else {
            guestRepository.deleteEntry(exhibitionId)
        }
    }
}

class AuthAwareFollowedGalleryRepository(
    private val guestRepository: FollowedGalleryRepository,
    private val coordinator: MyGallrAccountSyncCoordinator,
) : FollowedGalleryRepository {
    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> =
        coordinator.observeActiveArchive().flatMapLatest { archive ->
            if (archive == null) guestRepository.observeFollowedGalleries() else flowOf(archive.followedGalleries)
        }

    override suspend fun followGalleries(galleries: List<FollowedGallery>) {
        if (coordinator.hasActiveAccount()) {
            coordinator.followGalleries(
                galleries,
            )
        } else {
            guestRepository.followGalleries(galleries)
        }
    }

    override suspend fun unfollowGallery(galleryKey: String) {
        if (coordinator.hasActiveAccount()) {
            coordinator.unfollowGallery(
                galleryKey,
            )
        } else {
            guestRepository.unfollowGallery(galleryKey)
        }
    }

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) {
        if (coordinator.hasActiveAccount()) {
            coordinator.acknowledgeGallery(galleryKey, currentExhibitionIds)
        } else {
            guestRepository.acknowledgeGallery(galleryKey, currentExhibitionIds)
        }
    }

    override suspend fun assignGalleryId(
        galleryKey: String,
        galleryId: String,
    ) {
        if (coordinator.hasActiveAccount()) {
            coordinator.assignGalleryId(galleryKey, galleryId)
        } else {
            guestRepository.assignGalleryId(galleryKey, galleryId)
        }
    }

    override suspend fun setNewExhibitionAlertsEnabled(
        galleryKey: String,
        enabled: Boolean,
    ) {
        if (coordinator.hasActiveAccount()) {
            coordinator.setDeviceAlertPreference(galleryKey, enabled)
        } else {
            guestRepository.setNewExhibitionAlertsEnabled(galleryKey, enabled)
        }
    }
}

private fun StoredMyGallrAccountState.apply(mutation: MyGallrAccountMutation): StoredMyGallrAccountState =
    when (mutation) {
        is MyGallrAccountMutation.AddVisit -> {
            if (visits.any { it.exhibitionId == mutation.visit.exhibitionId }) {
                this
            } else {
                copy(visits = (visits + mutation.visit).sortedByDescending { it.createdAt })
            }
        }

        is MyGallrAccountMutation.RemoveVisit -> {
            copy(visits = visits.filterNot { it.exhibitionId == mutation.exhibitionId })
        }

        is MyGallrAccountMutation.FollowGallery -> {
            val existing = followedGalleries.firstOrNull { it.galleryKey == mutation.gallery.galleryKey }
            val merged =
                if (existing == null) {
                    mutation.gallery
                } else {
                    existing.copy(
                        galleryId = existing.galleryId ?: mutation.gallery.galleryId,
                        knownExhibitionIds = existing.knownExhibitionIds + mutation.gallery.knownExhibitionIds,
                        newExhibitionAlertsEnabled =
                            existing.newExhibitionAlertsEnabled || mutation.gallery.newExhibitionAlertsEnabled,
                    )
                }
            copy(
                followedGalleries =
                    (followedGalleries.filterNot { it.galleryKey == merged.galleryKey } + merged)
                        .sortedByDescending { it.followedAt },
            )
        }

        is MyGallrAccountMutation.UnfollowGallery -> {
            copy(followedGalleries = followedGalleries.filterNot { it.galleryKey == mutation.galleryKey })
        }

        is MyGallrAccountMutation.AcknowledgeGallery -> {
            copy(
                followedGalleries =
                    followedGalleries.map { gallery ->
                        if (gallery.galleryKey == mutation.galleryKey) {
                            gallery.copy(knownExhibitionIds = gallery.knownExhibitionIds + mutation.knownExhibitionIds)
                        } else {
                            gallery
                        }
                    },
            )
        }

        is MyGallrAccountMutation.UpdateVisit -> {
            updateEntry(mutation.visit)
        }

        is MyGallrAccountMutation.MergeVisit -> {
            val existing = visit(mutation.visit.exhibitionId)
            withVisit(if (existing == null) mutation.visit else mergeEntry(existing, mutation.visit))
        }

        is MyGallrAccountMutation.DeleteEntry -> {
            copy(
                visits = visits.filterNot { it.exhibitionId == mutation.exhibitionId },
                publicNotes = publicNotes.filterNot { it.exhibitionId == mutation.exhibitionId },
            )
        }

        is MyGallrAccountMutation.PublishNote -> {
            publishNote(mutation.exhibitionId)
        }

        is MyGallrAccountMutation.UnpublishNote -> {
            copy(publicNotes = publicNotes.filterNot { it.exhibitionId == mutation.exhibitionId })
        }

        is MyGallrAccountMutation.AcknowledgeNoteApproval -> {
            copy(
                publicNotes =
                    publicNotes.map { note ->
                        val seen =
                            note.moderation == PublicNoteModeration.APPROVED &&
                                note.exhibitionId == mutation.exhibitionId
                        if (seen) note.copy(approvalSeen = true) else note
                    },
            )
        }
    }

private fun StoredMyGallrAccountState.visit(exhibitionId: String): ExhibitionVisit? =
    visits.firstOrNull { it.exhibitionId == exhibitionId }

/** Puts [visit] in place of the exhibition's visit, or adds it, keeping the newest record first. */
private fun StoredMyGallrAccountState.withVisit(visit: ExhibitionVisit): StoredMyGallrAccountState {
    val others = visits.filterNot { it.exhibitionId == visit.exhibitionId }
    return copy(visits = (others + visit).sortedByDescending { it.createdAt })
}

/**
 * The server's `update_visit`: a missing entry is created; an existing one takes the four diary fields only from a
 * later save (ties keep what the account holds); an entry left without a note withdraws its 감상.
 */
private fun StoredMyGallrAccountState.updateEntry(incoming: ExhibitionVisit): StoredMyGallrAccountState {
    val existing = visit(incoming.exhibitionId)
    val result =
        when {
            existing == null -> {
                incoming
            }

            existing.entryUpdatedAt == null ||
                incoming.entryUpdatedAt?.let { it > existing.entryUpdatedAt } == true -> {
                existing.copy(
                    visitDate = incoming.visitDate,
                    feelingWire = incoming.feelingWire,
                    tagWires = incoming.tagWires,
                    note = incoming.note,
                    entryUpdatedAt = incoming.entryUpdatedAt,
                )
            }

            else -> {
                existing
            }
        }
    val updated = if (result == existing) this else withVisit(result)
    return if (result.note == null) {
        updated.copy(publicNotes = publicNotes.filterNot { it.exhibitionId == incoming.exhibitionId })
    } else {
        updated
    }
}

/**
 * The server's `publish_note`: the entry's note, if it is short enough to publish, becomes the 감상 and waits for
 * review, unless the same text is already approved. Without an entry or a publishable note nothing changes.
 */
private fun StoredMyGallrAccountState.publishNote(exhibitionId: String): StoredMyGallrAccountState {
    val entry = visit(exhibitionId) ?: return this
    val text = entry.note?.takeIf { it.length <= DIARY_NOTE_MAX_LENGTH } ?: return this
    val current = publicNotes.firstOrNull { it.exhibitionId == exhibitionId }
    if (current?.moderation == PublicNoteModeration.APPROVED && current.content == text) return this
    val requestedAt = entry.entryUpdatedAt ?: current?.requestedAt ?: entry.createdAt
    val pending =
        current?.copy(
            content = text,
            moderation = PublicNoteModeration.PENDING,
            rejectionReason = null,
            requestedAt = requestedAt,
            approvedAt = null,
            approvalSeen = false,
        ) ?: OwnPublicNote(
            exhibitionId = exhibitionId,
            thoughtId = null,
            content = text,
            moderation = PublicNoteModeration.PENDING,
            rejectionReason = null,
            requestedAt = requestedAt,
            approvedAt = null,
            approvalSeen = false,
        )
    return copy(publicNotes = publicNotes.filterNot { it.exhibitionId == exhibitionId } + pending)
}

/** The latest publish or unpublish per exhibition among [this] queued mutations. */
private fun List<StoredMyGallrMutation>.pendingNoteMutations(): Map<String, PendingNoteMutation> =
    mapNotNull { mutation ->
        val change =
            when (mutation.kind) {
                PUBLISH_NOTE_KIND -> PendingNoteMutation.PUBLISH
                UNPUBLISH_NOTE_KIND -> PendingNoteMutation.UNPUBLISH
                else -> null
            }
        mutation.exhibitionId?.let { id -> change?.let { id to it } }
    }.toMap()

/**
 * What the server sent less the visits and follows [mutations] added: those are the device's own acts, including a
 * sheet save and the sign-in merge of guest entries.
 */
private fun MyGallrAccountArchive.lessChangesFrom(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive {
    val addedVisitIds =
        mutations.mapNotNullTo(mutableSetOf()) { mutation ->
            when (mutation) {
                is MyGallrAccountMutation.AddVisit -> mutation.visit.exhibitionId
                is MyGallrAccountMutation.UpdateVisit -> mutation.visit.exhibitionId
                is MyGallrAccountMutation.MergeVisit -> mutation.visit.exhibitionId
                else -> null
            }
        }
    val followedKeys =
        mutations
            .filterIsInstance<MyGallrAccountMutation.FollowGallery>()
            .mapTo(mutableSetOf()) { it.gallery.galleryKey }
    return copy(
        visits = visits.filterNot { it.exhibitionId in addedVisitIds },
        followedGalleries = followedGalleries.filterNot { it.galleryKey in followedKeys },
    )
}

private fun MyGallrAccountMutation.toStored(): StoredMyGallrMutation =
    when (this) {
        is MyGallrAccountMutation.AddVisit -> {
            StoredMyGallrMutation(mutationId, "add_visit", visit = visit)
        }

        is MyGallrAccountMutation.RemoveVisit -> {
            StoredMyGallrMutation(mutationId, "remove_visit", exhibitionId = exhibitionId)
        }

        is MyGallrAccountMutation.FollowGallery -> {
            StoredMyGallrMutation(mutationId, "follow_gallery", gallery = gallery)
        }

        is MyGallrAccountMutation.UnfollowGallery -> {
            StoredMyGallrMutation(mutationId, "unfollow_gallery", galleryKey = galleryKey)
        }

        is MyGallrAccountMutation.AcknowledgeGallery -> {
            StoredMyGallrMutation(
                mutationId,
                "acknowledge_gallery",
                galleryKey = galleryKey,
                knownExhibitionIds = knownExhibitionIds,
            )
        }

        is MyGallrAccountMutation.UpdateVisit -> {
            StoredMyGallrMutation(mutationId, UPDATE_VISIT_KIND, visit = visit)
        }

        is MyGallrAccountMutation.MergeVisit -> {
            StoredMyGallrMutation(mutationId, MERGE_VISIT_KIND, visit = visit)
        }

        is MyGallrAccountMutation.DeleteEntry -> {
            StoredMyGallrMutation(mutationId, DELETE_ENTRY_KIND, exhibitionId = exhibitionId)
        }

        is MyGallrAccountMutation.PublishNote -> {
            StoredMyGallrMutation(mutationId, PUBLISH_NOTE_KIND, exhibitionId = exhibitionId)
        }

        is MyGallrAccountMutation.UnpublishNote -> {
            StoredMyGallrMutation(mutationId, UNPUBLISH_NOTE_KIND, exhibitionId = exhibitionId)
        }

        is MyGallrAccountMutation.AcknowledgeNoteApproval -> {
            StoredMyGallrMutation(mutationId, ACKNOWLEDGE_NOTE_APPROVAL_KIND, exhibitionId = exhibitionId)
        }
    }

private fun StoredMyGallrMutation.toDomain(): MyGallrAccountMutation =
    when (kind) {
        "add_visit" -> {
            MyGallrAccountMutation.AddVisit(mutationId, requireNotNull(visit))
        }

        "remove_visit" -> {
            MyGallrAccountMutation.RemoveVisit(mutationId, requireNotNull(exhibitionId))
        }

        "follow_gallery" -> {
            MyGallrAccountMutation.FollowGallery(mutationId, requireNotNull(gallery))
        }

        "unfollow_gallery" -> {
            MyGallrAccountMutation.UnfollowGallery(mutationId, requireNotNull(galleryKey))
        }

        "acknowledge_gallery" -> {
            MyGallrAccountMutation.AcknowledgeGallery(mutationId, requireNotNull(galleryKey), knownExhibitionIds)
        }

        UPDATE_VISIT_KIND -> {
            MyGallrAccountMutation.UpdateVisit(mutationId, requireNotNull(visit))
        }

        MERGE_VISIT_KIND -> {
            MyGallrAccountMutation.MergeVisit(mutationId, requireNotNull(visit))
        }

        DELETE_ENTRY_KIND -> {
            MyGallrAccountMutation.DeleteEntry(mutationId, requireNotNull(exhibitionId))
        }

        PUBLISH_NOTE_KIND -> {
            MyGallrAccountMutation.PublishNote(mutationId, requireNotNull(exhibitionId))
        }

        UNPUBLISH_NOTE_KIND -> {
            MyGallrAccountMutation.UnpublishNote(mutationId, requireNotNull(exhibitionId))
        }

        ACKNOWLEDGE_NOTE_APPROVAL_KIND -> {
            MyGallrAccountMutation.AcknowledgeNoteApproval(mutationId, requireNotNull(exhibitionId))
        }

        else -> {
            error("Unsupported stored My Gallr mutation")
        }
    }

private fun randomMyGallrMutationId(): String {
    val bytes = Random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}
