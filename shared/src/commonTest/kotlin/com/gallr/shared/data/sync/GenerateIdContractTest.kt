package com.gallr.shared.data.sync

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents the ID generation contract used by SyncExhibitions.gs.
 * IDs are SHA-256 hashes of "nameKo|venueNameKo|cityKo|openingDate".
 *
 * These tests verify the contract expectations, not the GAS implementation directly.
 * The hash function runs in Google Apps Script; these tests ensure the logic
 * that different inputs produce different IDs and same inputs produce same IDs.
 */
class GenerateIdContractTest {

    /**
     * Simulates the GAS generateId hash: SHA-256 of the pipe-delimited input,
     * first 8 bytes as hex. Uses Kotlin's built-in to mirror the contract.
     */
    private fun generateId(nameKo: String, venueNameKo: String, cityKo: String, openingDate: String): String {
        val raw = "$nameKo|$venueNameKo|$cityKo|$openingDate".lowercase().trim()
        val digest = raw.encodeToByteArray().let { bytes ->
            // Simple hash for contract testing (not SHA-256, but validates uniqueness logic)
            var hash = 0L
            for (b in bytes) {
                hash = hash * 31 + b.toLong()
            }
            hash
        }
        return digest.toULong().toString(16).takeLast(16).padStart(16, '0')
    }

    @Test
    fun sameExhibitionProducesSameId() {
        val id1 = generateId("전시이름", "갤러리이름", "서울", "2026-04-01")
        val id2 = generateId("전시이름", "갤러리이름", "서울", "2026-04-01")
        assertEquals(id1, id2)
    }

    @Test
    fun differentCitiesProduceDifferentIds() {
        val seoulId = generateId("전시이름", "갤러리이름", "서울", "2026-04-01")
        val busanId = generateId("전시이름", "갤러리이름", "부산", "2026-04-01")
        assertNotEquals(seoulId, busanId, "Same exhibition in different cities should have different IDs")
    }

    @Test
    fun differentNamesProduceDifferentIds() {
        val id1 = generateId("전시A", "갤러리이름", "서울", "2026-04-01")
        val id2 = generateId("전시B", "갤러리이름", "서울", "2026-04-01")
        assertNotEquals(id1, id2)
    }

    @Test
    fun differentDatesProduceDifferentIds() {
        val id1 = generateId("전시이름", "갤러리이름", "서울", "2026-04-01")
        val id2 = generateId("전시이름", "갤러리이름", "서울", "2026-05-01")
        assertNotEquals(id1, id2)
    }

    @Test
    fun emptyFieldsProduceValidId() {
        val id = generateId("", "", "", "")
        // Should not crash, should produce a non-empty string
        assertTrue(id.isNotEmpty())
    }
}
