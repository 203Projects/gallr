package com.gallr.shared.data.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class FollowedGallerySnapshot(
    val nameKo: String,
    val nameEn: String,
    val cityKo: String,
    val cityEn: String,
    val regionKo: String,
    val regionEn: String,
) {
    fun localizedName(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> nameEn.ifEmpty { nameKo }
            AppLanguage.KO -> nameKo
        }

    fun localizedLocation(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> listOf(cityEn.ifEmpty { cityKo }, regionEn.ifEmpty { regionKo })
            AppLanguage.KO -> listOf(cityKo, regionKo)
        }.filter { it.isNotBlank() }
            .joinToString(" · ")
}

@Serializable
data class FollowedGallery(
    val galleryKey: String,
    val snapshot: FollowedGallerySnapshot,
    val knownExhibitionIds: Set<String>,
    val followedAt: Instant,
    val galleryId: String? = null,
    val newExhibitionAlertsEnabled: Boolean = false,
) {
    init {
        require(galleryKey.isNotBlank()) { "galleryKey must not be blank" }
    }
}

fun galleryKey(
    nameKo: String,
    nameEn: String,
): String = "${nameKo.normalizedGalleryName()}\u001F${nameEn.normalizedGalleryName()}"

private fun String.normalizedGalleryName(): String =
    trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
