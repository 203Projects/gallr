package com.gallr.shared.notifications

import kotlinx.coroutines.flow.StateFlow

interface NotificationScheduler {
    suspend fun hasPermission(): Boolean

    suspend fun requestPermission(): Boolean

    /** Schedules [spec], replacing any pending reminder with the same id. */
    suspend fun schedule(spec: NotificationSpec)

    suspend fun cancel(id: String)

    suspend fun cancelAll()

    /**
     * The reminders still to come, as they were scheduled: one that has fired is no longer pending. A platform that
     * drops reminders on its own (Android loses every alarm on reboot and force-stop) arms its lost ones again before
     * answering, so the answer matches what will actually fire.
     */
    suspend fun pendingReminders(): List<NotificationSpec>

    val pendingDeepLink: StateFlow<DeepLink?>

    fun setPendingDeepLink(link: DeepLink)

    fun consumePendingDeepLink()
}
