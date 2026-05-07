package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition

class FakeExhibitionRepository(
    private val exhibitions: List<Exhibition>,
) : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions.filter { it.isFeatured })
    override suspend fun getExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions)
}
