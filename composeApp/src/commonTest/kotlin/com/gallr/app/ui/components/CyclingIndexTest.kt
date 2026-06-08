package com.gallr.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CyclingIndexTest {

    @Test
    fun next_wraps_0_to_1_to_2_to_0_for_count_3() {
        assertEquals(1, nextCyclingIndex(0, 3))
        assertEquals(2, nextCyclingIndex(1, 3))
        assertEquals(0, nextCyclingIndex(2, 3))
    }

    @Test
    fun next_returns_0_for_nonpositive_count() {
        assertEquals(0, nextCyclingIndex(0, 0))
        assertEquals(0, nextCyclingIndex(5, -1))
    }

    @Test
    fun clamp_keeps_index_in_range_on_shrink() {
        // count drops 2 -> 1 while raw still points at old index 1
        assertEquals(0, clampCyclingIndex(1, 1))
    }

    @Test
    fun clamp_handles_grow_and_normal_reads() {
        assertEquals(2, clampCyclingIndex(2, 3))
        assertEquals(0, clampCyclingIndex(3, 3)) // 3 mod 3 == 0
    }

    @Test
    fun clamp_returns_0_for_nonpositive_count() {
        assertEquals(0, clampCyclingIndex(4, 0))
        assertEquals(0, clampCyclingIndex(4, -2))
    }

    @Test
    fun clamp_normalizes_negative_raw() {
        // defensive: Int.mod never returns negative
        assertEquals(2, clampCyclingIndex(-1, 3))
    }
}
