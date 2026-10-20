package com.gallr.shared.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 098 FR-006 and the requirement rule (data-model.md). */
class AppVersionTest {
    @Test
    fun parsesThreeNumbersAndIgnoresASuffix() {
        assertEquals(AppVersion(1, 13, 1), AppVersion.parse("1.13.1"))
        assertEquals(AppVersion(1, 14, 0), AppVersion.parse("1.14.0-debug"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("01.2.3"))
        assertEquals(AppVersion(1, 14, 10), AppVersion.parse("1.14.10 (42)"))
    }

    @Test
    fun refusesAnythingShortOfThreeNumbers() {
        assertNull(AppVersion.parse("1.13"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("a.b.c"))
        assertNull(AppVersion.parse("—"))
    }

    @Test
    fun comparesNumerically() {
        val versions = listOf("1.14.10", "1.13.1", "1.14.0", "2.0.0").map { requireNotNull(AppVersion.parse(it)) }

        assertEquals(listOf("1.13.1", "1.14.0", "1.14.10", "2.0.0"), versions.sorted().map { it.toString() })
        assertTrue(AppVersion(1, 14, 10) > AppVersion(1, 14, 9))
    }

    @Test
    fun theRequirementFollowsTheMinimumThenTheRecommendedVersion() {
        val policy = UpdatePolicy(minimum = AppVersion(1, 14, 0), recommended = AppVersion(1, 15, 0))

        assertEquals(UpdateRequirement.REQUIRED, updateRequirement(AppVersion(1, 13, 1), policy))
        assertEquals(UpdateRequirement.RECOMMENDED, updateRequirement(AppVersion(1, 14, 0), policy), "at the minimum")
        assertEquals(UpdateRequirement.RECOMMENDED, updateRequirement(AppVersion(1, 14, 3), policy))
        assertEquals(UpdateRequirement.NONE, updateRequirement(AppVersion(1, 15, 0), policy))
        assertEquals(UpdateRequirement.NONE, updateRequirement(AppVersion(2, 0, 0), policy))

        val onlyMinimum = UpdatePolicy(minimum = AppVersion(1, 14, 0), recommended = AppVersion(1, 14, 0))
        assertEquals(UpdateRequirement.NONE, updateRequirement(AppVersion(1, 14, 0), onlyMinimum), "equal to both")
    }
}
