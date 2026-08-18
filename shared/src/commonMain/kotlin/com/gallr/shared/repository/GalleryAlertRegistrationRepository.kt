package com.gallr.shared.repository

import com.gallr.shared.data.model.RemotePushAddress

interface GalleryAlertRegistrationRepository {
    suspend fun enableGallery(
        galleryId: String,
        address: RemotePushAddress,
        locale: String,
    ): Result<Unit>

    suspend fun disableGallery(
        galleryId: String,
        platform: String,
        locale: String,
    ): Result<Unit>
}
