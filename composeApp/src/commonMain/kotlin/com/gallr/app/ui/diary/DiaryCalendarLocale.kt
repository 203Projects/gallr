package com.gallr.app.ui.diary

import androidx.compose.material3.CalendarLocale
import com.gallr.shared.data.model.AppLanguage

/**
 * The calendar's locale for the app's language, so its month names and weekdays follow 한국어 / English set in the
 * app rather than the device (Material's calendar reads only a platform locale).
 */
internal expect fun diaryCalendarLocale(language: AppLanguage): CalendarLocale
