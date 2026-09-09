package com.gallr.app.ui.route

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.map.EstimatedRouteLeg
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RouteWarning
import kotlin.math.roundToInt

internal data class RouteSummaryPresentation(
    val distance: String,
    val travelTime: String,
    val totalTime: String,
)

internal fun RouteCurationMode.localizedLabel(language: AppLanguage): String =
    when (this) {
        RouteCurationMode.NEIGHBORHOOD -> if (language == AppLanguage.KO) "동네 중심" else "NEIGHBORHOOD"
        RouteCurationMode.FOR_YOU -> if (language == AppLanguage.KO) "취향 맞춤" else "FOR YOU"
        RouteCurationMode.CLOSING_SOON -> if (language == AppLanguage.KO) "곧 종료" else "CLOSING SOON"
        RouteCurationMode.SAVED -> if (language == AppLanguage.KO) "저장한 전시" else "SAVED"
    }

internal fun RouteCurationMode.localizedDescription(language: AppLanguage): String =
    when (this) {
        RouteCurationMode.NEIGHBORHOOD -> {
            if (language == AppLanguage.KO) "가까운 전시로 짧게 구성" else "A compact route through nearby exhibitions"
        }

        RouteCurationMode.FOR_YOU -> {
            if (language == AppLanguage.KO) "취향과 이동 거리를 함께 고려" else "Balances your picks with travel distance"
        }

        RouteCurationMode.CLOSING_SOON -> {
            if (language == AppLanguage.KO) "종료일이 가까운 전시 우선" else "Prioritizes exhibitions ending soon"
        }

        RouteCurationMode.SAVED -> {
            if (language == AppLanguage.KO) "저장한 전시만 사용" else "Uses only exhibitions you saved"
        }
    }

internal fun estimatedDistanceLabel(
    distanceMeters: Int,
    language: AppLanguage,
): String {
    require(distanceMeters >= 0) { "distanceMeters must not be negative" }
    val value =
        if (distanceMeters < METERS_PER_KILOMETER) {
            "${(distanceMeters / METERS_ROUNDING.toDouble()).roundToInt() * METERS_ROUNDING} M"
        } else {
            val tenths = (distanceMeters / 100.0).roundToInt()
            "${tenths / 10}.${tenths % 10} KM"
        }
    return if (language == AppLanguage.KO) "약 $value" else "~$value"
}

internal fun estimatedDurationLabel(
    minutes: Int,
    language: AppLanguage,
): String {
    require(minutes >= 0) { "minutes must not be negative" }
    val hours = minutes / MINUTES_PER_HOUR
    val remainingMinutes = minutes % MINUTES_PER_HOUR
    return when (language) {
        AppLanguage.KO -> {
            when {
                hours == 0 -> "약 ${remainingMinutes}분"
                remainingMinutes == 0 -> "약 ${hours}시간"
                else -> "약 ${hours}시간 ${remainingMinutes}분"
            }
        }

        AppLanguage.EN -> {
            when {
                hours == 0 -> "~$remainingMinutes MIN"
                remainingMinutes == 0 -> "~$hours HR"
                else -> "~$hours HR $remainingMinutes MIN"
            }
        }
    }
}

internal fun routeSummaryPresentation(
    route: ExhibitionRouteEstimate,
    language: AppLanguage,
): RouteSummaryPresentation =
    when (language) {
        AppLanguage.KO -> {
            RouteSummaryPresentation(
                distance = "예상 거리 · ${estimatedDistanceLabel(route.totalDistanceMeters, language)}",
                travelTime = "예상 이동 · ${estimatedDurationLabel(route.estimatedTravelMinutes, language)}",
                totalTime = "관람 포함 총 시간 · ${estimatedDurationLabel(route.estimatedTotalMinutes, language)}",
            )
        }

        AppLanguage.EN -> {
            RouteSummaryPresentation(
                distance = "ESTIMATED DISTANCE · ${estimatedDistanceLabel(route.totalDistanceMeters, language)}",
                travelTime = "ESTIMATED TRAVEL · ${estimatedDurationLabel(route.estimatedTravelMinutes, language)}",
                totalTime = "TOTAL WITH VISITS · ${estimatedDurationLabel(route.estimatedTotalMinutes, language)}",
            )
        }
    }

