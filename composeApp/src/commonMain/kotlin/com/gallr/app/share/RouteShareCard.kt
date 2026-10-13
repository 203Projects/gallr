package com.gallr.app.share

import com.gallr.app.ui.route.estimatedDistanceLabel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.map.LocalApproximateRouteLegEstimator
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.route.PersonalRoute
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * Geometry of the 1080×1920 route share card (spec 089 DR-D11), top to bottom: wordmark, "전시 동선" eyebrow, the
 * route name, the numbered drawing in a bordered square, one line per stop, "N곳 · 약 X KM", and the QR to the link.
 */
object RouteShareCardConfig {
    const val CARD_WIDTH_PX = 1080
    const val CARD_HEIGHT_PX = 1920
    const val SAFE_TOP_PX = 96
    const val SAFE_BOTTOM_PX = 88
    const val SIDE_MARGIN_PX = 56
    const val TEXT_WIDTH_PX = CARD_WIDTH_PX - SIDE_MARGIN_PX * 2
    const val BRAND_TOP_PX = SAFE_TOP_PX
    const val BRAND_HEIGHT_PX = 48
    const val EYEBROW_TOP_PX = BRAND_TOP_PX + 80
    const val EYEBROW_FONT_SIZE_PX = 28
    const val NAME_TOP_PX = EYEBROW_TOP_PX + 64
    const val NAME_FONT_SIZE_PX = 56
    const val NAME_LINE_HEIGHT_PX = 70
    const val NAME_MAX_LINES = 2
    const val DRAWING_TOP_PX = NAME_TOP_PX + NAME_LINE_HEIGHT_PX * NAME_MAX_LINES + 32
    const val DRAWING_SIZE_PX = 560
    const val DRAWING_PADDING_PX = 56
    const val DRAWING_LINE_WIDTH_PX = 4
    const val DRAWING_STOP_RADIUS_PX = 22
    const val DRAWING_STOP_FONT_SIZE_PX = 22
    const val STOPS_TOP_PX = DRAWING_TOP_PX + DRAWING_SIZE_PX + 40
    const val STOP_FONT_SIZE_PX = 30
    const val STOP_LINE_HEIGHT_PX = 44
    const val MAX_STOP_LINES = 10
    const val SUMMARY_TOP_PX = STOPS_TOP_PX + STOP_LINE_HEIGHT_PX * MAX_STOP_LINES + 16
    const val SUMMARY_FONT_SIZE_PX = 28
    const val SUMMARY_HEIGHT_PX = 36
    const val QR_MAX_PX = 240
    const val QR_TOP_PX = CARD_HEIGHT_PX - SAFE_BOTTOM_PX - QR_MAX_PX
    const val CAPTION_FONT_SIZE_PX = 24
    const val CAPTION_TOP_PX = CARD_HEIGHT_PX - SAFE_BOTTOM_PX - 36
}

/** ARGB colours of the route card: paper and ink by theme, no accent and no poster palette (DR-D11). */
data class RouteShareCardPalette(
    val paper: Int,
    val ink: Int,
    val secondary: Int,
    val frame: Int,
    val qrTile: Int,
) {
    fun colors(): List<Int> = listOf(paper, ink, secondary, frame, qrTile)

    companion object {
        val LIGHT =
            RouteShareCardPalette(
                paper = 0xFFFFFFFF.toInt(),
                ink = 0xFF000000.toInt(),
                secondary = 0xFF525252.toInt(),
                frame = 0xFF000000.toInt(),
                qrTile = 0xFFFFFFFF.toInt(),
            )
        val DARK =
            RouteShareCardPalette(
                paper = 0xFF121212.toInt(),
                ink = 0xFFE0E0E0.toInt(),
                secondary = 0xFFA0A0A0.toInt(),
                frame = 0xFFE0E0E0.toInt(),
                qrTile = 0xFFFFFFFF.toInt(),
            )

        /** QR modules are black on the white tile in both themes. */
        const val QR_MODULE = 0xFF000000.toInt()
    }
}

