package com.gallr.shared.data.network

/**
 * Returns a Supabase Storage public-object URL for native image sizing.
 *
 * The app previously rewrote cover URLs to Supabase's `/render/image` endpoint.
 * That lowers egress but consumes the Storage Image Transformations quota per
 * origin image. Compose/Coil, Android Canvas, and UIKit now own the resize/crop
 * locally, so this helper deliberately keeps requests on `/object/public`.
 *
 * Legacy render URLs are normalized back to object URLs to avoid continuing to
 * hit the transformation endpoint if an old value is passed through.
 */
fun nativeSupabaseImageUrl(url: String?): String? {
    if (url == null) return null
    if (url.isBlank()) return url
    if (!url.contains(RENDER_PUBLIC_MARKER)) return url

    return url.substringBefore('?')
        .replaceFirst(RENDER_PUBLIC_MARKER, OBJECT_PUBLIC_MARKER)
}

private const val OBJECT_PUBLIC_MARKER = "/storage/v1/object/public/"
private const val RENDER_PUBLIC_MARKER = "/storage/v1/render/image/public/"
