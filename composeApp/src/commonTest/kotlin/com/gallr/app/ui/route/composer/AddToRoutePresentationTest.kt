package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.AddToRouteAvailability
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 089 DR-D16: the detail page's route button and its confirmation. */
class AddToRoutePresentationTest {
    @Test
    fun buttonLabelFollowsAvailability() {
        assertEquals("동선에 추가", addToRouteLabel(AddToRouteAvailability.CAN_ADD, AppLanguage.KO))
        assertEquals("동선에 있음", addToRouteLabel(AddToRouteAvailability.IN_ROUTE, AppLanguage.KO))
        assertEquals("동선이 가득 찼어요", addToRouteLabel(AddToRouteAvailability.FULL, AppLanguage.KO))
        assertEquals("ADD TO ROUTE", addToRouteLabel(AddToRouteAvailability.CAN_ADD, AppLanguage.EN))
        assertEquals("IN YOUR ROUTE", addToRouteLabel(AddToRouteAvailability.IN_ROUTE, AppLanguage.EN))
        assertEquals("ROUTE IS FULL", addToRouteLabel(AddToRouteAvailability.FULL, AppLanguage.EN))
        assertNull(addToRouteLabel(AddToRouteAvailability.HIDDEN, AppLanguage.KO))
    }

    @Test
    fun confirmationCountsTheStops() {
        assertEquals("동선에 추가했어요 (3/10)", addedToRouteMessage(3, AppLanguage.KO))
        assertEquals("ADDED TO ROUTE (3/10)", addedToRouteMessage(3, AppLanguage.EN))
        assertEquals("열기", addedToRouteAction(AppLanguage.KO))
        assertEquals("OPEN", addedToRouteAction(AppLanguage.EN))
    }
}
