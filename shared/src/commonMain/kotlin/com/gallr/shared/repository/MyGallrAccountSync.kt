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
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    fun archive() = MyGallrAccountArchive(revision, visits, followedGalleries)
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

    fun observeActiveArchive(): Flow<MyGallrAccountArchive?> =
        activeUserId.flatMapLatest { userId ->
            if (userId == null) flowOf(null) else accountStore.observe(userId).map(StoredMyGallrAccountState::archive)
        }

    fun hasActiveAccount(): Boolean = activeUserId.value != null

    suspend fun activateAccount(userId: String) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        activeUserId.value = userId
        mergeGuestRecords(userId)
        flush(userId)
    }

    fun deactivateAccount() {
        activeUserId.value = null
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

    private suspend fun mergeGuestRecords(userId: String) {
        val guestVisits = guestVisitRepository.observeVisits().first()
        val guestGalleries = guestFollowedGalleryRepository.observeFollowedGalleries().first()
        if (guestVisits.isEmpty() && guestGalleries.isEmpty()) return
        val mutations =
            guestVisits.map { MyGallrAccountMutation.AddVisit(generateMutationId(), it) } +
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
                val remote = source.sync(sent.map(StoredMyGallrMutation::toDomain))
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
