package com.gallr.shared.fixture

import com.gallr.shared.data.model.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the fixture against silent DTO mapping drift. */
class DiscoveryFixtureTest {
    @Test
    fun `snapshot decodes every published row through the production mapping`() {
        val exhibitions = DiscoveryFixture.exhibitions

        assertEquals(78, exhibitions.size)
        assertEquals(78, exhibitions.map { it.id }.distinct().size)
        assertTrue(
            exhibitions.all { exhibition ->
                val latitude = exhibition.latitude
                val longitude = exhibition.longitude
                latitude != null && longitude != null && runCatching { GeoPoint(latitude, longitude) }.isSuccess
            },
            "every fixture exhibition has valid coordinates",
        )
        assertEquals(68, exhibitions.count { !it.hours.isNullOrBlank() })
        assertTrue(exhibitions.all { it.closingDate >= DiscoveryFixture.referenceDate })
    }
}
