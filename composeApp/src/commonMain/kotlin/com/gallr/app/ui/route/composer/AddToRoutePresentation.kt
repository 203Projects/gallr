package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.AddToRouteAvailability
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.MAX_ROUTE_STOPS

/** The detail page's route button label, or null when the button is hidden (DR-D16, RO5). */
internal fun addToRouteLabel(
    availability: AddToRouteAvailability,
    language: AppLanguage,
): String? =
    when (availability) {
        AddToRouteAvailability.HIDDEN -> null
        AddToRouteAvailability.CAN_ADD -> if (language == AppLanguage.KO) "동선에 추가" else "ADD TO ROUTE"
        AddToRouteAvailability.IN_ROUTE -> if (language == AppLanguage.KO) "동선에 있음" else "IN YOUR ROUTE"
        AddToRouteAvailability.FULL -> if (language == AppLanguage.KO) "동선이 가득 찼어요" else "ROUTE IS FULL"
    }

internal fun addedToRouteMessage(
    stopCount: Int,
    language: AppLanguage,
): String =
    if (language == AppLanguage.KO) {
        "동선에 추가했어요 ($stopCount/$MAX_ROUTE_STOPS)"
    } else {
        "ADDED TO ROUTE ($stopCount/$MAX_ROUTE_STOPS)"
    }

internal fun addedToRouteAction(language: AppLanguage): String = if (language == AppLanguage.KO) "열기" else "OPEN"
