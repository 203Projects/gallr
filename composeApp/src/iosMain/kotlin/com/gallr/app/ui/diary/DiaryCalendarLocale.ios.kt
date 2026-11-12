package com.gallr.app.ui.diary

import androidx.compose.material3.CalendarLocale
import com.gallr.shared.data.model.AppLanguage
import platform.Foundation.NSLocale

internal actual fun diaryCalendarLocale(language: AppLanguage): CalendarLocale =
    when (language) {
        AppLanguage.KO -> NSLocale(localeIdentifier = "ko_KR")
        AppLanguage.EN -> NSLocale(localeIdentifier = "en_US")
    }
