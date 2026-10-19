package com.gallr.app.ui.theme

import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 095 FR-008: DESIGN.md's Visual system section states the values the app draws with, and no rule that the
 * app now contradicts survives elsewhere in the guide.
 */
class VisualSystemDocumentationTest {
    @Test
    fun designMdStatesTheAppWideTokenValues() {
        val section = section("## Visual system")
        val phrases =
            listOf(
                "${GallrShapeTokens.cardRadius.value.roundToInt()}dp corner radius",
                "${GallrShapeTokens.smallRadius.value.roundToInt()}dp corner radius",
                "reaches ${"%.2f".format(HomeVisualTokens.SCRIM_ALPHA)} alpha",
                "to ${"%.2f".format(HomeVisualTokens.SCRIM_PRESSED_ALPHA)} alpha",
                "over ${(HomeVisualTokens.SCRIM_LEAD * 100).roundToInt()}% of the cover",
                "${(HomeVisualTokens.SCRIM_START * 100).roundToInt()}% from the top",
            )
        val missing = phrases.filterNot { it in section }

        assertEquals(emptyList(), missing, "DESIGN.md Visual system section lacks these token values")
    }

    @Test
    fun theOldSquareRuleIsGone() {
        val retired =
            listOf("0dp on everything", "no rounded corners", "Sharp rectangles only", "On the Home tab only")
        val left = retired.filter { phrase -> phrase in designMdWithoutDecisionsLog() }

        assertEquals(emptyList(), left, "DESIGN.md still states the retired 0dp rule")
    }

    @Test
    fun theDecisionsLogRecordsTheAppWideMove() {
        val rows = designMd().lines().filter { it.startsWith("| 2026-10-15") && "spec 095" in it }

        assertEquals(1, rows.size, "one Decisions Log row for spec 095")
    }

    private fun designMd(): String {
        val file = File(System.getProperty("user.dir"), "../DESIGN.md")
        require(file.isFile) { "DESIGN.md not found from ${System.getProperty("user.dir")}" }
        return file.readText()
    }

    /** The guide minus its history: the Decisions Log keeps the dated 0dp rows as they were decided. */
    private fun designMdWithoutDecisionsLog(): String = designMd().substringBefore("## Decisions Log")

    /** A `## ` section's lines joined on single spaces, so a phrase wrapped across lines still matches. */
    private fun section(heading: String): String {
        val lines = designMd().lines()
        val start = lines.indexOfFirst { it.startsWith(heading) }
        require(start >= 0) { "DESIGN.md has no section starting with $heading" }
        return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.joinToString(" ") { it.trim() }
    }
}
