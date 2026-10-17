package com.gallr.app.ui.tabs.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2

/** From this zoom every exhibition has its own pin; below it, pins within [PIN_GROUPING_THRESHOLD] share a count pin. */
internal const val PIN_SPREAD_FROM_ZOOM = 13.0

/** Lattice spacing for spread pins: a pin's width, and its height, so neighbouring slots never overlap (spec 097 R2). */
internal val PIN_SPREAD_COLUMN = 52.dp
internal val PIN_SPREAD_ROW = 56.dp

/** One drawn mark: a single exhibition's pin (moved onto the lattice or not), or a count pin for several. */
internal data class ArrangedMapPin(
    val ids: List<String>,
    val xPx: Float,
    val yPx: Float,
    val isMoved: Boolean,
)

/** Two pin tips this far apart draw over each other: inside an ellipse of one pin width and height. */
internal fun pinsOverlap(
    dxPx: Float,
    dyPx: Float,
    columnPx: Float,
    rowPx: Float,
): Boolean {
    val x = dxPx / columnPx
    val y = dyPx / rowPx
    return x * x + y * y < 1f
}

/**
 * Arranges the projected pins for one map view. At or above [PIN_SPREAD_FROM_ZOOM] every candidate becomes its own
 * pin: pins that overlap nothing stay on their place, and each set of mutually overlapping pins moves onto a brick
 * lattice around its centroid, nearest slots first, each pin to the slot closest to its own place. Sets whose spread
 * pins still overlap are merged and arranged together until nothing overlaps. Below that zoom, candidates within
 * [groupingThresholdPx] share one count pin at their centroid. The result depends only on the inputs (FR-009).
 */
internal fun arrangeMapPins(
    candidates: List<PinVisualCandidate>,
    zoom: Double,
    groupingThresholdPx: Float,
    columnPx: Float,
    rowPx: Float,
): List<ArrangedMapPin> {
    if (candidates.isEmpty()) return emptyList()
    if (zoom < PIN_SPREAD_FROM_ZOOM) {
        return groupNearlyCoincidentPins(candidates, groupingThresholdPx).map { group ->
            ArrangedMapPin(ids = group.ids, xPx = group.xPx, yPx = group.yPx, isMoved = false)
        }
    }
    val lattice = SpreadLattice(columnPx, rowPx)
    var components =
        componentsOf(candidates.indices.toList()) { first, second ->
            pinsOverlap(
                candidates[first].xPx - candidates[second].xPx,
                candidates[first].yPx - candidates[second].yPx,
                columnPx,
                rowPx,
            )
        }
    var placed = components.map { component -> lattice.place(component.map(candidates::get)) }
    while (true) {
        val merged = componentsOf(placed.indices.toList()) { a, b -> placed[a].collidesWith(placed[b], columnPx, rowPx) }
        if (merged.size == placed.size) break
        components = merged.map { group -> group.flatMap(components::get) }
        placed = components.map { component -> lattice.place(component.map(candidates::get)) }
    }
    val byIndex = HashMap<Int, ArrangedMapPin>()
    placed.forEachIndexed { componentIndex, pins ->
        components[componentIndex].forEachIndexed { memberIndex, candidateIndex ->
            byIndex[candidateIndex] = pins[memberIndex]
        }
    }
    return candidates.indices.map { index -> byIndex.getValue(index) }
}

private fun List<ArrangedMapPin>.collidesWith(
    other: List<ArrangedMapPin>,
    columnPx: Float,
    rowPx: Float,
): Boolean = any { a -> other.any { b -> pinsOverlap(a.xPx - b.xPx, a.yPx - b.yPx, columnPx, rowPx) } }

/** Union-find over [items] joined by [joined]; components keep the input order of their members. */
private fun componentsOf(
    items: List<Int>,
    joined: (Int, Int) -> Boolean,
): List<List<Int>> {
    val parents = IntArray(items.size) { it }

    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }

    for (first in items.indices) {
        for (second in first + 1 until items.size) {
            if (joined(items[first], items[second])) {
                val firstRoot = root(first)
                val secondRoot = root(second)
                if (firstRoot != secondRoot) parents[secondRoot] = firstRoot
            }
        }
    }
    return items.indices.groupBy(::root).values.map { indices -> indices.map(items::get) }
}

