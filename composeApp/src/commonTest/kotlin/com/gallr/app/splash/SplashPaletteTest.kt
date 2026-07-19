package com.gallr.app.splash

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SplashPaletteTest {

    @Test
    fun light_system_palette_matches_native_launch_assets() {
        val palette = splashPalette(isSystemDark = false)

        assertEquals(Color(0xFFFFFFFF), palette.background)
        assertEquals(Color(0xFF000000), palette.logo)
    }

    @Test
    fun dark_system_palette_matches_native_launch_assets() {
        val palette = splashPalette(isSystemDark = true)

        assertEquals(Color(0xFF121212), palette.background)
        assertEquals(Color(0xFFE0E0E0), palette.logo)
    }
}
