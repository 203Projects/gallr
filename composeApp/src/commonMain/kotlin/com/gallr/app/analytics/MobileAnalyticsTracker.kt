package com.gallr.app.analytics

import com.gallr.shared.analytics.AnalyticsEntryPoint
import com.gallr.shared.analytics.AnalyticsIntentAction
import com.gallr.shared.analytics.AnalyticsRecorder
import com.gallr.shared.analytics.AnalyticsRouteMode
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.DiscoveryKind
import com.gallr.shared.analytics.DistanceBand
import com.gallr.shared.analytics.DurationBand
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.analytics.PositionBucket

data class DiscoveryAttribution(
    val surface: AnalyticsSurface,
    val kind: DiscoveryKind,
    val position: PositionBucket,
)

/** Common application boundary for coarse, completed mobile product events. */
class MobileAnalyticsTracker(
    private val recorder: AnalyticsRecorder,
    private val eventFactory: MobileAnalyticsEventFactory?,
) {
    suspend fun surfaceViewed(
        surface: AnalyticsSurface,
        entryPoint: AnalyticsEntryPoint,
    ) {
        val factory = eventFactory ?: return
        recorder.record { factory.surfaceViewed(surface, entryPoint) }
    }

    suspend fun exhibitionOpened(
        exhibitionId: String,
        attribution: DiscoveryAttribution,
    ) {
        val factory = eventFactory ?: return
        recorder.record {
            factory.exhibitionOpened(
                exhibitionId = exhibitionId,
                surface = attribution.surface,
                discoveryKind = attribution.kind,
                positionBucket = attribution.position,
            )
        }
    }

    suspend fun exhibitionImpression(
        exhibitionId: String,
        attribution: DiscoveryAttribution,
    ) {
        val factory = eventFactory ?: return
        recorder.record {
            factory.exhibitionImpression(
                exhibitionId = exhibitionId,
                surface = attribution.surface,
                discoveryKind = attribution.kind,
                positionBucket = attribution.position,
            )
        }
    }

    suspend fun exhibitionIntent(
        exhibitionId: String,
        surface: AnalyticsSurface,
        action: AnalyticsIntentAction,
    ) {
        val factory = eventFactory ?: return
        recorder.record { factory.exhibitionIntent(exhibitionId, surface, action) }
    }

    suspend fun recommendationsShown(
        surface: AnalyticsSurface,
        resultCount: Int,
    ) {
        val factory = eventFactory ?: return
        recorder.record { factory.recommendationsShown(surface, resultCount) }
    }

    suspend fun routeCreated(
        mode: AnalyticsRouteMode,
        stopCount: Int,
        distanceBand: DistanceBand,
        durationBand: DurationBand,
    ) {
        val factory = eventFactory ?: return
        recorder.record {
            factory.routeCreated(
                mode = mode,
                stopCount = stopCount,
                distanceBand = distanceBand,
                durationBand = durationBand,
            )
        }
    }

    suspend fun routeStarted(
        mode: AnalyticsRouteMode,
        stopCount: Int,
        distanceBand: DistanceBand,
        durationBand: DurationBand,
    ) {
        val factory = eventFactory ?: return
        recorder.record {
            factory.routeStarted(
                mode = mode,
                stopCount = stopCount,
                distanceBand = distanceBand,
                durationBand = durationBand,
            )
        }
    }
}
