package com.gallr.app.ui.map

/** What a pin on the map shows: an exhibition cover, if it has one, inside a ring that marks a saved exhibition. */
internal data class MapPinImage(
    val coverUrl: String?,
    val saved: Boolean,
)

private const val PIN_IMAGE_PREFIX = "gallr-pin:"
private const val SAVED_MARK = 's'
private const val UNSAVED_MARK = 'u'

/**
 * The style image id of a pin. The map asks for an image by id only when a visible pin needs it, so the id
 * carries everything needed to draw it and each cover is fetched once per saved state.
 */
internal fun mapPinImageId(
    coverUrl: String?,
    saved: Boolean,
): String = "$PIN_IMAGE_PREFIX${if (saved) SAVED_MARK else UNSAVED_MARK}:${coverUrl?.trim().orEmpty()}"

/** The pin an id stands for, or null for an id that belongs to the map style rather than to gallr's pins. */
internal fun parseMapPinImageId(id: String): MapPinImage? {
    if (!id.startsWith(PIN_IMAGE_PREFIX)) return null
    val body = id.removePrefix(PIN_IMAGE_PREFIX)
    if (body.length < 2 || body[1] != ':') return null
    val saved =
        when (body[0]) {
            SAVED_MARK -> true
            UNSAVED_MARK -> false
            else -> return null
        }
    return MapPinImage(coverUrl = body.substring(2).ifBlank { null }, saved = saved)
}

/** Several exhibitions at one spot share a pin; it shows the first of them that has a cover. */
internal fun representativeCoverUrl(coverUrls: List<String?>): String? = coverUrls.firstOrNull { !it.isNullOrBlank() }
