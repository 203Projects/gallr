package com.gallr.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Monochrome base palette ────────────────────────────────────────────────────
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val OffWhite = Color(0xFFF5F5F5)          // muted surface
private val SecondaryGray = Color(0xFF525252)      // secondary text
private val BorderLight = Color(0xFFE5E5E5)        // hairline dividers

// ── Dark palette ─────────────────────────────────────────────────────────────
private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkSurfaceVariant = Color(0xFF2C2C2C)
private val DarkOnBackground = Color(0xFFE0E0E0)
private val DarkOnSurfaceVariant = Color(0xFFA0A0A0)
private val DarkBorder = Color(0xFF404040)
private val DarkBorderSubtle = Color(0xFF333333)

// ── Accent (single, intentional — use only for the three permitted roles below) ─
// Rule: NEVER for backgrounds, large surfaces, decoration, or text on small targets.
private val Accent = Color(0xFFFF5400)

/**
 * Semantic accent tokens.
 *
 * All three roles resolve to the same #FF5400 value.
 * Named aliases make intent explicit and prevent accidental overuse.
 */
object GallrAccent {
    /** Fill color for primary call-to-action buttons. */
    val ctaPrimary: Color = Accent

    /** Indicator color for the active tab and selected filter chips. */
    val activeIndicator: Color = Accent

    /** Color shift for immediate pressed/active feedback on primary controls. */
    val interactionFeedback: Color = Accent
}

fun gallrColorScheme() = lightColorScheme(
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = OffWhite,
    onSurfaceVariant = SecondaryGray,
    primary = Black,
    onPrimary = White,
    primaryContainer = Black,
    onPrimaryContainer = White,
    secondary = Black,
    onSecondary = White,
    secondaryContainer = OffWhite,
    onSecondaryContainer = Black,
    tertiary = SecondaryGray,
    onTertiary = White,
    error = Black,
    onError = White,
    outline = Black,               // card borders (1dp)
    outlineVariant = BorderLight,  // hairline dividers (1dp subtle)
    scrim = Black,
    inverseSurface = Black,
    inverseOnSurface = White,
    inversePrimary = White,
)

fun gallrDarkColorScheme() = darkColorScheme(
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    primary = DarkOnBackground,
    onPrimary = DarkBackground,
    primaryContainer = DarkSurface,
    onPrimaryContainer = DarkOnBackground,
    secondary = DarkOnBackground,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnBackground,
    tertiary = DarkOnSurfaceVariant,
    onTertiary = DarkBackground,
    error = DarkOnBackground,
    onError = DarkBackground,
    outline = DarkBorder,
    outlineVariant = DarkBorderSubtle,
    scrim = Black,
    inverseSurface = DarkOnBackground,
    inverseOnSurface = DarkBackground,
    inversePrimary = DarkBackground,
)
