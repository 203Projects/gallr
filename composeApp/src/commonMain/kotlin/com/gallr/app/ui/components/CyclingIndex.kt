package com.gallr.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import kotlinx.coroutines.delay

/** Next index with wrap-around. count <= 0 → 0. */
internal fun nextCyclingIndex(current: Int, count: Int): Int =
    if (count <= 0) 0 else (current + 1).mod(count)

/** Safe read: always in [0, count). Uses Int.mod (never %), so negative raw is normalized. */
internal fun clampCyclingIndex(raw: Int, count: Int): Int =
    if (count <= 0) 0 else raw.mod(count)

/**
 * Drives an auto-advancing index over [count] items. Returns a [State] whose value
 * is always in [0, count). Auto-advance:
 *  - is a no-op when [count] <= 1,
 *  - pauses when this leaves composition (so off-screen surfaces don't cycle),
 *  - is disabled when reduced motion / a screen reader is active,
 *  - wraps around, and
 *  - restarts from index 0 whenever [resetKey] changes (e.g. a manual swipe/tap).
 */
@Composable
fun rememberCyclingIndex(
    count: Int,
    intervalMillis: Long = 3500L,
    resetKey: Any? = null,
): State<Int> {
    val raw = remember { mutableIntStateOf(0) }
    val clamped = remember(count) { derivedStateOf { clampCyclingIndex(raw.intValue, count) } }
    val autoCycle = !isReduceMotionOrScreenReaderActive()
    LaunchedEffect(count, intervalMillis, resetKey, autoCycle) {
        raw.intValue = 0
        if (count <= 1 || !autoCycle) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            raw.intValue = nextCyclingIndex(raw.intValue, count)
        }
    }
    return clamped
}
