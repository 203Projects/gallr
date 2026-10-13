package com.gallr.shared.repository

/** The exhibitions visitors opened most in the app this week (spec 091 FR-019). */
interface MostViewedExhibitionRepository {
    /** Exhibition ids, most opened first. Ids only: no counts and nothing about any visitor. */
    suspend fun mostViewedIds(): Result<List<String>>
}
