package com.gallr.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gallr.composeapp.generated.resources.GothicA1_Medium
import gallr.composeapp.generated.resources.Inter_Bold
import gallr.composeapp.generated.resources.Inter_Medium
import gallr.composeapp.generated.resources.Inter_Regular
import gallr.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font

// ── Font families (must be created inside a @Composable) ─────────────────────
// Inter: neo-grotesque sans-serif for Latin text.
// Gothic A1: Korean-optimized sans-serif fallback for Hangul rendering.
// Compose uses the first font family that can render a given glyph,
// so listing Inter first with Gothic A1 as fallback gives us:
//   Latin chars → Inter, Korean chars → Gothic A1 Medium.

@Composable
private fun inter() = FontFamily(
    Font(Res.font.Inter_Regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.Inter_Medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.Inter_Bold, FontWeight.Bold, FontStyle.Normal),
    // Korean fallback — Gothic A1 Medium (500) covers Normal/Medium/Bold
    Font(Res.font.GothicA1_Medium, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.GothicA1_Medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.GothicA1_Medium, FontWeight.Bold, FontStyle.Normal),
)

// ── Typography scale ──────────────────────────────────────────────────────────

@Composable
fun gallrTypography(): Typography {
    val sans = inter()

    return Typography(
        // Display — large screen titles
        displayLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            letterSpacing = (-0.025).em,
            lineHeight = 48.sp,
        ),
        displayMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = (-0.015).em,
            lineHeight = 40.sp,
        ),
        displaySmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            letterSpacing = 0.em,
            lineHeight = 32.sp,
        ),
        // Headline — section titles
        headlineLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            letterSpacing = (-0.015).em,
            lineHeight = 48.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = (-0.015).em,
            lineHeight = 40.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            letterSpacing = 0.em,
            lineHeight = 32.sp,
        ),
        // Title — card titles and list headers
        titleLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = 0.em,
            lineHeight = 32.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            letterSpacing = 0.em,
            lineHeight = 26.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            letterSpacing = 0.em,
            lineHeight = 22.sp,
        ),
        // Body — readable prose
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            letterSpacing = 0.em,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            letterSpacing = 0.em,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            letterSpacing = 0.em,
            lineHeight = 18.sp,
        ),
        // Label — tab labels, chip labels, metadata, dates
        labelLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.04.em,
            lineHeight = 18.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            letterSpacing = 0.04.em,
            lineHeight = 16.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.05.em,
            lineHeight = 16.sp,
        ),
    )
}
