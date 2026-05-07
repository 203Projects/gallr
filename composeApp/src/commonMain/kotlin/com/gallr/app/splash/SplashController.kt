package com.gallr.app.splash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SplashController(
    private val minVisibleMs: Long = 1500,
    private val hardCapMs: Long = 3000,
    private val scope: CoroutineScope,
) {
    private val themeReady = MutableStateFlow(false)
    private val dataReady = MutableStateFlow(false)
    private val minTimeElapsed = MutableStateFlow(false)
    private val hardCapElapsed = MutableStateFlow(false)
    private val skipped = MutableStateFlow(false)

    val isVisible: StateFlow<Boolean> = combine(
        themeReady, dataReady, minTimeElapsed, hardCapElapsed, skipped,
    ) { values ->
        val theme = values[0]
        val data = values[1]
        val min = values[2]
        val cap = values[3]
        val skip = values[4]
        if (skip) return@combine false
        !((theme && data && min) || cap)
    }.stateIn(scope, SharingStarted.Eagerly, true)

    fun start() {
        scope.launch { delay(minVisibleMs); minTimeElapsed.value = true }
        scope.launch { delay(hardCapMs); hardCapElapsed.value = true }
    }

    fun markThemeReady() { themeReady.value = true }
    fun themeReadyValue(): Boolean = themeReady.value
    fun markDataReady() { dataReady.value = true }
    fun skipSplash() { skipped.value = true }
}
