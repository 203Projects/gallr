package com.gallr.shared.util

import kotlin.coroutines.cancellation.CancellationException

/** Converts ordinary failures to [Result] while preserving structured-concurrency cancellation. */
suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
