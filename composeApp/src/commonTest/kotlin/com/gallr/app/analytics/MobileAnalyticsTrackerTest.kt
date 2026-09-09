package com.gallr.app.analytics

import com.gallr.shared.analytics.AnalyticsEntryPoint
import com.gallr.shared.analytics.AnalyticsIntentAction
import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.AnalyticsRecorder
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.DiscoveryKind
import com.gallr.shared.analytics.MobileAnalyticsEvent
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.analytics.PositionBucket
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class MobileAnalyticsTrackerTest {
    @Test
    fun `tracker records only coarse typed navigation and completed intent context`() =
        runTest {
            val recorder = RecordingRecorder()
            val tracker = MobileAnalyticsTracker(recorder, eventFactory())

            tracker.surfaceViewed(AnalyticsSurface.FEATURED, AnalyticsEntryPoint.TAB)
            tracker.exhibitionOpened(
                exhibitionId = "exhibition-one",
                attribution =
                    DiscoveryAttribution(
                        surface = AnalyticsSurface.LIST,
                        kind = DiscoveryKind.SEARCH,
                        position = PositionBucket.FOUR_TO_TEN,
                    ),
            )
            tracker.exhibitionIntent(
                exhibitionId = "exhibition-one",
                surface = AnalyticsSurface.EXHIBITION_DETAIL,
                action = AnalyticsIntentAction.OPEN_MAPS,
            )

            assertEquals(
                listOf("surface_viewed", "exhibition_opened", "exhibition_intent"),
                recorder.events.map { event ->
                    Json
                        .encodeToString(event)
                        .substringAfter("\"event_name\":\"")
                        .substringBefore('"')
                },
            )
            assertEquals(PositionBucket.FOUR_TO_TEN, recorder.events[1].positionBucket)
            assertEquals(AnalyticsIntentAction.OPEN_MAPS, recorder.events[2].action)
        }

    @Test
    fun `missing platform factory remains a complete no op`() =
        runTest {
            val recorder = RecordingRecorder()
            val tracker = MobileAnalyticsTracker(recorder, null)

            tracker.surfaceViewed(AnalyticsSurface.MAP, AnalyticsEntryPoint.TAB)

            assertEquals(emptyList(), recorder.events)
        }

    private fun eventFactory() =
        MobileAnalyticsEventFactory(
            platform = AnalyticsPlatform.ANDROID,
            appMajor = 1,
            clock =
                object : Clock {
                    override fun now(): Instant = Instant.parse("2026-08-30T12:00:00Z")
                },
            timeZone = TimeZone.UTC,
            eventIdFactory =
                generateSequence(1) { it + 1 }
                    .map { index -> "a7000000-0000-4000-8000-${index.toString().padStart(12, '0')}" }
                    .iterator()::next,
        )

    private class RecordingRecorder : AnalyticsRecorder {
        val events = mutableListOf<MobileAnalyticsEvent>()

        override suspend fun record(createEvent: () -> MobileAnalyticsEvent) {
            events += createEvent()
        }

        override suspend fun flush() = Unit

        override suspend fun clear() = Unit
    }
}
