package com.gallr.shared.repository

import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.network.PromotionSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromotionRepositoryTest {
    private val placement =
        PromotedExhibition(
            promotionId = "promotion-one",
            exhibitionId = "between-seasons",
            nameKo = "계절 사이",
            nameEn = "Between Seasons",
            venueNameKo = "아틀리에 한남",
            venueNameEn = "Atelier Hannam",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "용산구",
            regionEn = "Yongsan-gu",
            openingDate = "2026-08-08",
            closingDate = "2026-09-14",
            coverImageUrl = null,
        )

    @Test
    fun `uses one installation key and coarse locality only`() =
        runTest {
            val calls = mutableListOf<List<String>>()
            val repository =
                PromotionRepositoryImpl(
                    source =
                        object : PromotionSource {
                            override suspend fun fetch(
                                key: String,
                                cityKo: String,
                                regionKo: String,
                            ): PromotedExhibition? {
                                calls += listOf(key, cityKo, regionKo)
                                return placement
                            }
                        },
                    keyStore =
                        object : PromotionInstallationKeyStore {
                            override suspend fun getOrCreate(): String = "installation-key-1234"
                        },
                )

            assertEquals(placement, repository.getPromotedExhibition("서울", "용산구").getOrThrow())
            assertEquals(listOf(listOf("installation-key-1234", "서울", "용산구")), calls)
        }

    @Test
    fun `does not create a key or call the service without locality`() =
        runTest {
            var keyReads = 0
            var sourceCalls = 0
            val repository =
                PromotionRepositoryImpl(
                    source =
                        object : PromotionSource {
                            override suspend fun fetch(
                                key: String,
                                cityKo: String,
                                regionKo: String,
                            ): PromotedExhibition? {
                                sourceCalls += 1
                                return placement
                            }
                        },
                    keyStore =
                        object : PromotionInstallationKeyStore {
                            override suspend fun getOrCreate(): String {
                                keyReads += 1
                                return "unused"
                            }
                        },
                )

            assertNull(repository.getPromotedExhibition("", "").getOrThrow())
            assertEquals(0, keyReads)
            assertEquals(0, sourceCalls)
        }

    @Test
    fun `disabled capability constructs or calls neither source nor key store`() =
        runTest {
            var sourceConstructions = 0
            var sourceCalls = 0
            var keyStoreConstructions = 0
            var keyReads = 0
            val repository =
                createPromotionRepository(
                    enabled = false,
                    source = {
                        sourceConstructions += 1
                        object : PromotionSource {
                            override suspend fun fetch(
                                key: String,
                                cityKo: String,
                                regionKo: String,
                            ): PromotedExhibition? {
                                sourceCalls += 1
                                return placement
                            }
                        }
                    },
                    keyStore = {
                        keyStoreConstructions += 1
                        object : PromotionInstallationKeyStore {
                            override suspend fun getOrCreate(): String {
                                keyReads += 1
                                return "installation-key-1234"
                            }
                        }
                    },
                )

            assertNull(repository.getPromotedExhibition("서울", "용산구").getOrThrow())
            assertEquals(0, sourceConstructions)
            assertEquals(0, sourceCalls)
            assertEquals(0, keyStoreConstructions)
            assertEquals(0, keyReads)
        }
}
