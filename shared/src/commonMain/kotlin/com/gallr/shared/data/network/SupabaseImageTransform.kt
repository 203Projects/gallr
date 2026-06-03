package com.gallr.shared.data.network

/**
 * Rewrites a Supabase Storage public-object URL into the image-transformation
 * render endpoint with on-the-fly resizing, so cards download a right-sized,
 * CDN-cached, WebP-where-supported image instead of the full-resolution original.
 *
 *   .../storage/v1/object/public/bucket/x.jpg
 *     -> .../storage/v1/render/image/public/bucket/x.jpg?width=600&quality=75&resize=contain&height=1800
 *
 * Image transformations require the Supabase Pro plan or above. Transformed
 * responses are served through the Smart CDN, so the first Korean request warms
 * a nearby edge node and subsequent requests skip the Mumbai origin entirely.
 *
 * ## Why [RESIZE_CONTAIN] is the default
 *
 * Supabase's `cover` mode (its endpoint default) "fills a given size and crops
 * projecting parts" — and with **only** a width supplied it still crops on the
 * height axis to an implied box, *zooming in* and cutting off image content
 * (verified against the live render endpoint: `cover&width=600` returned a
 * 600×490 center-crop of a 700×490 source, changing the 1.43 aspect to 1.22).
 * Every consumer of this helper renders into a Compose `ContentScale.Crop` box,
 * so that server crop is a *second*, differently-shaped crop on top of Compose's
 * — the source of the over-zoom. `contain` instead "fits a given size" keeping
 * the full aspect (no server crop), handing the single, correct crop to Compose.
 *
 * Because Supabase's docs do not explicitly scope their single-parameter crop
 * rule to a mode, we make `contain` deterministic by emitting an explicit
 * [height] alongside the width. When the caller does not pass one we derive a
 * generous height (`width * CONTAIN_HEIGHT_FACTOR`, capped at [MAX_DIMENSION]) so
 * any realistic portrait source *fits* and the server never crops. For a source
 * that already fits, the explicit height is a no-op (empirically confirmed:
 * `contain&width=600` and `contain&width=600&height=1800` returned identical
 * 600×420 output). Fixed-aspect surfaces (e.g. the 16:9 detail hero) opt into
 * [RESIZE_COVER] with an explicit [height] so the server crops to exactly the
 * visible box and avoids a client double-crop.
 *
 * Safe to call on any string: returns [url] unchanged when it is null/blank, is
 * not a Supabase `/object/public/` URL, or is already a `/render/image/` URL.
 * The project ref is NOT hardcoded — detection keys off the path marker so this
 * keeps working if the project URL changes (e.g. after a region migration).
 *
 * @param width   target width in px; clamped to Supabase's 1..2500 range.
 * @param quality 1..100 input, clamped to Supabase's 20..100 range (default 75).
 * @param resize  Supabase resize mode: [RESIZE_CONTAIN] (default, no server crop)
 *                or [RESIZE_COVER] (server crops to the box — pass an explicit
 *                [height] for a fixed-aspect surface).
 * @param height  optional explicit target height in px, clamped to 1..2500. For
 *                [RESIZE_CONTAIN] a generous height is derived when omitted so the
 *                server never crops; for [RESIZE_COVER] no height is synthesized.
 */
fun supabaseImageTransform(
    url: String?,
    width: Int,
    quality: Int = DEFAULT_TRANSFORM_QUALITY,
    resize: String = RESIZE_CONTAIN,
    height: Int? = null,
): String? {
    if (url == null) return null
    if (url.isBlank()) return url
    if (!url.contains(OBJECT_PUBLIC_MARKER)) return url

    val w = width.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
    val q = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    // Pin contain to an explicit height so the server never falls back to its
    // single-parameter crop; cover only emits a height when the caller gives one.
    val effectiveHeight: Int? = when {
        height != null -> height.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        resize == RESIZE_CONTAIN -> (w * CONTAIN_HEIGHT_FACTOR).coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        else -> null
    }

    val rendered = url.replaceFirst(OBJECT_PUBLIC_MARKER, RENDER_PUBLIC_MARKER)
    val separator = if (rendered.contains('?')) '&' else '?'
    val base = "$rendered${separator}width=$w&quality=$q&resize=$resize"
    return if (effectiveHeight != null) "$base&height=$effectiveHeight" else base
}

const val DEFAULT_TRANSFORM_QUALITY = 75

/** Supabase resize mode that scales to fit, preserving aspect with no server crop. */
const val RESIZE_CONTAIN = "contain"

/** Supabase resize mode that fills the box and crops projecting parts. */
const val RESIZE_COVER = "cover"

private const val OBJECT_PUBLIC_MARKER = "/storage/v1/object/public/"
private const val RENDER_PUBLIC_MARKER = "/storage/v1/render/image/public/"
private const val MIN_DIMENSION = 1
private const val MAX_DIMENSION = 2500
private const val MIN_QUALITY = 20
private const val MAX_QUALITY = 100

// Derived contain height = width * this factor (capped at MAX_DIMENSION). 3 covers
// any realistic portrait source so the server fits-not-crops; tall enough that the
// share card's centered-square crop stays sharp.
private const val CONTAIN_HEIGHT_FACTOR = 3
