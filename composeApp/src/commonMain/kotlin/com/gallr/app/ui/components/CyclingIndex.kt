package com.gallr.app.ui.components

/** Next index with wrap-around. count <= 0 → 0. */
internal fun nextCyclingIndex(current: Int, count: Int): Int =
    if (count <= 0) 0 else (current + 1).mod(count)

/** Safe read: always in [0, count). Uses Int.mod (never %), so negative raw is normalized. */
internal fun clampCyclingIndex(raw: Int, count: Int): Int =
    if (count <= 0) 0 else raw.mod(count)
