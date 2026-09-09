package com.gallr.shared.analytics

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Serializable
enum class AnalyticsPlatform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,
}

@Serializable
enum class AnalyticsSurface {
    @SerialName("featured")
    FEATURED,

    @SerialName("list")
    LIST,

    @SerialName("map")
    MAP,

    @SerialName("my_gallr")
    MY_GALLR,

    @SerialName("exhibition_detail")
    EXHIBITION_DETAIL,

    @SerialName("gallery_detail")
    GALLERY_DETAIL,

    @SerialName("event_detail")
    EVENT_DETAIL,

    @SerialName("editor_detail")
    EDITOR_DETAIL,

    @SerialName("settings")
    SETTINGS,
}

@Serializable
enum class AnalyticsEntryPoint {
    @SerialName("tab")
    TAB,

    @SerialName("card")
    CARD,

    @SerialName("notification")
    NOTIFICATION,

    @SerialName("deep_link")
    DEEP_LINK,

    @SerialName("recommendation")
    RECOMMENDATION,

    @SerialName("route")
    ROUTE,
}

@Serializable
enum class DiscoveryKind {
    @SerialName("featured")
    FEATURED,

    @SerialName("organic")
    ORGANIC,

    @SerialName("search")
    SEARCH,

    @SerialName("editor")
    EDITOR,

    @SerialName("event")
    EVENT,

    @SerialName("gallery")
    GALLERY,

    @SerialName("nearby")
    NEARBY,

    @SerialName("saved")
    SAVED,

    @SerialName("notification")
    NOTIFICATION,

    @SerialName("recommendation")
    RECOMMENDATION,

    @SerialName("route")
    ROUTE,
}

@Serializable
enum class PositionBucket {
    @SerialName("unranked")
    UNRANKED,

    @SerialName("top_three")
    TOP_THREE,

    @SerialName("four_to_ten")
    FOUR_TO_TEN,

    @SerialName("after_ten")
    AFTER_TEN,
}

@Serializable
enum class AnalyticsRouteMode {
    @SerialName("neighborhood")
    NEIGHBORHOOD,

    @SerialName("for_you")
    FOR_YOU,

    @SerialName("closing_soon")
    CLOSING_SOON,

    @SerialName("saved")
    SAVED,
}

@Serializable
enum class DistanceBand {
    @SerialName("under_two_km")
    UNDER_TWO_KM,

    @SerialName("two_to_five_km")
    TWO_TO_FIVE_KM,

    @SerialName("over_five_km")
    OVER_FIVE_KM,
}

@Serializable
enum class DurationBand {
    @SerialName("under_two_hours")
    UNDER_TWO_HOURS,

    @SerialName("two_to_four_hours")
    TWO_TO_FOUR_HOURS,

    @SerialName("over_four_hours")
    OVER_FOUR_HOURS,
}

@Serializable
enum class AnalyticsIntentAction {
    @SerialName("bookmark_add")
    BOOKMARK_ADD,

    @SerialName("bookmark_remove")
    BOOKMARK_REMOVE,

    @SerialName("share")
    SHARE_SHEET_OPENED,

    @SerialName("open_maps")
    OPEN_MAPS,

    @SerialName("ticket")
    TICKET,

    @SerialName("contact")
    CONTACT,

    @SerialName("visit_recorded")
    VISIT_RECORDED,

    @SerialName("gallery_open")
    GALLERY_OPEN,

    @SerialName("follow_gallery")
    FOLLOW_GALLERY,
}

@Serializable
enum class MobileAnalyticsEventName {
    @SerialName("surface_viewed")
    SURFACE_VIEWED,

    @SerialName("exhibition_impression")
    EXHIBITION_IMPRESSION,

    @SerialName("exhibition_opened")
    EXHIBITION_OPENED,

    @SerialName("exhibition_intent")
    EXHIBITION_INTENT,

    @SerialName("recommendations_shown")
    RECOMMENDATIONS_SHOWN,

    @SerialName("route_created")
    ROUTE_CREATED,

    @SerialName("route_started")
    ROUTE_STARTED,
}

