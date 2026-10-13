package com.gallr.shared.data.model

/** The exhibition page's gallery reads at most this many pictures after the cover. */
const val GALLERY_IMAGE_LIMIT = 12

/** A curator's note shows at most this many of them: one per insight. */
const val NOTE_IMAGE_LIMIT = 4

/** In a note, a picture's frame follows its own shape between these limits, so a banner or a scroll stays readable. */
const val NOTE_IMAGE_WIDEST = 16f / 9f
const val NOTE_IMAGE_TALLEST = 3f / 4f
const val NOTE_IMAGE_DEFAULT_RATIO = 3f / 2f

/**
 * One of an exhibition's published gallery images. The exhibition page pages through them after the cover, and the
 * curator's note shows one with each insight (DESIGN.md "Exhibition gallery", "Curator's note block"). The credit is
 * printed as the source gives it; the alt text is read aloud.
 */
data class ExhibitionImage(
    val url: String,
    val altKo: String,
    val altEn: String,
    val credit: String,
    val width: Int?,
    val height: Int?,
) {
    fun alt(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> altEn.ifBlank { altKo }
            AppLanguage.KO -> altKo
        }

    /** Width over height of the frame the picture is cropped into. */
    fun frameAspectRatio(): Float {
        val knownWidth = width?.takeIf { it > 0 } ?: return NOTE_IMAGE_DEFAULT_RATIO
        val knownHeight = height?.takeIf { it > 0 } ?: return NOTE_IMAGE_DEFAULT_RATIO
        return (knownWidth.toFloat() / knownHeight).coerceIn(NOTE_IMAGE_TALLEST, NOTE_IMAGE_WIDEST)
    }
}

/**
 * The pictures the top of the exhibition page pages through: the cover first, described by the exhibition's name,
 * then the gallery in staff order. A gallery image that is the cover again is left out.
 */
fun Exhibition.galleryPages(images: List<ExhibitionImage>): List<ExhibitionImage> {
    val cover = coverImageUrl?.takeIf { it.isNotBlank() }
    val coverPage =
        cover?.let {
            ExhibitionImage(url = it, altKo = nameKo, altEn = nameEn, credit = "", width = null, height = null)
        }
    return listOfNotNull(coverPage) + images.filter { it.url != cover }
}

/**
 * The pictures the curator's note sets above its insights: the first [NOTE_IMAGE_LIMIT] gallery images in staff
 * order. The cover already heads the note as its thumbnail, so a gallery image that is the cover again is left out.
 */
fun Exhibition.noteImages(images: List<ExhibitionImage>): List<ExhibitionImage> {
    val cover = coverImageUrl?.takeIf { it.isNotBlank() }
    return images.filter { it.url != cover }.take(NOTE_IMAGE_LIMIT)
}

/** `2 / 5`: where the reader is in the gallery. */
fun galleryCounter(
    index: Int,
    count: Int,
): String = "${index + 1} / $count"
