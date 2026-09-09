package com.gallr.app.analytics

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import com.gallr.shared.analytics.PositionBucket
import com.gallr.shared.analytics.positionBucket

data class RankedExhibitionExposure(
    val exhibitionId: String,
    val position: PositionBucket,
)

/** Deduplicates stable exhibition IDs for one mounted discovery-surface visit. */
class ExhibitionExposureSession {
    private var rankById = emptyMap<String, Int>()
    private val seen = mutableSetOf<String>()

    fun updateCatalogue(exhibitionIds: List<String>) {
        rankById = exhibitionIds.withIndex().associate { indexed -> indexed.value to indexed.index }
    }

    fun newlyVisible(exhibitionIds: List<String>): List<RankedExhibitionExposure> =
        exhibitionIds.mapNotNull { id ->
            val index = rankById[id] ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            RankedExhibitionExposure(id, positionBucket(index))
        }
}

fun halfVisibleStableKeys(layout: LazyListLayoutInfo): List<String> =
    layout.visibleItemsInfo.mapNotNull { item ->
        if (item.size <= 0) return@mapNotNull null
        val visibleStart = maxOf(item.offset, layout.viewportStartOffset)
        val visibleEnd = minOf(item.offset + item.size, layout.viewportEndOffset)
        val visiblePixels = (visibleEnd - visibleStart).coerceAtLeast(0)
        (item.key as? String)?.takeIf { visiblePixels * 2 >= item.size }
    }
