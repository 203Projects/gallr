package com.gallr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's two corner radii, taken from the Home tab's visual system (spec 091, made app-wide by spec 095). Every
 * rounded element uses one of them; DESIGN.md "Visual system" names which.
 */
object GallrShapeTokens {
    /** Cards, tiles, framed panels and pictures inside the margins, dialogs, and the top of bottom sheets. */
    val cardRadius: Dp = 16.dp

    /** Buttons, fields, chips, pills, counters, badges, small thumbnails, menus, snackbars and map controls. */
    val smallRadius: Dp = 12.dp

    val card: Shape get() = RoundedCornerShape(cardRadius)
    val small: Shape get() = RoundedCornerShape(smallRadius)
    val sheetTop: Shape get() = RoundedCornerShape(topStart = cardRadius, topEnd = cardRadius)
}
