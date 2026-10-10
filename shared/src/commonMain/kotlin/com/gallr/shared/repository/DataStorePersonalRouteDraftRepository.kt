@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.observability.AppLog
import com.gallr.shared.route.AppendResult
import com.gallr.shared.route.CopyIntoDraftResult
import com.gallr.shared.route.MAX_ROUTE_STOPS
import com.gallr.shared.route.PendingAction
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.UndoResult
import com.gallr.shared.route.UndoToken
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val PERSONAL_ROUTE_DRAFT_KEY = stringPreferencesKey("personal_route_draft_v1")
private const val DRAFT_SCHEMA_VERSION = 1
private val draftLog = AppLog.tagged("PersonalRouteDraft")

/** [PersonalRouteDraftRepository] stored as one JSON value in Preferences DataStore. */
class DataStorePersonalRouteDraftRepository(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Instant = { Clock.System.now() },
    private val idFactory: () -> String = { Uuid.random().toString() },
) : PersonalRouteDraftRepository {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    /** Used until the first write so every read before it sees the same ids. */
    private val initialDraft: PersonalRouteDraft by lazy { emptyDraft() }

    override val draft: Flow<PersonalRouteDraft> =
        dataStore.data
            .map { preferences -> read(preferences) }
            .distinctUntilChanged()

    override suspend fun append(exhibition: Exhibition): AppendResult {
        var result: AppendResult = AppendResult.Full
        update { current ->
            val stop = exhibition.toRouteStop()
            val stops = current.route.stops
            result =
                when {
                    stop == null -> AppendResult.MissingLocation
                    stops.any { it.exhibitionId == stop.exhibitionId } -> AppendResult.Duplicate
                    stops.size >= MAX_ROUTE_STOPS -> AppendResult.Full
                    else -> AppendResult.Added(stops.size + 1)
                }
            if (result is AppendResult.Added && stop != null) {
                current.changed { it.copy(stops = stops + stop) }
            } else {
                current
            }
        }
        return result
    }

    override suspend fun move(
        from: Int,
        to: Int,
    ) {
        update { current ->
            val stops = current.route.stops
            if (from !in stops.indices || to !in stops.indices || from == to) return@update current
            val reordered = stops.toMutableList().apply { add(to, removeAt(from)) }
            current.changed { it.copy(stops = reordered) }
        }
    }

    override suspend fun remove(position: Int): UndoToken {
        var token: UndoToken? = null
        update { current ->
            val stops = current.route.stops
            require(position in stops.indices) { "no stop at position" }
            val changed = current.changed { it.copy(stops = stops.filterIndexed { index, _ -> index != position }) }
            token = UndoToken(changed.draftId, changed.revision, position, stops[position])
            changed
        }
        return checkNotNull(token)
    }

    override suspend fun undo(token: UndoToken): UndoResult {
        var result = UndoResult.Expired
        update { current ->
            val stops = current.route.stops
            result =
                when {
                    current.draftId != token.draftId || current.revision != token.revisionAfterRemove -> {
                        UndoResult.Expired
                    }

                    stops.any { it.exhibitionId == token.stop.exhibitionId } -> {
                        UndoResult.Duplicate
                    }

                    stops.size >= MAX_ROUTE_STOPS -> {
                        UndoResult.Full
                    }

                    else -> {
                        UndoResult.Restored
                    }
                }
            if (result != UndoResult.Restored) return@update current
            val restored = stops.toMutableList().apply { add(token.position.coerceAtMost(size), token.stop) }
            current.changed { it.copy(stops = restored) }
        }
        return result
    }

    override suspend fun rename(name: String) {
        update { current -> if (current.route.name == name) current else current.changed { it.copy(name = name) } }
    }

    override suspend fun replace(
        route: PersonalRoute,
        ownerAccountId: String?,
    ) {
        update {
            PersonalRouteDraft(
                draftId = idFactory(),
                revision = 0,
                route = route,
                ownerAccountId = ownerAccountId,
                savedRevision = if (ownerAccountId != null) 0 else null,
                pendingAction = null,
            )
        }
    }

    override suspend fun seed(stops: List<PersonalRouteStop>) {
        update { emptyDraft().let { it.copy(route = it.route.copy(stops = stops.take(MAX_ROUTE_STOPS))) } }
    }

    override suspend fun clear() {
        update { emptyDraft() }
    }

    override suspend fun detach() {
        update { current ->
            current.copy(
                route = current.route.copy(id = idFactory(), isPublished = false, revision = null),
                ownerAccountId = null,
                savedRevision = null,
                pendingAction = null,
            )
        }
    }

    override suspend fun setPending(
        kind: PendingKind,
        routeId: String?,
    ) {
        update { current ->
            current.copy(pendingAction = PendingAction(kind, clock(), current.draftId, current.revision, routeId))
        }
    }

    override suspend fun copyIntoDraft(
        name: String,
        stops: List<PersonalRouteStop>,
        expectedDraftId: String,
        expectedRevision: Long,
    ): CopyIntoDraftResult {
        var result: CopyIntoDraftResult = CopyIntoDraftResult.DraftChanged
        update { current ->
            if (current.draftId != expectedDraftId || current.revision != expectedRevision) {
                current
            } else {
                emptyDraft()
                    .let { it.copy(route = it.route.copy(name = name, stops = stops.take(MAX_ROUTE_STOPS))) }
                    .also { result = CopyIntoDraftResult.Applied(it.draftId) }
            }
        }
        return result
    }

    override suspend fun clearPending() {
        update { current -> current.copy(pendingAction = null) }
    }

    override suspend fun acknowledgeSave(
        draftId: String,
        sentRevision: Long,
        saved: PersonalRoute,
        ownerAccountId: String,
    ): Boolean {
        var applied = false
        update { current ->
            if (current.draftId != draftId) return@update current
            applied = true
            if (current.revision == sentRevision) {
                current.copy(route = saved, ownerAccountId = ownerAccountId, savedRevision = sentRevision)
            } else {
                val remote =
                    current.route.copy(
                        id = saved.id,
                        isPublished = saved.isPublished,
                        revision = saved.revision,
                    )
                current.copy(route = remote, ownerAccountId = ownerAccountId)
            }
        }
        return applied
    }

    override suspend fun markPublished(
        draftId: String,
        published: PersonalRoute,
    ) {
        update { current ->
            if (current.draftId != draftId || current.route.id != published.id) return@update current
            current.copy(route = current.route.copy(isPublished = true, revision = published.revision))
        }
    }

    private suspend fun update(transform: (PersonalRouteDraft) -> PersonalRouteDraft) {
        dataStore.edit { preferences ->
            val next = transform(read(preferences))
            preferences[PERSONAL_ROUTE_DRAFT_KEY] = json.encodeToString(DraftPayload.serializer(), next.toPayload())
        }
    }

    private fun read(preferences: Preferences): PersonalRouteDraft {
        val encoded = preferences[PERSONAL_ROUTE_DRAFT_KEY] ?: return initialDraft
        return try {
            val payload = json.decodeFromString(DraftPayload.serializer(), encoded)
            require(payload.schemaVersion == DRAFT_SCHEMA_VERSION) { "unsupported draft schema" }
            payload.toDraft()
        } catch (error: Exception) {
            draftLog.warn("route_draft_decode_failed", error)
            initialDraft
        }
    }

    private fun emptyDraft() =
        PersonalRouteDraft(
            draftId = idFactory(),
            revision = 0,
            route = PersonalRoute(id = idFactory(), name = "", stops = emptyList()),
            ownerAccountId = null,
            savedRevision = null,
            pendingAction = null,
        )

    /** An author change: new content, next revision, any pending action cleared (RO2). */
    private fun PersonalRouteDraft.changed(edit: (PersonalRoute) -> PersonalRoute) =
        copy(route = edit(route), revision = revision + 1, pendingAction = null)
}

