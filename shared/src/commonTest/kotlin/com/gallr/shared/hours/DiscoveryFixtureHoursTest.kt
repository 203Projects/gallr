package com.gallr.shared.hours

import com.gallr.shared.fixture.DiscoveryFixture
import com.gallr.shared.fixture.PublishedCatalogueHoursExpectations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SC-003: the listed hours in the published catalogue read as complete or partial schedules. */
class DiscoveryFixtureHoursTest {
    @Test
    fun readsListedHours() {
        val listed = DiscoveryFixture.exhibitions.filter { !it.hours.isNullOrBlank() }
        assertEquals(68, listed.size)
        assertEquals(listed.map { it.id }.toSet(), PublishedCatalogueHoursExpectations.byExhibitionId.keys)

        val unread =
            listed.filter { exhibition ->
                parseOpeningHours(exhibition.hours).completeness == OpeningHoursCompleteness.UNKNOWN
            }
        assertTrue(unread.size <= 1, "unread hours: ${unread.map { it.hours }}")

        listed.forEach { exhibition ->
            val expected = PublishedCatalogueHoursExpectations.byExhibitionId.getValue(exhibition.id)
            assertEquals(expected, parseOpeningHours(exhibition.hours), "${exhibition.id}: ${exhibition.hours}")
        }
    }
}
