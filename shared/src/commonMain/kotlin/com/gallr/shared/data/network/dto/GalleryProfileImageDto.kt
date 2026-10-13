package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.GalleryProfileImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GalleryProfileImageDto(
    @SerialName("gallery_id") val galleryId: String = "",
    @SerialName("name_ko") val nameKo: String = "",
    @SerialName("name_en") val nameEn: String = "",
    val kind: String = "",
    @SerialName("storage_path") val storagePath: String = "",
    val credit: String? = null,
) {
    /** Returns null for a malformed row so one bad row never fails the whole list. */
    fun toDomainOrNull(publicBucketUrl: String): GalleryProfileImage? {
        val domainKind =
            when (kind) {
                "logo" -> GalleryProfileImage.Kind.LOGO
                "photo" -> GalleryProfileImage.Kind.PHOTO
                else -> return null
            }
        if (galleryId.isBlank() || storagePath.isBlank()) return null
        return GalleryProfileImage(
            galleryId = galleryId,
            nameKo = nameKo,
            nameEn = nameEn,
            kind = domainKind,
            imageUrl = "$publicBucketUrl/$storagePath",
            credit = credit?.trim()?.ifEmpty { null },
        )
    }
}
