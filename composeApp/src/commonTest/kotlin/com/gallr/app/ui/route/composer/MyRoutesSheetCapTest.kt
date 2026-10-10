package com.gallr.app.ui.route.composer

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PersonalRouteSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 089 US9 (DD3): the route sheet lists at most three saved routes, with 모두 보기 for the rest. */
class MyRoutesSheetCapTest {
    private val updated = Instant.parse("2026-10-08T04:00:00Z")

    @Test
    fun theSheetShowsAtMostThreeSavedRoutes() {
        val five = (1..5).map(::summary)

        assertEquals(listOf("r1", "r2", "r3"), myRoutesPlannerRows(five).map { it.id })
        assertTrue(myRoutesShowsSeeAll(five))
    }

    @Test
    fun threeOrFewerNeedNoSeeAll() {
        val three = (1..3).map(::summary)

        assertEquals(3, myRoutesPlannerRows(three).size)
        assertFalse(myRoutesShowsSeeAll(three))
    }

    @Test
    fun seeAllWording() {
        assertEquals("모두 보기", myRoutesSeeAllLabel(AppLanguage.KO))
        assertEquals("SEE ALL", myRoutesSeeAllLabel(AppLanguage.EN))
    }

    private fun summary(index: Int) = PersonalRouteSummary("r$index", "동선 $index", 2, true, false, updated)
}
