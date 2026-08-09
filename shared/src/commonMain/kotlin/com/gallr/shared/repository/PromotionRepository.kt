package com.gallr.shared.repository

import com.gallr.shared.data.model.PromotedExhibition

/** Retrieves a promoted placement without adding it to the organic exhibition repository. */
interface PromotionRepository {
    suspend fun getPromotedExhibition(cityKo: String, regionKo: String): Result<PromotedExhibition?>
}

/** Supplies a stable installation-scoped random key for the daily delivery cap. */
interface PromotionInstallationKeyStore {
    suspend fun getOrCreate(): String
}
