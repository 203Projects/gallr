package com.gallr.shared.data.network.dto

import com.gallr.shared.curatornote.CuratorNote
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.time.Instant

/** One insight paragraph as the view stores it; the grounding keys are provenance the app does not need. */
@Serializable
data class CuratorNoteInsightDto(
    val text: String = "",
)

/** A row of `curator_notes_published`. Required: `exhibition_id`, a non-blank `hook_ko` and `approved_at`. */
@Serializable
data class CuratorNoteDto(
    @SerialName("exhibition_id") val exhibitionId: String,
    @SerialName("version_number") val versionNumber: Int = 1,
    @SerialName("hook_ko") val hookKo: String,
    @SerialName("hook_en") val hookEn: String = "",
    @SerialName("insights_ko") val insightsKo: List<CuratorNoteInsightDto> = emptyList(),
    @SerialName("insights_en") val insightsEn: List<CuratorNoteInsightDto> = emptyList(),
    @SerialName("tip_ko") val tipKo: String = "",
    @SerialName("tip_en") val tipEn: String = "",
    @SerialName("reviewer_name_ko") val reviewerNameKo: String? = null,
    @SerialName("reviewer_name_en") val reviewerNameEn: String? = null,
    @SerialName("approved_at") val approvedAt: Instant,
) {
    /** Null when a required field is blank, so the caller can skip the row instead of failing the page. */
    fun toDomain(): CuratorNote? {
        if (exhibitionId.isBlank() || hookKo.isBlank()) return null
        return CuratorNote(
            exhibitionId = exhibitionId,
            versionNumber = versionNumber,
            hookKo = hookKo,
            hookEn = hookEn,
            insightsKo = insightsKo.map { it.text }.filter { it.isNotBlank() },
            insightsEn = insightsEn.map { it.text }.filter { it.isNotBlank() },
            tipKo = tipKo,
            tipEn = tipEn,
            reviewerNameKo = reviewerNameKo.orEmpty(),
            reviewerNameEn = reviewerNameEn.orEmpty(),
            approvedAt = approvedAt,
        )
    }
}

/** A decoded page: the notes that parsed and how many rows were skipped as malformed. */
data class CuratorNotePage(
    val notes: List<CuratorNote>,
    val skipped: Int,
)

private val curatorNoteJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

/**
 * Decodes a `curator_notes_published` response row by row: one malformed row is skipped and counted, the rest
 * survive. A body that is not a JSON array yields an empty page.
 */
fun decodeCuratorNotePage(body: String): CuratorNotePage {
    val rows =
        runCatching { curatorNoteJson.parseToJsonElement(body) }
            .getOrNull() as? JsonArray
            ?: return CuratorNotePage(emptyList(), 0)
    var skipped = 0
    val notes =
        rows.mapNotNull { row ->
            runCatching { curatorNoteJson.decodeFromJsonElement(CuratorNoteDto.serializer(), row).toDomain() }
                .getOrNull()
                .also { if (it == null) skipped += 1 }
        }
    return CuratorNotePage(notes, skipped)
}