/** A stop's place in the drawing square: 0..1 on both axes, y growing downwards. */
data class DrawingPoint(
    val x: Double,
    val y: Double,
)

/** The words, link and drawing of one route card, built from the saved and published route. */
data class RouteShareCardContent(
    val eyebrow: String,
    val name: String,
    val stopLines: List<String>,
    val summary: String,
    val qrTarget: String,
    val caption: String,
    val drawing: List<DrawingPoint>,
    val shareDescriptor: String,
) {
    companion object {
        fun from(
            route: PersonalRoute,
            link: String,
            language: AppLanguage,
            legEstimator: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
        ): RouteShareCardContent {
            val distance =
                route.stops.zipWithNext { from, to -> legEstimator.estimate(from.point, to.point).distanceMeters }.sum()
            val count = route.stops.size
            return RouteShareCardContent(
                eyebrow = if (language == AppLanguage.KO) "전시 동선" else "EXHIBITION ROUTE",
                name = route.trimmedName,
                stopLines =
                    route.stops.mapIndexed { index, stop ->
                        val title = if (language == AppLanguage.KO) stop.nameKo else stop.nameEn.ifBlank { stop.nameKo }
                        "${(index + 1).toString().padStart(2, '0')} $title"
                    },
                summary =
                    when (language) {
                        AppLanguage.KO -> "${count}곳 · ${estimatedDistanceLabel(distance, language)}"
                        AppLanguage.EN -> "$count STOPS · ${estimatedDistanceLabel(distance, language)}"
                    },
                qrTarget = link,
                caption = if (language == AppLanguage.KO) "스캔해서 동선 보기" else "Scan to see the route",
                drawing = drawingPoints(route.stops.map { it.point.latitude to it.point.longitude }),
                shareDescriptor = route.trimmedName,
            )
        }
    }
}

data class RouteShareCardLayout(
    val nameLines: List<String>,
    val stopLines: List<String>,
)

/** Fits the name to two lines and every stop to one line, ellipsizing what does not fit. */
fun routeShareCardLayout(
    content: RouteShareCardContent,
    measureName: (String) -> Float,
    measureStop: (String) -> Float,
): RouteShareCardLayout {
    val width = RouteShareCardConfig.TEXT_WIDTH_PX.toFloat()
    return RouteShareCardLayout(
        nameLines =
            wrapMeasuredText(
                text = content.name,
                maxWidth = width,
                maxLines = RouteShareCardConfig.NAME_MAX_LINES,
                measureWidth = measureName,
            ),
        stopLines =
            content.stopLines
                .take(RouteShareCardConfig.MAX_STOP_LINES)
                .map { line -> ellipsizeMeasuredText(line, width, measureStop) },
    )
}

/** Projects the stops (Web Mercator) and fits them, keeping their shape, inside the unit square. */
private fun drawingPoints(points: List<Pair<Double, Double>>): List<DrawingPoint> {
    if (points.isEmpty()) return emptyList()
    val projected = points.map { (latitude, longitude) -> longitude to mercatorY(latitude) }
    val minX = projected.minOf { it.first }
    val maxX = projected.maxOf { it.first }
    val minY = projected.minOf { it.second }
    val maxY = projected.maxOf { it.second }
    val span = max(maxX - minX, maxY - minY)
    if (span == 0.0) return projected.map { DrawingPoint(CENTER, CENTER) }
    val offsetX = (span - (maxX - minX)) / 2
    val offsetY = (span - (maxY - minY)) / 2
    return projected.map { (x, y) ->
        DrawingPoint(
            x = (x - minX + offsetX) / span,
            // Mercator y grows northwards; the drawing's y grows downwards.
            y = 1.0 - (y - minY + offsetY) / span,
        )
    }
}

private fun mercatorY(latitudeDegrees: Double): Double = ln(tan(PI / 4 + latitudeDegrees * PI / 360)) * 180 / PI

private const val CENTER = 0.5
