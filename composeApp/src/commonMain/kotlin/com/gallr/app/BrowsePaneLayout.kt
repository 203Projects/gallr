package com.gallr.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

/** A separating fold or an occluding hinge, in window coordinates. */
internal data class BrowseFold(
    val bounds: IntRect,
    val vertical: Boolean,
)

internal data class BrowsePaneLimits(
    val expandedWidth: Int,
    val sideWidth: Int,
    val stackHeight: Int,
    val masterWidths: IntRange,
)

/** Pane rectangles are relative to the content viewport. */
internal data class BrowsePaneFrames(
    val primary: IntRect,
    val master: IntRect? = null,
)

internal fun browsePaneFrames(
    viewport: IntRect,
    folds: List<BrowseFold>,
    detailSupportsSplit: Boolean,
    limits: BrowsePaneLimits,
): BrowsePaneFrames {
    val wide =
        detailSupportsSplit && viewport.width >= limits.expandedWidth &&
            viewport.height >= limits.stackHeight
    separatingRegions(viewport, folds)?.let { (first, second) ->
        val hasRoom =
            minOf(first.width, second.width) >= limits.sideWidth &&
                minOf(first.height, second.height) >= limits.stackHeight
        return if (wide && hasRoom) {
            BrowsePaneFrames(primary = second, master = first)
        } else {
            BrowsePaneFrames(primary = maxOf(first, second, compareBy { it.width.toLong() * it.height }))
        }
    }

    val full = IntRect(0, 0, viewport.width, viewport.height)
    if (!wide) return BrowsePaneFrames(full)

    val masterWidth =
        (viewport.width * MASTER_FRACTION)
            .roundToInt()
            .coerceIn(limits.masterWidths)
            .coerceAtMost(viewport.width - limits.sideWidth)
    return BrowsePaneFrames(
        primary = IntRect(masterWidth, 0, full.right, full.bottom),
        master = IntRect(0, 0, masterWidth, full.bottom),
    )
}

internal fun separatingRegions(
    viewport: IntRect,
    folds: List<BrowseFold>,
): Pair<IntRect, IntRect>? =
    folds.firstNotNullOfOrNull { (bounds, vertical) ->
        if (vertical) {
            if (bounds.top > viewport.top || bounds.bottom < viewport.bottom ||
                bounds.left <= viewport.left || bounds.right >= viewport.right
            ) {
                null
            } else {
                IntRect(0, 0, bounds.left - viewport.left, viewport.height) to
                    IntRect(bounds.right - viewport.left, 0, viewport.width, viewport.height)
            }
        } else {
            if (bounds.left > viewport.left || bounds.right < viewport.right ||
                bounds.top <= viewport.top || bounds.bottom >= viewport.bottom
            ) {
                null
            } else {
                IntRect(0, 0, viewport.width, bounds.top - viewport.top) to
                    IntRect(0, bounds.bottom - viewport.top, viewport.width, viewport.height)
            }
        }
    }

internal fun browseTabFrames(
    viewport: IntRect,
    folds: List<BrowseFold>,
    tabCount: Int,
    minTabWidth: Int,
): List<IntRect> {
    val full = IntRect(0, 0, viewport.width, viewport.height)
    val (left, right) =
        separatingRegions(viewport, folds.filter(BrowseFold::vertical))
            ?: return evenlySpacedTabs(full, tabCount)
    val available = left.width + right.width
    val largest = if (left.width >= right.width) left else right
    if (available < tabCount * minTabWidth) return evenlySpacedTabs(largest, tabCount)
    val minLeftTabs = (tabCount - right.width / minTabWidth).coerceAtLeast(0)
    val maxLeftTabs = (left.width / minTabWidth).coerceAtMost(tabCount)
    if (minLeftTabs > maxLeftTabs) return evenlySpacedTabs(largest, tabCount)
    val leftTabs =
        (tabCount.toDouble() * left.width / available)
            .roundToInt()
            .coerceIn(minLeftTabs, maxLeftTabs)
    return evenlySpacedTabs(left, leftTabs) + evenlySpacedTabs(right, tabCount - leftTabs)
}

private fun evenlySpacedTabs(
    frame: IntRect,
    count: Int,
): List<IntRect> =
    List(count) { index ->
        IntRect(
            left = frame.left + frame.width * index / count,
            top = frame.top,
            right = frame.left + frame.width * (index + 1) / count,
            bottom = frame.bottom,
        )
    }

internal val AppTab.supportsBrowseMaster: Boolean
    get() = this == AppTab.LIST || this == AppTab.MAP

internal val AppDestination?.supportsBrowseSplit: Boolean
    get() =
        when (this) {
            is AppDestination.ExhibitionDetail,
            is AppDestination.GalleryDetail,
            is AppDestination.EditorDetail,
            is AppDestination.EventDetail,
            -> true

            else -> false
        }

@Composable
internal expect fun observeBrowseFolds(): List<BrowseFold>

private const val MASTER_FRACTION = 0.43
