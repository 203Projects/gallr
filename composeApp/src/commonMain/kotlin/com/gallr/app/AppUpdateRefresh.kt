package com.gallr.app

import com.gallr.shared.repository.AppUpdateRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * When the version policy is read (spec 098 FR-001): once at start, and on resume after the app was away for at
 * least [AWAY_BEFORE_REFRESH]. Switching apps briefly does not re-read it.
 */
internal class AppUpdateRefresh(
    private val repository: AppUpdateRepository?,
    private val clock: Clock = Clock.System,
) {
    private var pausedAt: Instant? = null

    suspend fun refreshNow() {
        repository?.refresh()
    }

    fun paused() {
        pausedAt = clock.now()
    }

    suspend fun resumed() {
        val since = pausedAt ?: return
        pausedAt = null
        if (clock.now() - since >= AWAY_BEFORE_REFRESH) refreshNow()
    }

    private companion object {
        val AWAY_BEFORE_REFRESH = 1.minutes
    }
}
