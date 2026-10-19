package com.gallr.app.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 095 SC-001: Home's shapes are the app's. A square shape is allowed only where the element is square by design
 * (contracts/shape-categories.md), marked on its line or the line above with `// Square by design: <reason>`, and
 * every rounded shape takes its radius from the tokens in `ui/theme/`.
 */
class AppShapesTest {
    @Test
    fun squareShapesAppearOnlyWhereTheyAreSquareByDesign() {
        val offenders =
            sites { line -> SQUARE.containsMatchIn(line) }
                .filterNot { (_, lines, index) -> lines.markedSquare(index) }
                .map { (path, _, index) -> "$path:${index + 1}" }
                .toList()

        assertEquals(emptyList(), offenders, "square shapes without a `$MARKER` reason")
    }

    @Test
    fun cornerRadiiComeFromTheTokens() {
        val offenders =
            sites { line -> LITERAL_RADIUS.containsMatchIn(line) }
                .filterNot { (path, _, _) -> path.startsWith("ui/theme/") }
                .map { (path, _, index) -> "$path:${index + 1}" }
                .toList()

        assertEquals(emptyList(), offenders, "rounded shapes with a literal radius outside ui/theme/")
    }

    /** Every non-import source line matching [matches], as (path, all lines, index). */
    private fun sites(matches: (String) -> Boolean): Sequence<Triple<String, List<String>, Int>> {
        val root = File(System.getProperty("user.dir"), "src/commonMain/kotlin/com/gallr/app")
        require(root.isDirectory) { "composeApp commonMain not found from ${System.getProperty("user.dir")}" }
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                val path = file.relativeTo(root).path
                lines.indices
                    .asSequence()
                    .filter { index -> !lines[index].trimStart().startsWith("import ") && matches(lines[index]) }
                    .map { index -> Triple(path, lines, index) }
            }
    }

    private fun List<String>.markedSquare(index: Int): Boolean =
        MARKER in this[index] || (index > 0 && MARKER in this[index - 1])

    private companion object {
        const val MARKER = "// Square by design:"

        val SQUARE = Regex("""\bRectangleShape\b|RoundedCornerShape\(0(\.dp)?\)""")

        val LITERAL_RADIUS = Regex("""RoundedCornerShape\(\s*\d+(\.\d+)?\.dp""")
    }
}
