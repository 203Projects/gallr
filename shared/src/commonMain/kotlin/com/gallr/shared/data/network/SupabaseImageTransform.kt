package com.gallr.shared.data.network

/**
 * Rewrites a Supabase Storage public-object URL into the image-transformation
 * render endpoint with on-the-fly resizing, so cards download a right-sized,
 * CDN-cached, WebP-where-supported image instead of the full-resolution original.
 *
 *   .../storage/v1/object/public/bucket/x.jpg
 *     -> .../storage/v1/render/image/public/bucket/x.jpg?width=600&quality=75&resize=cover
 *
 * Image transformations require the Supabase Pro plan or above. Transformed
 * responses are served through the Smart CDN, so the first Korean request warms
 * a nearby edge node and subsequent requests skip the Mumbai origin entirely.
 *
 * Safe to call on any string: returns [url] unchanged when it is null/blank, is
 * not a Supabase `/object/public/` URL, or is already a `/render/image/` URL.
 * The project ref is NOT hardcoded — detection keys off the path marker so this
 * keeps working if the project URL changes (e.g. after a region migration).
 *
 * @param width  target width in px; clamped to Supabase's 1..2500 range.
 * @param quality 1..100 input, clamped to Supabase's 20..100 range (default 75).
 */
fun supabaseImageTransform(
    url: String?,
    width: Int,
    quality: Int = DEFAULT_TRANSFORM_QUALITY,
): String? {
    if (url == null) return null
    if (url.isBlank()) return url
    if (!url.contains(OBJECT_PUBLIC_MARKER)) return url

    val w = width.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
    val q = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    val rendered = url.replaceFirst(OBJECT_PUBLIC_MARKER, RENDER_PUBLIC_MARKER)
    val separator = if (rendered.contains('?')) '&' else '?'
    return "$rendered${separator}width=$w&quality=$q&resize=cover"
}

const val DEFAULT_TRANSFORM_QUALITY = 75

private const val OBJECT_PUBLIC_MARKER = "/storage/v1/object/public/"
private const val RENDER_PUBLIC_MARKER = "/storage/v1/render/image/public/"
private const val MIN_DIMENSION = 1
private const val MAX_DIMENSION = 2500
private const val MIN_QUALITY = 20
private const val MAX_QUALITY = 100
