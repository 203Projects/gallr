package com.gallr.shared.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppLogTest {
    @Test
    fun failure_message_contains_stable_context_without_exception_message() {
        val sensitiveMessage = "https://example.invalid?access_token=secret"

        val message =
            structuredLogMessage(
                operation = "catalog_refresh",
                outcome = "failure",
                cause = IllegalStateException(sensitiveMessage),
            )

        assertEquals(
            "operation=catalog_refresh outcome=failure cause=IllegalStateException",
            message,
        )
        assertFalse(sensitiveMessage in message)
    }

    @Test
    fun success_message_omits_failure_context() {
        assertEquals(
            "operation=auth_deeplink outcome=received",
            structuredLogMessage(operation = "auth_deeplink", outcome = "received"),
        )
    }
}
