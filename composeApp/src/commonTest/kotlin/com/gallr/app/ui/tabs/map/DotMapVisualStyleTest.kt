package com.gallr.app.ui.tabs.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotMapVisualStyleTest {
    @Test
    fun `semantic states share one footprint and stay distinct from geography`() {
        assertTrue(DotMapVisualStyle.backgroundRadiusDp < DotMapVisualStyle.markerRadiusDp)
        assertTrue(DotMapVisualStyle.denseBackgroundRadiusDp < DotMapVisualStyle.backgroundRadiusDp)
        assertEquals(DotMapVisualStyle.markerRadiusDp, DotMapVisualStyle.unexploredRadiusDp)
        assertEquals(DotMapVisualStyle.markerRadiusDp, DotMapVisualStyle.savedRadiusDp)
        assertEquals(DotMapVisualStyle.markerRadiusDp, DotMapVisualStyle.visitedRadiusDp)
        assertEquals(DotMapVisualStyle.selectedRadiusDp, DotMapVisualStyle.currentLocationRadiusDp)
        assertTrue(DotMapVisualStyle.haloRadiusDp > DotMapVisualStyle.selectedRadiusDp)
        assertTrue(DotMapVisualStyle.currentLocationPulseEndRadiusDp > DotMapVisualStyle.haloRadiusDp)
    }
}
