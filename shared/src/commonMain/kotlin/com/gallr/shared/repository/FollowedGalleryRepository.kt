package com.gallr.shared.repository

import com.gallr.shared.data.model.FollowedGallery
import kotlinx.coroutines.flow.Flow

interface FollowedGalleryRepository {
    fun observeFollowedGalleries(): Flow<List<FollowedGallery>>

    suspend fun followGalleries(galleries: List<FollowedGallery>)

    suspend fun followGallery(gallery: FollowedGallery) {
        followGalleries(listOf(gallery))
    }

    suspend fun unfollowGallery(galleryKey: String)

    suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    )

    suspend fun assignGalleryId(
        galleryKey: String,
        galleryId: String,
    ) = Unit

    suspend fun setNewExhibitionAlertsEnabled(
        galleryKey: String,
        enabled: Boolean,
    ) = Unit
}