/** A brick lattice of pin slots around a centre: rows [rowPx] apart, columns [columnPx] apart, odd rows shifted. */
private class SpreadLattice(
    private val columnPx: Float,
    private val rowPx: Float,
) {
    fun place(members: List<PinVisualCandidate>): List<ArrangedMapPin> {
        if (members.size == 1) {
            val only = members.single()
            return listOf(ArrangedMapPin(listOf(only.id), only.xPx, only.yPx, isMoved = false))
        }
        val centreX = members.map { it.xPx }.average().toFloat()
        val centreY = members.map { it.yPx }.average().toFloat()
        val slots = nearestSlots(centreX, centreY, members.size)
        val taken = BooleanArray(slots.size)
        // Closest pin-to-slot pairs first; ties go to the slot nearer the centre, then the earlier slot, then the id.
        val pairs =
            members.indices.flatMap { memberIndex ->
                slots.indices.map { slotIndex -> Triple(memberIndex, slotIndex, distance(members[memberIndex], slots[slotIndex])) }
            }
        val ordered =
            pairs.sortedWith(
                compareBy<Triple<Int, Int, Float>> { it.third }
                    .thenBy { it.second }
                    .thenBy { members[it.first].id },
            )
        val chosen = arrayOfNulls<Pair<Float, Float>>(members.size)
        for ((memberIndex, slotIndex) in ordered) {
            if (chosen[memberIndex] != null || taken[slotIndex]) continue
            chosen[memberIndex] = slots[slotIndex]
            taken[slotIndex] = true
        }
        // The slots' own centroid can sit off the members' (an even count has no centre slot), so shift them back.
        val shiftX = centreX - chosen.map { requireNotNull(it).first }.average().toFloat()
        val shiftY = centreY - chosen.map { requireNotNull(it).second }.average().toFloat()
        return members.mapIndexed { memberIndex, member ->
            val slot = requireNotNull(chosen[memberIndex])
            val x = slot.first + shiftX
            val y = slot.second + shiftY
            ArrangedMapPin(ids = listOf(member.id), xPx = x, yPx = y, isMoved = x != member.xPx || y != member.yPx)
        }
    }

    /** The [count] lattice slots nearest the centre, ordered by distance, then row, then column. */
    private fun nearestSlots(
        centreX: Float,
        centreY: Float,
        count: Int,
    ): List<Pair<Float, Float>> {
        val reach = (count / 2) + 1
        val slots = ArrayList<Pair<Float, Float>>()
        for (rowIndex in -reach..reach) {
            val shift = if (abs(rowIndex) % 2 == 1) columnPx / 2f else 0f
            for (columnIndex in -reach..reach) {
                slots += (centreX + columnIndex * columnPx + shift) to (centreY + rowIndex * rowPx)
            }
        }
        return slots
            .sortedWith(
                compareBy<Pair<Float, Float>> { hypot(it.first - centreX, it.second - centreY) }
                    .thenBy { it.second }
                    .thenBy { it.first },
            ).take(count)
    }

    private fun distance(
        member: PinVisualCandidate,
        slot: Pair<Float, Float>,
    ): Float = hypot(member.xPx - slot.first, member.yPx - slot.second)
}

/** Where the camera goes when a count pin is tapped: its members' centroid, zoomed in until they spread. */
internal data class CountPinCameraTarget(
    val centerXPx: Float,
    val centerYPx: Float,
    val zoom: Double,
)

/** Share of the view the members are fitted into, so the neighbourhood around them stays visible. */
private const val COUNT_PIN_FIT_FRACTION = 0.6

/**
 * Fits a count pin's members into [COUNT_PIN_FIT_FRACTION] of the view, held between [PIN_SPREAD_FROM_ZOOM] (where
 * they separate) and one level past it (so the view stays a neighbourhood), and never past [MAP_MAX_ZOOM].
 */
internal fun countPinCameraTarget(
    memberPoints: List<Pair<Float, Float>>,
    currentZoom: Double,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): CountPinCameraTarget {
    val centerX = memberPoints.map { it.first }.average().toFloat()
    val centerY = memberPoints.map { it.second }.average().toFloat()
    val spanX = (memberPoints.maxOf { it.first } - memberPoints.minOf { it.first }) / viewportWidthPx
    val spanY = (memberPoints.maxOf { it.second } - memberPoints.minOf { it.second }) / viewportHeightPx
    val span = maxOf(spanX, spanY).toDouble()
    // Members at one place have nothing to fit; the spread zoom is where they separate.
    val fitted = if (span > 0.0) currentZoom + log2(COUNT_PIN_FIT_FRACTION / span) else PIN_SPREAD_FROM_ZOOM
    val zoom = fitted.coerceIn(PIN_SPREAD_FROM_ZOOM, PIN_SPREAD_FROM_ZOOM + 1).coerceAtMost(MAP_MAX_ZOOM)
    return CountPinCameraTarget(centerXPx = centerX, centerYPx = centerY, zoom = zoom)
}

/** The accessible name of a drawn pin: the exhibition for a single pin, the count and the zoom action for a count pin. */
internal fun pinTargetLabel(
    singleName: String?,
    count: Int,
    language: AppLanguage,
): String =
    if (singleName != null && count == 1) {
        singleName
    } else {
        when (language) {
            AppLanguage.KO -> "전시 ${count}개 · 확대"
            AppLanguage.EN -> "$count EXHIBITIONS · ZOOM IN"
        }
    }

/** A lone single pin's target spans pin and title; one with a neighbour within that width, or a count pin, spans the pin. */
internal fun pinTargetWidth(
    isSingle: Boolean,
    nearestNeighbourDxPx: Float?,
    density: Float,
): Dp {
    if (!isSingle) return PIN_TARGET_PIN_WIDTH
    val crowded = nearestNeighbourDxPx != null && nearestNeighbourDxPx < PIN_TARGET_WIDE_WIDTH.value * density
    return if (crowded) PIN_TARGET_PIN_WIDTH else PIN_TARGET_WIDE_WIDTH
}

internal val PIN_TARGET_WIDE_WIDTH = 104.dp
internal val PIN_TARGET_PIN_WIDTH = 52.dp
