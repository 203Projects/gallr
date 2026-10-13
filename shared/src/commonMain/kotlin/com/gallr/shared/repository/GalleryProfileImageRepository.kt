package com.gallr.shared.repository

import com.gallr.shared.data.model.GalleryProfileImages

interface GalleryProfileImageRepository {
    /**
     * Returns Result.success with the lookup of curated gallery images, or
     * Result.failure on network/parse error. Callers fall back to monograms on failure.
     */
    suspend fun getProfileImages(): Result<GalleryProfileImages>
}
