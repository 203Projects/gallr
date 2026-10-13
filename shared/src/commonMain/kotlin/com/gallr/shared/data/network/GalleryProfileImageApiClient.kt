package com.gallr.shared.data.network

import com.gallr.shared.data.model.GalleryProfileImage
import com.gallr.shared.data.network.dto.GalleryProfileImageDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GalleryProfileImageApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) {
    private val baseUrl = supabaseUrl.trimEnd('/')
    private val endpoint = "$baseUrl/rest/v1/rpc/list_gallery_profile_images"
    private val publicBucketUrl = "$baseUrl/storage/v1/object/public/gallery-profile-images"

    /** Fetches curated images for active galleries; malformed rows are dropped. */
    suspend fun fetchProfileImages(): List<GalleryProfileImage> =
        client
            .post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.body<List<GalleryProfileImageDto>>()
            .mapNotNull { it.toDomainOrNull(publicBucketUrl) }
}
