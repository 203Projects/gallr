package com.gallr.shared.data.network

import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.MyGallrAccountArchive
import com.gallr.shared.data.model.MyGallrAccountMutation
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.ThoughtRejectionReason
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

class MyGallrAccountAuthenticationRequiredException : IllegalStateException("Authenticated session required")

interface MyGallrAccountCommandSource {
    suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive
}

class MyGallrAccountApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
    private val accessTokenProvider: suspend () -> String?,
) : MyGallrAccountCommandSource {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/sync_my_gallr_archive"

    override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive {
        val accessToken =
            accessTokenProvider()?.takeIf { it.isNotBlank() }
                ?: throw MyGallrAccountAuthenticationRequiredException()
        return client
            .post(endpoint) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(SyncMyGallrArchiveRequestDto(mutations.map(MyGallrAccountMutation::toDto)))
            }.body<MyGallrAccountArchiveDto>()
            .toDomain()
    }
}

@Serializable
private data class SyncMyGallrArchiveRequestDto(
    @SerialName("p_mutations") val mutations: List<MyGallrAccountMutationDto>,
)

@Serializable
private data class MyGallrAccountMutationDto(
    @SerialName("mutation_id") val mutationId: String,
    val kind: String,
    val record: JsonElement,
)

private fun MyGallrAccountMutation.toDto(): MyGallrAccountMutationDto =
    when (this) {
        is MyGallrAccountMutation.AddVisit -> {
            MyGallrAccountMutationDto(mutationId, "add_visit", visit.toJson())
        }

        is MyGallrAccountMutation.RemoveVisit -> {
            MyGallrAccountMutationDto(
                mutationId,
                "remove_visit",
                buildJsonObject { put("exhibition_id", exhibitionId) },
            )
        }

        is MyGallrAccountMutation.FollowGallery -> {
            MyGallrAccountMutationDto(mutationId, "follow_gallery", gallery.toJson())
        }

        is MyGallrAccountMutation.UnfollowGallery -> {
            MyGallrAccountMutationDto(
                mutationId,
                "unfollow_gallery",
                buildJsonObject { put("gallery_key", galleryKey) },
            )
        }

        is MyGallrAccountMutation.AcknowledgeGallery -> {
            MyGallrAccountMutationDto(
                mutationId,
                "acknowledge_gallery",
                buildJsonObject {
                    put("gallery_key", galleryKey)
                    put(
                        "known_exhibition_ids",
                        JsonArray(knownExhibitionIds.sorted().map(::JsonPrimitive)),
                    )
                },
            )
        }

        is MyGallrAccountMutation.UpdateVisit -> {
            MyGallrAccountMutationDto(mutationId, "update_visit", visit.toDiaryJson())
        }

        is MyGallrAccountMutation.MergeVisit -> {
            MyGallrAccountMutationDto(mutationId, "merge_visit", visit.toDiaryJson())
        }

        is MyGallrAccountMutation.DeleteEntry -> {
            MyGallrAccountMutationDto(mutationId, "delete_entry", exhibitionRecord(exhibitionId))
        }

        is MyGallrAccountMutation.PublishNote -> {
            MyGallrAccountMutationDto(mutationId, "publish_note", exhibitionRecord(exhibitionId))
        }

        is MyGallrAccountMutation.UnpublishNote -> {
            MyGallrAccountMutationDto(mutationId, "unpublish_note", exhibitionRecord(exhibitionId))
        }

        is MyGallrAccountMutation.AcknowledgeNoteApproval -> {
            MyGallrAccountMutationDto(mutationId, "acknowledge_note_approval", exhibitionRecord(exhibitionId))
        }
    }

private fun exhibitionRecord(exhibitionId: String) = buildJsonObject { put("exhibition_id", exhibitionId) }

/** The `add_visit` record plus the diary keys of contracts/database.md M4, every one present, null when empty. */
private fun ExhibitionVisit.toDiaryJson() =
    JsonObject(
        toJson() +
            mapOf(
                "visit_date" to (visitDate?.toString()?.let(::JsonPrimitive) ?: JsonNull),
                "feeling" to (feelingWire?.let(::JsonPrimitive) ?: JsonNull),
                "tags" to JsonArray(tagWires.map(::JsonPrimitive)),
                "note" to (note?.let(::JsonPrimitive) ?: JsonNull),
                "entry_updated_at" to (entryUpdatedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull),
            ),
    )

