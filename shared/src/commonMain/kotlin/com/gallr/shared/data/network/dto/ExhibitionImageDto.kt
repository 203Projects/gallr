package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.ExhibitionImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** A row of `exhibition_gallery_images`. Required: `position` and an https `url`. */
@Serializable
data class ExhibitionImageDto(
    val position: Int,
    val url: String,
    @SerialName("alt_ko") val altKo: String = "",
    @SerialName("alt_en") val altEn: String = "",
    val credit: String = "",
    val width: Int? = null,
    val height: Int? = null,
) {
    /** Null when the address is not one the app will load, so the caller skips the row. */
    fun toDomain(): ExhibitionImage? {
        if (!url.startsWith("https://")) return null
        return ExhibitionImage(
            url = url,
            altKo = altKo,
            altEn = altEn,
            credit = credit,
            width = width,
            height = height,
        )
    }
}

private val exhibitionImageJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Decodes an `exhibition_gallery_images` response row by row in staff order: a malformed row is skipped and the
 * rest survive. A body that is not a JSON array yields no images.
 */
fun decodeExhibitionImages(body: String): List<ExhibitionImage> {
    val rows =
        runCatching { exhibitionImageJson.parseToJsonElement(body) }
            .getOrNull() as? JsonArray
            ?: return emptyList()
    return rows
        .mapNotNull { row ->
            runCatching { exhibitionImageJson.decodeFromJsonElement(ExhibitionImageDto.serializer(), row) }
                .getOrNull()
        }.sortedBy { it.position }
        .mapNotNull { it.toDomain() }
}
