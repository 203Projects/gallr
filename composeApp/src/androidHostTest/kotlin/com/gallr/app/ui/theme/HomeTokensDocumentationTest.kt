package com.gallr.app.ui.theme

import com.gallr.shared.home.HOME_ROUTES_LIMIT
import com.gallr.shared.home.HOME_ROUTE_COVER_LIMIT
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 091 T033: DESIGN.md's Home tab section states the values `HomeVisualTokens` carries, so a token change
 * without the matching DESIGN.md edit fails here rather than drifting.
 */
class HomeTokensDocumentationTest {
    @Test
    fun designMdStatesTheHomeTokenValues() {
        val section = homeTabSection()
        val phrases =
            listOf(
                "${HomeVisualTokens.cornerRadius.value.roundToInt()}dp corner radius",
                "${HomeVisualTokens.innerRadius.value.roundToInt()}dp corner radius",
                "reaches ${"%.2f".format(HomeVisualTokens.SCRIM_ALPHA)} alpha",
                "to ${"%.2f".format(HomeVisualTokens.SCRIM_PRESSED_ALPHA)} alpha",
                "over ${(HomeVisualTokens.SCRIM_LEAD * 100).roundToInt()}% of the cover",
                "${(HomeVisualTokens.SCRIM_START * 100).roundToInt()}% from the top",
                "${HomeVisualTokens.dotSize.value.roundToInt()}dp dots " +
                    "${HomeVisualTokens.dotSpacing.value.roundToInt()}dp apart",
                "${HomeVisualTokens.activeDotWidth.value.roundToInt()}dp pill",
                "at most ${numberWord(HomeVisualTokens.DOT_LIMIT)} cards",
                "up to ${numberWord(HOME_ROUTES_LIMIT)} public routes",
                "up to ${numberWord(HOME_ROUTE_COVER_LIMIT)} stop covers",
            )
        val missing = phrases.filterNot { it in section }

        assertEquals(emptyList(), missing, "DESIGN.md Home tab section lacks these token values")
    }

    @Test
    fun theDecisionsLogRecordsOptionAAndItsRecomposition() {
        val rows = designMd().lines().filter { it.startsWith("| 2026-10-1") && "Home tab" in it && "Option A" in it }

        assertEquals(
            listOf("2026-10-11", "2026-10-12"),
            rows.map { it.substring(2, 12) },
            "Decisions Log rows for the Home tab's Option A move and its recomposition",
        )
    }

    private fun designMd(): String {
        val file = File(System.getProperty("user.dir"), "../DESIGN.md")
        require(file.isFile) { "DESIGN.md not found from ${System.getProperty("user.dir")}" }
        return file.readText()
    }

    /** The Home tab section's lines joined on single spaces, so a phrase wrapped across lines still matches. */
    private fun homeTabSection(): String {
        val lines = designMd().lines()
        val start = lines.indexOfFirst { it.startsWith("### Home tab") }
        require(start >= 0) { "DESIGN.md has no Home tab section" }
        val body = lines.drop(start + 1).takeWhile { !it.startsWith("### ") }
        return body.joinToString(" ") { it.trim() }
    }

    private fun numberWord(value: Int): String =
        when (value) {
            3 -> "three"
            4 -> "four"
            10 -> "ten"
            else -> value.toString()
        }
}
