package com.gallr.shared.analytics

import com.gallr.shared.repository.AnalyticsPreferenceRepository
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates the immutable release gate with the persisted device preference. */
class MobileAnalyticsController(
    private val delegate: AnalyticsRecorder,
    private val preferences: AnalyticsPreferenceRepository,
    private val releaseEnabled: Boolean,
) : AnalyticsRecorder {
    private val initializationMutex = Mutex()
    private val transitionMutex = Mutex()
    private var gate: GatedAnalyticsRecorder? = null

    fun observeUserEnabled(): Flow<Boolean> = preferences.observeEnabled()

    suspend fun initialize() {
        initializedGate()
    }

    suspend fun setUserEnabled(enabled: Boolean) {
        transitionMutex.withLock {
            val current = initializedGate()
            if (!enabled) {
                current.pause()
                try {
                    preferences.setEnabled(false)
                } catch (error: Exception) {
                    runSuspendCatching { current.setEnabled(false) }
                    throw error
                }
                current.setEnabled(false)
                return@withLock
            }

            try {
                preferences.setEnabled(true)
                current.setEnabled(releaseEnabled)
            } catch (error: Exception) {
                runSuspendCatching { preferences.setEnabled(false) }
                current.setEnabled(false)
                throw error
            }
        }
    }

    suspend fun onResume() {
        initializedGate().flush()
    }

    override suspend fun record(createEvent: () -> MobileAnalyticsEvent) {
        initializationMutex.withLock { gate }?.record(createEvent)
    }

    override suspend fun flush() {
        onResume()
    }

    override suspend fun clear() {
        initializedGate().clear()
    }

    private suspend fun initializedGate(): GatedAnalyticsRecorder =
        initializationMutex.withLock {
            gate ?: run {
                val userEnabled = preferences.observeEnabled().first()
                createGatedAnalyticsRecorder(
                    delegate = delegate,
                    initiallyEnabled = releaseEnabled && userEnabled,
                ).also { gate = it }
            }
        }
}
