package com.gallr.app.share

private const val ACCENT = 0xFFFF5400.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val DARK_PAPER_TOP_WASH = 0.88
private const val DARK_PAPER_BOTTOM_WASH = 0.92
private const val MIN_SECONDARY_CONTRAST = 4.5
private val SECONDARY_SOFTENING = listOf(0.45, 0.4, 0.35, 0.3, 0.25, 0.2, 0.15, 0.1, 0.05)

/** Resolved colours for one story card: the app theme combined with the poster palette. */
data class StoryCardColors(
    val paperTop: Int,
    val paperBottom: Int,
    val title: Int,
    val secondary: Int,
    val divider: Int,
    val frame: Int,
    val placeholder: Int,
    val statusBackground: Int,
    val qrTile: Int = WHITE,
) {
    fun statusDot(emphasized: Boolean): Int = if (emphasized) ACCENT else title
}

fun storyCardColors(
    theme: ExhibitionStoryCardPalette,
    poster: PosterPalette,
): StoryCardColors {
    val dark = theme == ExhibitionStoryCardPalette.DARK
    val paperTop: Int
    val paperBottom: Int
    val title: Int
    if (dark) {
        val tinted = poster != PosterPalette.FALLBACK
        paperTop = if (tinted) mixArgb(poster.dominant, theme.background, DARK_PAPER_TOP_WASH) else theme.background
        paperBottom =
            if (tinted) mixArgb(poster.dominant, theme.background, DARK_PAPER_BOTTOM_WASH) else theme.background
        title = theme.title
    } else {
        paperTop = poster.paperTop
        paperBottom = poster.paperBottom
        title = poster.ink
    }
    return StoryCardColors(
        paperTop = paperTop,
        paperBottom = paperBottom,
        title = title,
        secondary = softest(title, paperBottom),
        divider = (title and 0x00FFFFFF) or (0x24 shl 24),
        frame = (title and 0x00FFFFFF) or (0x14 shl 24),
        placeholder = mixArgb(paperBottom, title, 0.06),
        statusBackground = if (dark) 0x1FFFFFFF else 0xB8FFFFFF.toInt(),
    )
}

/** The lightest step from [ink] toward [paper] that still reads at 4.5:1. */
private fun softest(
    ink: Int,
    paper: Int,
): Int =
    SECONDARY_SOFTENING
        .map { mixArgb(ink, paper, it) }
        .firstOrNull { contrastRatio(it, paper) >= MIN_SECONDARY_CONTRAST }
        ?: ink

private fun contrastRatio(
    a: Int,
    b: Int,
): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
