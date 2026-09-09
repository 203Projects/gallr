package com.gallr.app

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

interface ShareHandler {
    fun shareApp()

    suspend fun shareExhibition(
        exhibition: Exhibition,
        lang: AppLanguage,
    ): Result<Unit>
}

expect fun createShareHandler(): ShareHandler
