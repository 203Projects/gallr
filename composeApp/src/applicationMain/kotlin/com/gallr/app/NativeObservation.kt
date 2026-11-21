package com.gallr.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class NativeObservation internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }
}

internal fun <T> Flow<T>.observeForNative(
    scope: CoroutineScope,
    onChange: (T) -> Unit,
): NativeObservation = NativeObservation(scope.launch { collect(onChange) })
