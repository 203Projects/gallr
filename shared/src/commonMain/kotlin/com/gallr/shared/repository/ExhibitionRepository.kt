package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition

interface ExhibitionRepository {
    suspend fun getFeaturedExhibitions(): Result<List<Exhibition>>

    suspend fun getExhibitions(): Result<List<Exhibition>>
}
