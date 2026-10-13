package com.gallr.shared.repository

import com.gallr.shared.data.model.GalleryProfileImages
import com.gallr.shared.data.network.GalleryProfileImageApiClient
import com.gallr.shared.util.runSuspendCatching

class GalleryProfileImageRepositoryImpl(
    private val apiClient: GalleryProfileImageApiClient,
) : GalleryProfileImageRepository {
    override suspend fun getProfileImages(): Result<GalleryProfileImages> =
        runSuspendCatching { GalleryProfileImages(apiClient.fetchProfileImages()) }
}
