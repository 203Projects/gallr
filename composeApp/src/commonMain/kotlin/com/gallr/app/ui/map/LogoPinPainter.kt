package com.gallr.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The gallr logo as a map pin (DESIGN.md, Map pins): the logo's ring in [ring], its opening filled with
 * [cover] cropped to fit, or white when there is no cover, and a thin white edge so the pin reads on any
 * map colour. Geometry is the logo's own 100-unit artwork (`drawable/logo.xml`).
 */
internal class LogoPinPainter(
    private val cover: Painter?,
    private val ring: Color,
) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val unit = size.width / PIN_VIEW.width
        withTransform({
            scale(unit, unit, pivot = Offset.Zero)
            translate(left = -PIN_VIEW.left, top = -PIN_VIEW.top)
        }) {
            drawPath(path = pinOutline, color = Color.White, style = Stroke(width = EDGE_UNITS * 2))
            drawPath(path = pinOutline, color = ring)
            clipPath(pinOpening) {
                drawRect(color = Color.White, topLeft = PIN_OPENING.topLeft, size = PIN_OPENING.size)
                cover?.let { drawCover(it) }
            }
        }
    }

    /** Centre-crops the cover over the opening's bounds. */
    private fun DrawScope.drawCover(cover: Painter) {
        val intrinsic = cover.intrinsicSize
        if (intrinsic == Size.Unspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) return
        val fill = max(PIN_OPENING.width / intrinsic.width, PIN_OPENING.height / intrinsic.height)
        val drawn = Size(intrinsic.width * fill, intrinsic.height * fill)
        translate(
            left = PIN_OPENING.left + (PIN_OPENING.width - drawn.width) / 2f,
            top = PIN_OPENING.top + (PIN_OPENING.height - drawn.height) / 2f,
        ) {
            with(cover) { draw(drawn) }
        }
    }
}

/** The white edge outside the ring, in artwork units. */
private const val EDGE_UNITS = 2f

/** The part of the 100-unit artwork a pin image shows: the logo plus its white edge, tip at the bottom. */
private val PIN_VIEW = Rect(left = 12f, top = 10f, right = 88f, bottom = 91f)

/** Bounds of the logo's opening, where the cover goes. */
private val PIN_OPENING = Rect(left = 24f, top = 22f, right = 76f, bottom = 82f)

private const val PIN_OUTLINE_PATH = "M 50 90 C 30 78 14 64 14 48 A 36 36 0 0 1 86 48 C 86 64 70 78 50 90 Z"
private const val PIN_OPENING_PATH = "M 50 82 C 35 71 24 60 24 48 A 26 26 0 0 1 76 48 C 76 60 65 71 50 82 Z"

private val pinOutline: Path by lazy { PathParser().parsePathString(PIN_OUTLINE_PATH).toPath() }
private val pinOpening: Path by lazy { PathParser().parsePathString(PIN_OPENING_PATH).toPath() }

/** One artwork unit on screen: the ring is 48dp across, its opening about 35dp. */
private const val PIN_UNIT_DP = 2f / 3f

/** The size a pin image is drawn at; its bottom edge is the pin's tip. */
internal val MAP_PIN_IMAGE_SIZE = DpSize((PIN_VIEW.width * PIN_UNIT_DP).dp, (PIN_VIEW.height * PIN_UNIT_DP).dp)

/** Width of the cover inside a pin, for requesting an image no larger than needed. */
internal val MAP_PIN_COVER_WIDTH = (PIN_OPENING.height * PIN_UNIT_DP).dp

/** Where a pin's corner badge sits relative to its tip: over the upper right of the ring. */
internal val MAP_PIN_BADGE_OFFSET_X = 17.dp
internal val MAP_PIN_BADGE_OFFSET_Y = (-45).dp
