package com.gallr.shared.repository

import com.gallr.shared.data.network.MostViewedExhibitionApi
import com.gallr.shared.util.runSuspendCatching

/** "This week" on the Home tile. */
private const val WINDOW_DAYS = 7

/** The collection shows ten; a couple more let ended exhibitions drop out without thinning it. */
private const val ID_LIMIT = 12

class MostViewedExhibitionRepositoryImpl(
    private val api: MostViewedExhibitionApi,
) : MostViewedExhibitionRepository {
    override suspend fun mostViewedIds(): Result<List<String>> =
        runSuspendCatching { api.fetchIds(days = WINDOW_DAYS, limit = ID_LIMIT) }
}