internal fun routeLegLabel(
    stopIndex: Int,
    leg: EstimatedRouteLeg,
    language: AppLanguage,
    whyThisLabel: String? = null,
): String {
    require(stopIndex >= 0) { "stopIndex must not be negative" }
    val origin =
        when (language) {
            AppLanguage.KO -> if (stopIndex == 0) "시작점에서" else "${stopIndex}번 정류장에서"
            AppLanguage.EN -> if (stopIndex == 0) "FROM START" else "FROM STOP $stopIndex"
        }
    return "$origin · ${estimatedDistanceLabel(leg.distanceMeters, language)} · " +
        estimatedDurationLabel(leg.estimatedTravelMinutes, language)
}

internal fun routeHoursLabel(
    hours: String?,
    language: AppLanguage,
): String {
    val raw = hours?.trim().orEmpty()
    if (raw.isNotEmpty()) {
        return if (language == AppLanguage.KO) "운영 시간 · $raw" else "HOURS · $raw"
    }
    return if (language == AppLanguage.KO) "운영 시간 미확인" else "HOURS NOT VERIFIED"
}

internal fun RouteWarning.localizedLabel(language: AppLanguage): String =
    when (this) {
        RouteWarning.APPROXIMATE_DISTANCE -> {
            if (language == AppLanguage.KO) {
                "예상 거리이며 길 안내 경로가 아닙니다"
            } else {
                "ESTIMATED DISTANCE — NOT TURN-BY-TURN DIRECTIONS"
            }
        }

        RouteWarning.HOURS_UNVERIFIED -> {
            if (language == AppLanguage.KO) "방문 전 운영 시간을 확인하세요" else "CHECK VENUE HOURS BEFORE YOU GO"
        }
    }

internal fun routeStopSemanticsLabel(
    stopIndex: Int,
    stopCount: Int,
    exhibition: Exhibition,
    leg: EstimatedRouteLeg,
    language: AppLanguage,
    whyThisLabel: String? = null,
): String {
    require(stopIndex in 0 until stopCount) { "stopIndex must identify a stop" }
    val number = stopIndex + 1
    val name = exhibition.localizedName(language)
    val venue = exhibition.localizedVenueName(language)
    val hours = routeHoursLabel(exhibition.hours, language)
    val routeFacts =
        when (language) {
            AppLanguage.KO -> {
                "${stopCount}개 중 ${number}번째 정류장. $name. $venue. " +
                    "${routeLegLabel(stopIndex, leg, language)}. $hours."
            }

            AppLanguage.EN -> {
                "Stop $number of $stopCount. $name. $venue. " +
                    "${routeLegLabel(stopIndex, leg, language)}. $hours."
            }
        }
    return listOf(routeFacts, whyThisLabel?.let { "$it." }.orEmpty())
        .filter(String::isNotBlank)
        .joinToString(" ")
}

internal fun routeStopMapContentDescription(
    stopIndex: Int,
    stopCount: Int,
    language: AppLanguage,
): String =
    if (language == AppLanguage.KO) {
        "${stopCount}개 중 ${stopIndex + 1}번째 정류장을 지도에서 열기"
    } else {
        "Open stop ${stopIndex + 1} of $stopCount in Maps"
    }

internal fun insufficientRouteMessage(
    requested: Int,
    available: Int,
    language: AppLanguage,
): String =
    if (language == AppLanguage.KO) {
        "${requested}개 정류장 경로에 맞는 전시가 ${available}개뿐입니다. 정류장 수를 줄이거나 다른 방식을 선택해 보세요."
    } else {
        val noun = if (available == 1) "exhibition fits" else "exhibitions fit"
        "Only $available $noun this $requested-stop route. Reduce the stops or choose another mode."
    }

internal fun routeMapOpenErrorLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "지도를 열지 못했습니다. 다시 시도해 주세요." else "Couldn’t open Maps. Please try again."

private const val METERS_PER_KILOMETER = 1_000
private const val METERS_ROUNDING = 10
private const val MINUTES_PER_HOUR = 60
