package com.gallr.shared.data.model

/**
 * Staff-curated representative image of one gallery: its own logo or a photo of its space,
 * stored by gallr as a square JPEG. [credit] is non-null only when the image license
 * requires attribution.
 */
data class GalleryProfileImage(
    val galleryId: String,
    val nameKo: String,
    val nameEn: String,
    val kind: Kind,
    val imageUrl: String,
    val credit: String?,
) {
    enum class Kind { LOGO, PHOTO }
}

/**
 * Lookup of curated images for galleries seen in the catalogue.
 *
 * Most catalogue exhibitions identify their gallery only by venue name, so a gallery id
 * match is preferred and the exact normalized [galleryKey] is the fallback. A name key
 * shared by records with different images is ambiguous and never matches, so no gallery
 * shows another gallery's image.
 */
class GalleryProfileImages(
    images: List<GalleryProfileImage>,
) {
    private val byId: Map<String, GalleryProfileImage> = images.associateBy { it.galleryId }
    private val byNameKey: Map<String, GalleryProfileImage> =
        images
            .groupBy { galleryKey(it.nameKo, it.nameEn) }
            .filterValues { candidates -> candidates.distinctBy { it.imageUrl }.size == 1 }
            .mapValues { (_, candidates) -> candidates.first() }

    fun find(
        galleryId: String?,
        nameKo: String,
        nameEn: String,
    ): GalleryProfileImage? = galleryId?.let(byId::get) ?: byNameKey[galleryKey(nameKo, nameEn)]

    companion object {
        val EMPTY = GalleryProfileImages(emptyList())
    }
}
