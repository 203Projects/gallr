package com.gallr.shared.util

import com.gallr.shared.data.model.AppLanguage

/**
 * Derives the stacked text label shown inside the Map-tab Event FAB.
 *
 * - [AppLanguage.KO]: returns the first whitespace-separated token of [localizedName], as-is.
 * - [AppLanguage.EN]: returns the first two whitespace-separated tokens, uppercased and
 *   joined with a newline for stacked display. Returns a single uppercased token when the
 *   name has only one word. Returns the empty string for blank input.
 *
 * Examples:
 *   fabLabel("루프랩 부산 2025", KO) = "루프랩"
 *   fabLabel("Loop Lab Busan 2025", EN) = "LOOP\nLAB"
 *   fabLabel("Biennale", EN) = "BIENNALE"
 */
fun fabLabel(localizedName: String, lang: AppLanguage): String {
    val tokens = localizedName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (lang) {
        AppLanguage.KO -> tokens.firstOrNull().orEmpty()
        AppLanguage.EN -> tokens.take(2).joinToString("\n").uppercase()
    }
}
