package com.gallr.shared.analytics

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class MobileAnalyticsEventFactoryTest {
    @Test
    fun `factory owns coarse platform version date and retry identity`() {
        val factory =
            MobileAnalyticsEventFactory(
                platform = AnalyticsPlatform.IOS,
                appMajor = 12,
                clock = fixedClock("2026-08-30T16:00:00Z"),
                timeZone = TimeZone.of("Asia/Seoul"),
                eventIdFactory = { "a6000000-0000-4000-8000-000000000001" },
            )

        val event =
            factory.exhibitionOpened(
                exhibitionId = "exhibition-one",
                surface = AnalyticsSurface.MAP,
                discoveryKind = DiscoveryKind.NEARBY,
                positionBucket = PositionBucket.UNRANKED,
            )

        assertEquals("a6000000-0000-4000-8000-000000000001", event.eventId)
        assertEquals("2026-08-31", event.occurredOn.toString())
        assertEquals(AnalyticsPlatform.IOS, event.platform)
        assertEquals(12, event.appMajor)
    }

    @Test
    fun `app major parsing fails closed`() {
        assertEquals(1, parseAppMajorVersion("1.10.1"))
        assertEquals(12, parseAppMajorVersion("12"))
        assertNull(parseAppMajorVersion("0.1"))
        assertNull(parseAppMajorVersion("1000.0"))
        assertNull(parseAppMajorVersion("beta"))
        assertNull(parseAppMajorVersion(""))
    }

    @Test
    fun `rank bucket boundaries are deterministic and direct opens are unranked`() {
        assertEquals(PositionBucket.UNRANKED, positionBucket(null))
        assertEquals(PositionBucket.UNRANKED, positionBucket(-1))
        assertEquals(PositionBucket.TOP_THREE, positionBucket(0))
        assertEquals(PositionBucket.TOP_THREE, positionBucket(2))
        assertEquals(PositionBucket.FOUR_TO_TEN, positionBucket(3))
        assertEquals(PositionBucket.FOUR_TO_TEN, positionBucket(9))
        assertEquals(PositionBucket.AFTER_TEN, positionBucket(10))
    }

    private fun fixedClock(value: String): Clock =
        object : Clock {
            override fun now(): Instant = Instant.parse(value)
        }
}
