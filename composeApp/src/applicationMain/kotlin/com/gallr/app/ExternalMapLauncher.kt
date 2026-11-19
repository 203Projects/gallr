package com.gallr.app

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.map.ExternalMapDestination
import com.gallr.shared.map.toExternalMapDestination

/** Thin platform boundary for handing validated coordinates to a map application. */
fun interface ExternalMapLauncher {
    fun open(destination: ExternalMapDestination): Result<Unit>
}

/** Validates the exhibition before invoking the injected platform launcher. */
fun openExhibitionInMap(
    exhibition: Exhibition,
    language: AppLanguage,
    launcher: ExternalMapLauncher,
): Result<Unit>? = exhibition.toExternalMapDestination(language)?.let(launcher::open)
