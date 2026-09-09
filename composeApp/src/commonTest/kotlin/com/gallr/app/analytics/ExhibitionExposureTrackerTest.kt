package com.gallr.app.analytics

import com.gallr.shared.analytics.PositionBucket
import kotlin.test.Test
import kotlin.test.assertEquals

class ExhibitionExposureTrackerTest {
    @Test
    fun `one surface visit deduplicates stable ids and preserves catalogue ranks`() {
        val session = ExhibitionExposureSession()
        session.updateCatalogue((0..12).map { "exhibition-$it" })

        val first = session.newlyVisible(listOf("header", "exhibition-0", "exhibition-3", "paid-promotion:x"))
        val repeated = session.newlyVisible(listOf("exhibition-0", "exhibition-3"))
        val later = session.newlyVisible(listOf("exhibition-10"))

        assertEquals(
            listOf(
                RankedExhibitionExposure("exhibition-0", PositionBucket.TOP_THREE),
                RankedExhibitionExposure("exhibition-3", PositionBucket.FOUR_TO_TEN),
            ),
            first,
        )
        assertEquals(emptyList(), repeated)
        assertEquals(
            listOf(RankedExhibitionExposure("exhibition-10", PositionBucket.AFTER_TEN)),
            later,
        )
    }

    @Test
    fun `catalogue refresh does not repeat an exposure within the same visit`() {
        val session = ExhibitionExposureSession()
        session.updateCatalogue(listOf("one", "two"))
        session.newlyVisible(listOf("one"))

        session.updateCatalogue(listOf("two", "one", "three"))

        assertEquals(emptyList(), session.newlyVisible(listOf("one")))
        assertEquals(
            listOf(RankedExhibitionExposure("three", PositionBucket.TOP_THREE)),
            session.newlyVisible(listOf("three")),
        )
    }
}
