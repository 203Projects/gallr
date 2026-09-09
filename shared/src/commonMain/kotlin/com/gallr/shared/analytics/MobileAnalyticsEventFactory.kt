@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.gallr.shared.analytics

import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Creates allowlisted events only after an enabled recorder invokes its lazy factory. */
class MobileAnalyticsEventFactory(
    private val platform: AnalyticsPlatform,
    private val appMajor: Int,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.of("Asia/Seoul"),
    private val eventIdFactory: () -> String = { Uuid.random().toString() },
) {
    init {
        require(appMajor in 1..999) { "appMajor must be between 1 and 999" }
    }

    fun surfaceViewed(
        surface: AnalyticsSurface,
        entryPoint: AnalyticsEntryPoint,
    ): MobileAnalyticsEvent =
        MobileAnalyticsEvent.surfaceViewed(
            eventId = eventIdFactory(),
            occurredOn = clock.todayIn(timeZone),
            platform = platform,
            appMajor = appMajor,
            surface = surface,
            entryPoint = entryPoint,
        )

    fun exhibitionImpression(
        exhibitionId: String,
        surface: AnalyticsSurface,
        discoveryKind: DiscoveryKind,
        positionBucket: PositionBucket,
    ): MobileAnalyticsEvent =
        MobileAnalyticsEvent.exhibitionImpression(
            eventId = eventIdFactory(),
            occurredOn = clock.todayIn(timeZone),
            platform = platform,
            appMajor = appMajor,
            exhibitionId = exhibitionId,
            surface = surface,
            discoveryKind = discoveryKind,
            positionBucket = positionBucket,
        )

    fun exhibitionOpened(
        exhibitionId: String,
        surface: AnalyticsSurface,
        discoveryKind: DiscoveryKind,
        positionBucket: PositionBucket,
    ): MobileAnalyticsEvent =
        MobileAnalyticsEvent.exhibitionOpened(
            eventId = eventIdFactory(),
            occurredOn = clock.todayIn(timeZone),
            platform = platform,
            appMajor = appMajor,
            exhibitionId = exhibitionId,
            surface = surface,
            discoveryKind = discoveryKind,
            positionBucket = positionBucket,
        )

    fun exhibitionIntent(
        exhibitionId: String,
        surface: AnalyticsSurface,
        action: AnalyticsIntentAction,
    ): MobileAnalyticsEvent =
        MobileAnalyticsEvent.exhibitionIntent(
            eventId = eventIdFactory(),
            occurredOn = clock.todayIn(timeZone),
            platform = platform,
            appMajor = appMajor,
            exhibitionId = exhibitionId,
            surface = surface,
            action = action,
        )

    fun recommendationsShown(
        surface: AnalyticsSurface,
        resultCount: Int,
    ): MobileAnalyticsEvent =
        MobileAnalyticsEvent.recommendationsShown(
            eventId = eventIdFactory(),
            occurredOn = clock.todayIn(timeZone),
            platform = platform,
            appMajor = appMajor,
            surface = surface,
            resultCount = resultCount,
        )

    fun routeCreated(
        mode: AnalyticsRouteMode,
        stopCount: Int,
        distanceBand: DistanceBand,
        durationBand: DurationBand,
    ): MobileAnalyticsEvent =
        routeEvent(
            started = false,
            mode = mode,
            stopCount = stopCount,
            distanceBand = distanceBand,
            durationBand = durationBand,
        )

    fun routeStarted(
        mode: AnalyticsRouteMode,
        stopCount: Int,
        distanceBand: DistanceBand,
        durationBand: DurationBand,
    ): MobileAnalyticsEvent =
        routeEvent(
            started = true,
            mode = mode,
            stopCount = stopCount,
            distanceBand = distanceBand,
            durationBand = durationBand,
        )

    private fun routeEvent(
        started: Boolean,
        mode: AnalyticsRouteMode,
        stopCount: Int,
        distanceBand: DistanceBand,
        durationBand: DurationBand,
    ): MobileAnalyticsEvent {
        val eventId = eventIdFactory()
        val occurredOn = clock.todayIn(timeZone)
        return if (started) {
            MobileAnalyticsEvent.routeStarted(
                eventId = eventId,
                occurredOn = occurredOn,
                platform = platform,
                appMajor = appMajor,
                mode = mode,
                stopCount = stopCount,
                distanceBand = distanceBand,
                durationBand = durationBand,
            )
        } else {
            MobileAnalyticsEvent.routeCreated(
                eventId = eventId,
                occurredOn = occurredOn,
                platform = platform,
                appMajor = appMajor,
                mode = mode,
                stopCount = stopCount,
                distanceBand = distanceBand,
                durationBand = durationBand,
            )
        }
    }
}

fun parseAppMajorVersion(versionName: String): Int? =
    versionName
        .trim()
        .substringBefore('.')
        .toIntOrNull()
        ?.takeIf { it in 1..999 }

fun positionBucket(index: Int?): PositionBucket =
    when (index) {
        null -> PositionBucket.UNRANKED
        in 0..2 -> PositionBucket.TOP_THREE
        in 3..9 -> PositionBucket.FOUR_TO_TEN
        in 10..Int.MAX_VALUE -> PositionBucket.AFTER_TEN
        else -> PositionBucket.UNRANKED
    }
