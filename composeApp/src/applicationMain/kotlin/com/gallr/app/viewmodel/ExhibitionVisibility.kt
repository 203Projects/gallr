package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

internal const val UPCOMING_VISIBILITY_DAYS = 14

internal fun Exhibition.isVisibleInCatalog(today: LocalDate): Boolean =
    closingDate >= today &&
        openingDate <= today.plus(UPCOMING_VISIBILITY_DAYS, DateTimeUnit.DAY)
