package com.gallr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 095: Home's two corner radii are the app's, and the Material theme shapes follow them. */
class GallrShapeTokensTest {
    @Test
    fun theAppTakesHomesRadii() {
        assertEquals(16.dp, GallrShapeTokens.cardRadius)
        assertEquals(12.dp, GallrShapeTokens.smallRadius)
        assertEquals(GallrShapeTokens.cardRadius, HomeVisualTokens.cornerRadius)
        assertEquals(GallrShapeTokens.smallRadius, HomeVisualTokens.innerRadius)
    }

    @Test
    fun theMaterialShapesFollowTheTokens() {
        val shapes = gallrShapes()

        assertEquals(RoundedCornerShape(12.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), shapes.small)
        assertEquals(RoundedCornerShape(12.dp), shapes.medium)
        assertEquals(RoundedCornerShape(16.dp), shapes.large)
        assertEquals(RoundedCornerShape(16.dp), shapes.extraLarge)
    }

    @Test
    fun sheetsRoundOnlyTheirTopCorners() {
        assertEquals(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), GallrShapeTokens.sheetTop)
    }
}