/** Flat allowlisted event shape. Sensitive or free-form dimensions cannot be represented. */
@ConsistentCopyVisibility
@Serializable
data class MobileAnalyticsEvent private constructor(
    @SerialName("event_id") val eventId: String,
    @SerialName("occurred_on") val occurredOn: LocalDate,
    val platform: AnalyticsPlatform,
    @SerialName("app_major") val appMajor: Int,
    @SerialName("event_name") val eventName: MobileAnalyticsEventName,
    val surface: AnalyticsSurface? = null,
    @SerialName("entry_point") val entryPoint: AnalyticsEntryPoint? = null,
    @SerialName("exhibition_id") val exhibitionId: String? = null,
    @SerialName("discovery_kind") val discoveryKind: DiscoveryKind? = null,
    @SerialName("position_bucket") val positionBucket: PositionBucket? = null,
    @SerialName("result_count") val resultCount: Int? = null,
    val action: AnalyticsIntentAction? = null,
    @SerialName("route_mode") val routeMode: AnalyticsRouteMode? = null,
    @SerialName("stop_count") val stopCount: Int? = null,
    @SerialName("distance_band") val distanceBand: DistanceBand? = null,
    @SerialName("duration_band") val durationBand: DurationBand? = null,
) {
    init {
        require(UUID_PATTERN.matches(eventId)) { "eventId must be a UUID" }
        require(appMajor in 1..999) { "appMajor must be between 1 and 999" }
        exhibitionId?.let { require(validIdentifier(it)) { "exhibitionId is invalid" } }
        require(validShape()) { "analytics dimensions do not match eventName" }
    }

    private fun validShape(): Boolean =
        when (eventName) {
            MobileAnalyticsEventName.SURFACE_VIEWED -> {
                surface != null &&
                    entryPoint != null &&
                    exhibitionId == null &&
                    discoveryKind == null &&
                    positionBucket == null &&
                    resultCount == null &&
                    action == null &&
                    routeMode == null &&
                    stopCount == null &&
                    distanceBand == null &&
                    durationBand == null
            }

            MobileAnalyticsEventName.EXHIBITION_OPENED,
            MobileAnalyticsEventName.EXHIBITION_IMPRESSION,
            -> {
                exhibitionId != null &&
                    surface != null &&
                    discoveryKind != null &&
                    positionBucket != null &&
                    entryPoint == null &&
                    resultCount == null &&
                    action == null &&
                    routeMode == null &&
                    stopCount == null &&
                    distanceBand == null &&
                    durationBand == null
            }

            MobileAnalyticsEventName.EXHIBITION_INTENT -> {
                exhibitionId != null &&
                    surface != null &&
                    action != null &&
                    entryPoint == null &&
                    discoveryKind == null &&
                    positionBucket == null &&
                    resultCount == null &&
                    routeMode == null &&
                    stopCount == null &&
                    distanceBand == null &&
                    durationBand == null
            }

            MobileAnalyticsEventName.RECOMMENDATIONS_SHOWN -> {
                surface != null &&
                    discoveryKind == DiscoveryKind.RECOMMENDATION &&
                    positionBucket == null &&
                    resultCount in 0..20 &&
                    entryPoint == null &&
                    exhibitionId == null &&
                    action == null &&
                    routeMode == null &&
                    stopCount == null &&
                    distanceBand == null &&
                    durationBand == null
            }

            MobileAnalyticsEventName.ROUTE_CREATED,
            MobileAnalyticsEventName.ROUTE_STARTED,
            -> {
                routeMode != null &&
                    stopCount in 2..5 &&
                    distanceBand != null &&
                    durationBand != null &&
                    surface == null &&
                    entryPoint == null &&
                    exhibitionId == null &&
                    discoveryKind == null &&
                    positionBucket == null &&
                    resultCount == null &&
                    action == null
            }
        }

    companion object {
        fun surfaceViewed(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            surface: AnalyticsSurface,
            entryPoint: AnalyticsEntryPoint,
        ) = MobileAnalyticsEvent(
            eventId,
            occurredOn,
            platform,
            appMajor,
            MobileAnalyticsEventName.SURFACE_VIEWED,
            surface,
            entryPoint,
        )

        fun exhibitionOpened(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            exhibitionId: String,
            surface: AnalyticsSurface,
            discoveryKind: DiscoveryKind,
            positionBucket: PositionBucket,
        ) = MobileAnalyticsEvent(
            eventId = eventId,
            occurredOn = occurredOn,
            platform = platform,
            appMajor = appMajor,
            eventName = MobileAnalyticsEventName.EXHIBITION_OPENED,
            surface = surface,
            exhibitionId = exhibitionId,
            discoveryKind = discoveryKind,
            positionBucket = positionBucket,
        )

        fun exhibitionImpression(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            exhibitionId: String,
            surface: AnalyticsSurface,
            discoveryKind: DiscoveryKind,
            positionBucket: PositionBucket,
        ) = MobileAnalyticsEvent(
            eventId = eventId,
            occurredOn = occurredOn,
            platform = platform,
            appMajor = appMajor,
            eventName = MobileAnalyticsEventName.EXHIBITION_IMPRESSION,
            surface = surface,
            exhibitionId = exhibitionId,
            discoveryKind = discoveryKind,
            positionBucket = positionBucket,
        )

        fun exhibitionIntent(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            exhibitionId: String,
            surface: AnalyticsSurface,
            action: AnalyticsIntentAction,
        ) = MobileAnalyticsEvent(
            eventId = eventId,
            occurredOn = occurredOn,
            platform = platform,
            appMajor = appMajor,
            eventName = MobileAnalyticsEventName.EXHIBITION_INTENT,
            surface = surface,
            exhibitionId = exhibitionId,
            action = action,
        )

        fun recommendationsShown(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            surface: AnalyticsSurface,
            resultCount: Int,
        ) = MobileAnalyticsEvent(
            eventId = eventId,
            occurredOn = occurredOn,
            platform = platform,
            appMajor = appMajor,
            eventName = MobileAnalyticsEventName.RECOMMENDATIONS_SHOWN,
            surface = surface,
            discoveryKind = DiscoveryKind.RECOMMENDATION,
            resultCount = resultCount,
        )

        fun routeCreated(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            mode: AnalyticsRouteMode,
            stopCount: Int,
            distanceBand: DistanceBand,
            durationBand: DurationBand,
        ) = MobileAnalyticsEvent(
            eventId = eventId,
            occurredOn = occurredOn,
            platform = platform,
            appMajor = appMajor,
            eventName = MobileAnalyticsEventName.ROUTE_CREATED,
            routeMode = mode,
            stopCount = stopCount,
            distanceBand = distanceBand,
            durationBand = durationBand,
        )

        fun routeStarted(
            eventId: String,
            occurredOn: LocalDate,
            platform: AnalyticsPlatform,
            appMajor: Int,
            mode: AnalyticsRouteMode,
            stopCount: Int,
            distanceBand: DistanceBand,
            durationBand: DurationBand,
        ) = MobileAnalyticsEvent(
            eventId = eventId,
            occurredOn = occurredOn,
            platform = platform,
            appMajor = appMajor,
            eventName = MobileAnalyticsEventName.ROUTE_STARTED,
            routeMode = mode,
            stopCount = stopCount,
            distanceBand = distanceBand,
            durationBand = durationBand,
        )
    }
}

