package com.gallr.app.ui.tabs.map

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec 097 research R2: pins spread onto a 52 × 56 brick lattice from zoom 13. Sizes here are in pixels at 1 px/dp. */
class MapPinArrangementTest {
    private val column = 52f
    private val row = 56f
    private val grouping = 28f

    private fun arrange(
        candidates: List<PinVisualCandidate>,
        zoom: Double = PIN_SPREAD_FROM_ZOOM,
    ) = arrangeMapPins(
        candidates = candidates,
        zoom = zoom,
        groupingThresholdPx = grouping,
        columnPx = column,
        rowPx = row,
    )

    private fun pin(
        id: String,
        x: Float,
        y: Float,
    ) = PinVisualCandidate(id, xPx = x, yPx = y)

    private fun assertNoOverlap(pins: List<ArrangedMapPin>) {
        pins.forEachIndexed { i, a ->
            pins.drop(i + 1).forEach { b ->
                assertFalse(
                    pinsOverlap(a.xPx - b.xPx, a.yPx - b.yPx, column, row),
                    "${a.ids} at (${a.xPx}, ${a.yPx}) overlaps ${b.ids} at (${b.xPx}, ${b.yPx})",
                )
            }
        }
    }

    @Test
    fun pinsFarApartStayExactlyOnTheirPlaces() {
        val arranged = arrange(listOf(pin("a", 100f, 100f), pin("b", 300f, 100f)))

        assertEquals(listOf(listOf("a"), listOf("b")), arranged.map { it.ids })
        assertEquals(listOf(100f, 300f), arranged.map { it.xPx })
        assertTrue(arranged.none { it.isMoved })
    }

    @Test
    fun aClosePairSpreadsAroundItsCentroid() {
        val arranged = arrange(listOf(pin("a", 100f, 100f), pin("b", 110f, 100f)))

        assertEquals(2, arranged.size)
        assertTrue(arranged.all { it.ids.size == 1 })
        assertNoOverlap(arranged)
        arranged.forEach { assertTrue(hypot(it.xPx - 105f, it.yPx - 100f) <= column, "within one pin width") }
        assertEquals(105f, arranged.map { it.xPx }.average().toFloat(), 0.01f)
        assertEquals(100f, arranged.map { it.yPx }.average().toFloat(), 0.01f)
    }

    @Test
    fun threePinsAtOnePlaceKeepOneOnThePlace() {
        val arranged = arrange(listOf(pin("a", 200f, 200f), pin("b", 200f, 200f), pin("c", 200f, 200f)))

        assertEquals(3, arranged.size)
        assertNoOverlap(arranged)
        assertEquals(1, arranged.count { it.xPx == 200f && it.yPx == 200f })
        assertTrue(arranged.all { it.ids.size == 1 })
    }

    @Test
    fun eightPinsAtOnePlaceStayWithinTwoPinWidthsAndArrangeTheSameWayTwice() {
        val candidates = (1..8).map { pin("e$it", 400f, 400f) }

        val first = arrange(candidates)
        val second = arrange(candidates)

        assertEquals(8, first.size)
        assertNoOverlap(first)
        first.forEach { assertTrue(hypot(it.xPx - 400f, it.yPx - 400f) <= 2 * column, "${it.ids} too far") }
        assertEquals(first, second)
    }

    @Test
    fun twoGroupsWhoseSpreadPinsWouldCollideAreArrangedTogether() {
        // Two pairs 70 px apart: each pair spreads 26 px either side, so their inner pins would be 18 px apart.
        val arranged =
            arrange(
                listOf(pin("a", 100f, 100f), pin("b", 100f, 100f), pin("c", 170f, 100f), pin("d", 170f, 100f)),
            )

        assertEquals(4, arranged.size)
        assertNoOverlap(arranged)
    }

    @Test
    fun belowTheSpreadZoomClosePinsShareOneCountPin() {
        val candidates = listOf(pin("a", 100f, 100f), pin("b", 110f, 100f))

        val grouped = arrange(candidates, zoom = 12.9)
        val spread = arrange(candidates, zoom = 13.0)

        assertEquals(listOf(listOf("a", "b")), grouped.map { it.ids })
        assertEquals(105f, grouped.single().xPx)
        assertFalse(grouped.single().isMoved)
        assertEquals(2, spread.size)
    }

    @Test
    fun overlapIsAnEllipseOfOnePinWidthAndHeight() {
        assertTrue(pinsOverlap(0f, 0f, column, row))
        assertTrue(pinsOverlap(51f, 0f, column, row))
        assertFalse(pinsOverlap(52f, 0f, column, row))
        assertTrue(pinsOverlap(0f, 55f, column, row))
        assertFalse(pinsOverlap(26f, 56f, column, row))
    }

    @Test
    fun countPinAtOnePlaceZoomsToTheSpreadZoomOnIt() {
        val target =
            countPinCameraTarget(
                memberPoints = listOf(120f to 300f, 120f to 300f, 120f to 300f),
                currentZoom = 11.8,
                viewportWidthPx = 400f,
                viewportHeightPx = 800f,
            )

        assertEquals(120f, target.centerXPx)
        assertEquals(300f, target.centerYPx)
        assertEquals(PIN_SPREAD_FROM_ZOOM, target.zoom)
    }

    @Test
    fun aTightCountPinZoomsNoCloserThanOneLevelPastTheSpreadZoom() {
        // Members span 10% of the view: fitting them into 60% would be log2(6) ≈ 2.6 levels, capped at 14.
        val target =
            countPinCameraTarget(
                memberPoints = listOf(100f to 400f, 140f to 400f),
                currentZoom = 11.8,
                viewportWidthPx = 400f,
                viewportHeightPx = 800f,
            )

        assertEquals(120f, target.centerXPx)
        assertEquals(PIN_SPREAD_FROM_ZOOM + 1, target.zoom)
    }

    @Test
    fun aWideCountPinFitsItsMembersIntoMostOfTheViewButNotBelowTheSpreadZoom() {
        // Members span 80% of the width: fitting into 60% is 12.5 + log2(0.75) ≈ 12.08, raised to 13.
        val target =
            countPinCameraTarget(
                memberPoints = listOf(40f to 400f, 360f to 400f),
                currentZoom = 12.5,
                viewportWidthPx = 400f,
                viewportHeightPx = 800f,
            )

        assertEquals(PIN_SPREAD_FROM_ZOOM, target.zoom)
    }

    @Test
    fun countPinZoomNeverExceedsTheMapMaximum() {
        val target =
            countPinCameraTarget(
                memberPoints = listOf(100f to 400f, 100f to 400f),
                currentZoom = MAP_MAX_ZOOM,
                viewportWidthPx = 400f,
                viewportHeightPx = 800f,
            )

        assertTrue(target.zoom <= MAP_MAX_ZOOM)
    }

    @Test
    fun emptyInputGivesNoPins() {
        assertEquals(emptyList(), arrange(emptyList()))
    }
}
