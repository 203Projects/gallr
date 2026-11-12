package com.gallr.app.viewmodel

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How often a screen that is shown again may read the account archive (MY 일기, the exhibition page). */
internal val ACCOUNT_READ_INTERVAL: Duration = 30.seconds

/**
 * Lets a screen read the account archive when it is shown without reading it on every showing: switching back and
 * forth reads it at most once per [interval]. The app's return to the foreground reads it on its own.
 */
internal class AccountReadThrottle(
    private val clock: Clock,
    private val interval: Duration = ACCOUNT_READ_INTERVAL,
) {
    private var lastRead: Instant? = null

    /** True when a read may start now; it then counts as the last read. */
    fun tryStart(): Boolean {
        val now = clock.now()
        val last = lastRead
        if (last != null && now - last < interval) return false
        lastRead = now
        return true
    }
}
