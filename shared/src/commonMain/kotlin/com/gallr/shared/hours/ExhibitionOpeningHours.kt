package com.gallr.shared.hours

import com.gallr.shared.data.model.Exhibition

/**
 * The weekly hours every schedule and disclosure uses: staff-confirmed hours when the catalogue carries them,
 * otherwise a reading of the venue's free-text listing.
 */
fun Exhibition.weeklyOpeningHours(): WeeklyOpeningHours = confirmedHours ?: parseOpeningHours(hours)
