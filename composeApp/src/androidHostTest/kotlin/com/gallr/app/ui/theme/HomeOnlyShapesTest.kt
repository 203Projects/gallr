package com.gallr.app.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 091 FR-001 / SC-006: the Home visual tokens and rounded shapes stay inside the Home tab. Any new
 * `RoundedCornerShape(` or `HomeVisualTokens` reference outside the allowed files is a design-system violation
 * (DESIGN.md: 0dp everywhere else), so this test fails on it.
 */
class HomeOnlyShapesTest {
    @Test
    fun roundedShapesAndHomeTokensStayInsideTheHomeTab() {
        val root = File(System.getProperty("user.dir"), "src/commonMain/kotlin/com/gallr/app")
        require(root.isDirectory) { "composeApp commonMain not found from ${System.getProperty("user.dir")}" }
        val offenders =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file -> file.readText().let { ROUNDED.containsMatchIn(it) || "HomeVisualTokens" in it } }
                .map { it.relativeTo(root).path }
                .filterNot { path -> ALLOWED.any { allowed -> path.startsWith(allowed) || path == allowed } }
                .sorted()
                .toList()

        assertEquals(emptyList(), offenders, "rounded shapes or Home tokens outside the Home tab")
    }

    private companion object {
        /** A rounded shape with any radius but the system's own 0dp. */
        val ROUNDED = Regex("RoundedCornerShape\\((?!0\\.dp\\))")

        /** The Home tab, its tokens, and one pre-existing 4dp use. */
        val ALLOWED =
            listOf(
                "ui/tabs/home/",
                "ui/theme/HomeVisualTokens.kt",
                "ui/profile/ProfileScreen.kt",
            )
    }
}
