package com.gallr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.model.resolvesToDark

/** Material's shape slots, from the app's two radii: small pieces 12dp, cards and dialogs 16dp (spec 095). */
internal fun gallrShapes(): Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(GallrShapeTokens.smallRadius),
        small = RoundedCornerShape(GallrShapeTokens.smallRadius),
        medium = RoundedCornerShape(GallrShapeTokens.smallRadius),
        large = RoundedCornerShape(GallrShapeTokens.cardRadius),
        extraLarge = RoundedCornerShape(GallrShapeTokens.cardRadius),
    )

private val GallrShapes = gallrShapes()

@Composable
fun GallrTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDark = themeMode.resolvesToDark(isSystemInDarkTheme())

    MaterialTheme(
        colorScheme = if (useDark) gallrDarkColorScheme() else gallrColorScheme(),
        typography = gallrTypography(),
        shapes = GallrShapes,
        content = content,
    )
}
