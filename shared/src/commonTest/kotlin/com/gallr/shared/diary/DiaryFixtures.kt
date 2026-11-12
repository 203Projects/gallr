package com.gallr.shared.diary

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

internal fun diaryExhibition(
    id: String,
    opening: LocalDate = LocalDate(2026, 10, 1),
    closing: LocalDate = LocalDate(2026, 12, 31),
    nameKo: String = "전시 $id",
    nameEn: String = "Exhibition $id",
) = Exhibition(
    id = id,
    nameKo = nameKo,
    nameEn = nameEn,
    venueNameKo = "갤러리",
    venueNameEn = "Gallery",
    cityKo = "서울",
    cityEn = "Seoul",
    regionKo = "종로구",
    regionEn = "Jongno-gu",
    openingDate = opening,
    closingDate = closing,
    isFeatured = false,
    latitude = null,
    longitude = null,
    descriptionKo = "",
    descriptionEn = "",
    addressKo = "",
    addressEn = "",
    coverImageUrl = null,
)

internal fun diaryVisit(
    exhibitionId: String,
    createdAt: String = "2026-11-01T00:00:00Z",
    visitDate: LocalDate? = null,
    feeling: VisitFeeling? = null,
    tags: List<VisitTag> = emptyList(),
    note: String? = null,
    entryUpdatedAt: String? = null,
    nameKo: String = "전시 $exhibitionId",
    nameEn: String = "Exhibition $exhibitionId",
) = ExhibitionVisit(
    clientRecordId = "record-$exhibitionId",
    exhibitionId = exhibitionId,
    snapshot =
        ExhibitionVisitSnapshot(
            nameKo = nameKo,
            nameEn = nameEn,
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            openingDate = LocalDate(2026, 10, 1),
            closingDate = LocalDate(2026, 12, 31),
            coverImageUrl = null,
        ),
    createdAt = Instant.parse(createdAt),
    visitDate = visitDate,
    feelingWire = feeling?.wire,
    tagWires = tags.map(VisitTag::wire),
    note = note,
    entryUpdatedAt = entryUpdatedAt?.let(Instant::parse),
)

internal fun ownNote(
    exhibitionId: String,
    moderation: PublicNoteModeration = PublicNoteModeration.PENDING,
    content: String = "공개한 감상",
    rejectionReason: ThoughtRejectionReason? = null,
    requestedAt: String = "2026-11-02T00:00:00Z",
    approvalSeen: Boolean = false,
) = OwnPublicNote(
    exhibitionId = exhibitionId,
    thoughtId = "thought-$exhibitionId",
    content = content,
    moderation = moderation,
    rejectionReason = rejectionReason,
    requestedAt = Instant.parse(requestedAt),
    approvedAt = if (moderation == PublicNoteModeration.APPROVED) Instant.parse(requestedAt) else null,
    approvalSeen = approvalSeen,
)