@Serializable
private data class DraftPayload(
    val schemaVersion: Int = DRAFT_SCHEMA_VERSION,
    val draftId: String,
    val revision: Long,
    val routeId: String,
    val name: String,
    val stops: List<StopPayload>,
    val isPublished: Boolean,
    val routeRevision: Instant?,
    val ownerAccountId: String?,
    val savedRevision: Long?,
    val pending: PendingPayload?,
)

@Serializable
private data class StopPayload(
    val exhibitionId: String,
    val nameKo: String,
    val nameEn: String,
    val venueNameKo: String,
    val venueNameEn: String,
    val latitude: Double,
    val longitude: Double,
    val regionKo: String,
    val regionEn: String,
    val cityKo: String,
)

@Serializable
private data class PendingPayload(
    val kind: PendingKind,
    val createdAt: Instant,
    val draftId: String,
    val draftRevision: Long,
    val routeId: String? = null,
)

private fun PersonalRouteDraft.toPayload() =
    DraftPayload(
        draftId = draftId,
        revision = revision,
        routeId = route.id,
        name = route.name,
        stops =
            route.stops.map { stop ->
                StopPayload(
                    exhibitionId = stop.exhibitionId,
                    nameKo = stop.nameKo,
                    nameEn = stop.nameEn,
                    venueNameKo = stop.venueNameKo,
                    venueNameEn = stop.venueNameEn,
                    latitude = stop.point.latitude,
                    longitude = stop.point.longitude,
                    regionKo = stop.regionKo,
                    regionEn = stop.regionEn,
                    cityKo = stop.cityKo,
                )
            },
        isPublished = route.isPublished,
        routeRevision = route.revision,
        ownerAccountId = ownerAccountId,
        savedRevision = savedRevision,
        pending =
            pendingAction?.let { action ->
                PendingPayload(action.kind, action.createdAt, action.draftId, action.draftRevision, action.routeId)
            },
    )

private fun DraftPayload.toDraft() =
    PersonalRouteDraft(
        draftId = draftId,
        revision = revision,
        route =
            PersonalRoute(
                id = routeId,
                name = name,
                stops =
                    stops.map { stop ->
                        PersonalRouteStop(
                            exhibitionId = stop.exhibitionId,
                            nameKo = stop.nameKo,
                            nameEn = stop.nameEn,
                            venueNameKo = stop.venueNameKo,
                            venueNameEn = stop.venueNameEn,
                            point = GeoPoint(stop.latitude, stop.longitude),
                            regionKo = stop.regionKo,
                            regionEn = stop.regionEn,
                            cityKo = stop.cityKo,
                        )
                    },
                isPublished = isPublished,
                revision = routeRevision,
            ),
        ownerAccountId = ownerAccountId,
        savedRevision = savedRevision,
        pendingAction =
            pending?.let { payload ->
                PendingAction(payload.kind, payload.createdAt, payload.draftId, payload.draftRevision, payload.routeId)
            },
    )
