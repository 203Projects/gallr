package com.gallr.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FcmRegistrationCoordinatorTest {
    @Test
    fun `registered FID completes the current registration`() {
        val coordinator = FcmRegistrationCoordinator()
        val registration = coordinator.beginRegistration()

        coordinator.acceptRegistration("cdefghijklmnopqrstuvwxyz")

        assertTrue(registration.isCompleted)
        assertEquals("cdefghijklmnopqrstuvwxyz", runBlocking { registration.await() })
    }

    @Test
    fun `invalid registration does not complete the current request`() {
        val coordinator = FcmRegistrationCoordinator()
        val registration = coordinator.beginRegistration()

        coordinator.acceptRegistration("short")

        assertFalse(registration.isCompleted)
    }
}
