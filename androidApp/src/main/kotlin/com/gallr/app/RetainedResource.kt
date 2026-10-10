package com.gallr.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.runBlocking

/**
 * Keeps [value] in an activity's ViewModelStore, so it lives exactly as long as that activity's ViewModels: kept
 * across configuration changes (dark mode, font scale, locale) and released once when the activity finishes for good.
 * The app's ViewModels hold repositories built on the network clients, so the clients must not close while those
 * ViewModels survive a recreation.
 */
internal class RetainedResource<T : Any>(
    val value: T,
    private val release: suspend (T) -> Unit,
) : ViewModel() {
    override fun onCleared() {
        runBlocking { release(value) }
    }
}
