package com.gallr.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import kotlinx.coroutines.delay

/** Next index with wrap-around. count <= 0 → 0. */
internal fun nextCyclingIndex(
    current: Int,
    count: Int,
): Int = if (count <= 0) 0 else (current + 1).mod(count)

/** Safe read: always in [0, count). Uses Int.mod (never %), so negative raw is normalized. */
internal fun clampCyclingIndex(
    raw: Int,
    count: Int,
): Int = if (count <= 0) 0 else raw.mod(count)

/**
 * Holder for a cycling carousel's current position. [index] is always in [0, count).
 * [advance] moves to the next item AND restarts the auto-cycle countdown — and it
 * works regardless of whether auto-advance is gated off (reduced motion / screen
 * reader), so manual swipe/tap navigation always stays available.
 */
class CyclingState internal constructor(
    private val rawIndex: androidx.compose.runtime.MutableIntState,
    private val onManualAdvance: () -> Unit,
    count: Int,
) {
    val index: Int by derivedStateOf { clampCyclingIndex(rawIndex.intValue, count) }

    /** Advance to the next item (wraps) and restart the auto-cycle timer. */
    fun advance() {
        onManualAdvance()
    }
}

/**
 * Drives an auto-advancing index over [count] items. Returns a [CyclingState] whose
 * [CyclingState.index] is always in [0, count). Behaviour:
 *  - auto-advance is a no-op when [count] <= 1,
 *  - auto-advance pauses when this leaves composition (so off-screen surfaces don't cycle),
 *  - auto-advance is disabled when reduced motion / a screen reader is active,
 *  - both auto-advance and [CyclingState.advance] wrap around, and
 *  - [CyclingState.advance] moves to the next item and restarts the auto-cycle countdown,
 *    and it works even when auto-advance is disabled (so manual navigation always works).
 */
@Composable
fun rememberCyclingIndex(
    count: Int,
    intervalMillis: Long = 3500L,
): CyclingState {
    val raw = remember { mutableIntStateOf(0) }
    // A manual advance bumps this; the timer effect keys on it, so it both moves the
    // index and restarts the countdown. Distinct from `count` so a list change and a
    // manual advance are independent restart triggers.
    var manualTick by remember { mutableIntStateOf(0) }
    val state =
        remember(count) {
            CyclingState(
                rawIndex = raw,
                onManualAdvance = {
                    raw.intValue = nextCyclingIndex(raw.intValue, count)
                    manualTick++
                },
                count = count,
            )
        }
    val autoCycle = !isReduceMotionOrScreenReaderActive()
    LaunchedEffect(count, intervalMillis, manualTick, autoCycle) {
        if (count <= 1 || !autoCycle) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            raw.intValue = nextCyclingIndex(raw.intValue, count)
        }
    }
    return state
}