private fun ExhibitionVisit.toJson() =
    buildJsonObject {
        put("client_record_id", clientRecordId)
        put("exhibition_id", exhibitionId)
        put("created_at", createdAt.toString())
        put(
            "snapshot",
            buildJsonObject {
                put("name_ko", snapshot.nameKo)
                put("name_en", snapshot.nameEn)
                put("venue_name_ko", snapshot.venueNameKo)
                put("venue_name_en", snapshot.venueNameEn)
                put("opening_date", snapshot.openingDate.toString())
                put("closing_date", snapshot.closingDate.toString())
                put("cover_image_url", snapshot.coverImageUrl?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    }

private fun FollowedGallery.toJson() =
    buildJsonObject {
        put("gallery_key", galleryKey)
        put("gallery_id", galleryId?.let(::JsonPrimitive) ?: JsonNull)
        put("known_exhibition_ids", JsonArray(knownExhibitionIds.sorted().map(::JsonPrimitive)))
        put("followed_at", followedAt.toString())
        put(
            "snapshot",
            buildJsonObject {
                put("name_ko", snapshot.nameKo)
                put("name_en", snapshot.nameEn)
                put("city_ko", snapshot.cityKo)
                put("city_en", snapshot.cityEn)
                put("region_ko", snapshot.regionKo)
                put("region_en", snapshot.regionEn)
            },
        )
    }

@Serializable
private data class MyGallrAccountArchiveDto(
    val revision: Long,
    val visits: List<ExhibitionVisitDto> = emptyList(),
    @SerialName("followed_galleries") val followedGalleries: List<FollowedGalleryDto> = emptyList(),
    @SerialName("public_notes") val publicNotes: List<OwnPublicNoteDto> = emptyList(),
) {
    fun toDomain() =
        MyGallrAccountArchive(
            revision = revision,
            visits = visits.map(ExhibitionVisitDto::toDomain),
            followedGalleries = followedGalleries.map(FollowedGalleryDto::toDomain),
            publicNotes = publicNotes.mapNotNull(OwnPublicNoteDto::toDomainOrNull),
        )
}

/** A visit row; the diary keys are absent before M4 and decode to an empty entry. */
@Serializable
private data class ExhibitionVisitDto(
    @SerialName("client_record_id") val clientRecordId: String,
    @SerialName("exhibition_id") val exhibitionId: String,
    val snapshot: ExhibitionVisitSnapshotDto,
    @SerialName("created_at") val createdAt: String,
    @SerialName("visit_date") val visitDate: String? = null,
    val feeling: String? = null,
    val tags: List<String> = emptyList(),
    val note: String? = null,
    @SerialName("entry_updated_at") val entryUpdatedAt: String? = null,
) {
    fun toDomain() =
        ExhibitionVisit(
            clientRecordId = clientRecordId,
            exhibitionId = exhibitionId,
            snapshot = snapshot.toDomain(),
            createdAt = Instant.parse(createdAt),
            visitDate = visitDate?.let(LocalDate::parse),
            feelingWire = feeling,
            tagWires = tags,
            note = note,
            entryUpdatedAt = entryUpdatedAt?.let(Instant::parse),
        )
}

/**
 * One of the account's own 감상 in the archive's `public_notes`. A status this build does not know reads as in review,
 * so a 감상 that may be public is never shown to its author as private; a reason it does not know reads as none. A row
 * whose times cannot be read is left out rather than failing the whole archive.
 */
@Serializable
private data class OwnPublicNoteDto(
    @SerialName("exhibition_id") val exhibitionId: String,
    @SerialName("thought_id") val thoughtId: String,
    val content: String,
    val status: String,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("approval_seen") val approvalSeen: Boolean = false,
) {
    fun toDomainOrNull(): OwnPublicNote? {
        val requested = Instant.parseOrNull(requestedAt) ?: return null
        val approved = approvedAt?.let { Instant.parseOrNull(it) ?: return null }
        val moderation =
            when (status) {
                "approved" -> PublicNoteModeration.APPROVED
                "rejected" -> PublicNoteModeration.REJECTED
                else -> PublicNoteModeration.PENDING
            }
        return OwnPublicNote(
            exhibitionId = exhibitionId,
            thoughtId = thoughtId,
            content = content,
            moderation = moderation,
            rejectionReason = ThoughtRejectionReason.fromWire(rejectionReason),
            requestedAt = requested,
            approvedAt = approved,
            approvalSeen = approvalSeen,
        )
    }
}

@Serializable
private data class ExhibitionVisitSnapshotDto(
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("venue_name_ko") val venueNameKo: String,
    @SerialName("venue_name_en") val venueNameEn: String,
    @SerialName("opening_date") val openingDate: String,
    @SerialName("closing_date") val closingDate: String,
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
) {
    fun toDomain() =
        ExhibitionVisitSnapshot(
            nameKo = nameKo,
            nameEn = nameEn,
            venueNameKo = venueNameKo,
            venueNameEn = venueNameEn,
            openingDate = LocalDate.parse(openingDate),
            closingDate = LocalDate.parse(closingDate),
            coverImageUrl = coverImageUrl,
        )
}

@Serializable
private data class FollowedGalleryDto(
    @SerialName("gallery_key") val galleryKey: String,
    @SerialName("gallery_id") val galleryId: String? = null,
    val snapshot: FollowedGallerySnapshotDto,
    @SerialName("known_exhibition_ids") val knownExhibitionIds: Set<String> = emptySet(),
    @SerialName("followed_at") val followedAt: String,
) {
    fun toDomain() =
        FollowedGallery(
            galleryKey = galleryKey,
            galleryId = galleryId,
            snapshot = snapshot.toDomain(),
            knownExhibitionIds = knownExhibitionIds,
            followedAt = Instant.parse(followedAt),
            newExhibitionAlertsEnabled = false,
        )
}

@Serializable
private data class FollowedGallerySnapshotDto(
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("city_ko") val cityKo: String,
    @SerialName("city_en") val cityEn: String,
    @SerialName("region_ko") val regionKo: String,
    @SerialName("region_en") val regionEn: String,
) {
    fun toDomain() = FollowedGallerySnapshot(nameKo, nameEn, cityKo, cityEn, regionKo, regionEn)
}
