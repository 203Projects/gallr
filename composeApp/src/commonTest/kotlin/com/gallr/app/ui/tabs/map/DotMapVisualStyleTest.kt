package com.gallr.app.ui.tabs.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotMapVisualStyleTest {
    @Test
    fun `semantic states share one footprint and stay distinct from geography`() {
        assertTrue(DotMapVisualStyle.BACKGROUND_RADIUS_DP < DotMapVisualStyle.MARKER_RADIUS_DP)
        assertTrue(DotMapVisualStyle.DENSE_BACKGROUND_RADIUS_DP < DotMapVisualStyle.BACKGROUND_RADIUS_DP)
        assertEquals(DotMapVisualStyle.MARKER_RADIUS_DP, DotMapVisualStyle.UNEXPLORED_RADIUS_DP)
        assertEquals(DotMapVisualStyle.MARKER_RADIUS_DP, DotMapVisualStyle.SAVED_RADIUS_DP)
        assertEquals(DotMapVisualStyle.MARKER_RADIUS_DP, DotMapVisualStyle.VISITED_RADIUS_DP)
        assertEquals(DotMapVisualStyle.SELECTED_RADIUS_DP, DotMapVisualStyle.CURRENT_LOCATION_RADIUS_DP)
        assertTrue(DotMapVisualStyle.HALO_RADIUS_DP > DotMapVisualStyle.SELECTED_RADIUS_DP)
        assertTrue(DotMapVisualStyle.CURRENT_LOCATION_PULSE_END_RADIUS_DP > DotMapVisualStyle.HALO_RADIUS_DP)
    }
}
