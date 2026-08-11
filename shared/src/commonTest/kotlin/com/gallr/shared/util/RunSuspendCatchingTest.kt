package com.gallr.shared.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunSuspendCatchingTest {
    @Test
    fun returns_success_and_ordinary_failure_as_results() =
        runTest {
            assertEquals(42, runSuspendCatching { 42 }.getOrThrow())

            val result = runSuspendCatching<Int> { error("failed") }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun rethrows_cancellation() =
        runTest {
            assertFailsWith<CancellationException> {
                runSuspendCatching<Unit> { throw CancellationException("cancelled") }
            }
        }
}