/** Bounded request batch accepted by the mobile analytics boundary. */
@Serializable
data class MobileAnalyticsBatch(
    val events: List<MobileAnalyticsEvent>,
) {
    init {
        require(events.size in 1..MOBILE_ANALYTICS_MAX_BATCH_SIZE) {
            "events must contain 1 to $MOBILE_ANALYTICS_MAX_BATCH_SIZE rows"
        }
        require(events.map(MobileAnalyticsEvent::eventId).distinct().size == events.size) {
            "event IDs must be unique within a batch"
        }
    }
}

/** Purgeable queued event with no device, account, or session identity. */
@Serializable
data class QueuedMobileAnalyticsEvent(
    val event: MobileAnalyticsEvent,
    val queuedAt: Instant,
)

/** Applies the seven-day retention window, stable ordering, and 200-row cap. */
fun normalizeAnalyticsQueue(
    entries: List<QueuedMobileAnalyticsEvent>,
    now: Instant,
    maxSize: Int = MOBILE_ANALYTICS_MAX_QUEUE_SIZE,
): List<QueuedMobileAnalyticsEvent> {
    require(maxSize >= 0) { "maxSize must not be negative" }
    return entries
        .map { entry -> if (entry.queuedAt > now) entry.copy(queuedAt = now) else entry }
        .filter { it.queuedAt >= now - MOBILE_ANALYTICS_QUEUE_TTL }
        .distinctBy { it.event.eventId }
        .sortedWith(compareBy({ it.queuedAt }, { it.event.eventId }))
        .takeLast(maxSize)
}

private fun validIdentifier(value: String): Boolean =
    value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

private val UUID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
internal val MOBILE_ANALYTICS_QUEUE_TTL = 7.days
internal const val MOBILE_ANALYTICS_MAX_BATCH_SIZE = 20
internal const val MOBILE_ANALYTICS_MAX_QUEUE_SIZE = 200
