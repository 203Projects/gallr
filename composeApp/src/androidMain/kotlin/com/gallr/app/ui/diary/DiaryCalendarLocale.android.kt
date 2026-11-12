package com.gallr.app.ui.diary

import androidx.compose.material3.CalendarLocale
import com.gallr.shared.data.model.AppLanguage
import java.util.Locale

internal actual fun diaryCalendarLocale(language: AppLanguage): CalendarLocale =
    when (language) {
        AppLanguage.KO -> Locale.KOREA
        AppLanguage.EN -> Locale.US
    }
