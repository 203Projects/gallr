package com.gallr.shared.data.model

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Resolves the saved preference against the current OS appearance. */
fun ThemeMode.resolvesToDark(systemInDarkTheme: Boolean): Boolean =
    when (this) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDarkTheme
    }
