package com.gallr.shared.repository

import com.gallr.shared.data.network.MostViewedExhibitionApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 091 FR-019: a week of opens, a dozen ids, and a failure the caller can turn into a hidden tile. */
class MostViewedExhibitionRepositoryImplTest {
    @Test
    fun readsSevenDaysAndTwelveIds() =
        runTest {
            val api = FakeApi(Result.success(listOf("b", "a")))

            val ids = MostViewedExhibitionRepositoryImpl(api).mostViewedIds()

            assertEquals(listOf("b", "a"), ids.getOrThrow())
            assertEquals(listOf(7 to 12), api.requests)
        }

    @Test
    fun aFailedReadIsAFailureNotACrash() =
        runTest {
            val offline = FakeApi(Result.failure(IllegalStateException("offline")))

            val ids = MostViewedExhibitionRepositoryImpl(offline).mostViewedIds()

            assertTrue(ids.isFailure)
        }

    private class FakeApi(
        private val result: Result<List<String>>,
    ) : MostViewedExhibitionApi {
        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun fetchIds(
            days: Int,
            limit: Int,
        ): List<String> {
            requests += days to limit
            return result.getOrThrow()
        }
    }
}
