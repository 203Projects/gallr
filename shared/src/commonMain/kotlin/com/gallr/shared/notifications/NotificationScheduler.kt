package com.gallr.shared.notifications

import kotlinx.coroutines.flow.StateFlow

interface NotificationScheduler {
    suspend fun hasPermission(): Boolean
    suspend fun requestPermission(): Boolean
    suspend fun schedule(spec: NotificationSpec)
    suspend fun cancel(id: String)
    suspend fun cancelAll()
    suspend fun scheduledIds(): Set<String>

    val pendingDeepLink: StateFlow<DeepLink?>
    fun setPendingDeepLink(link: DeepLink)
    fun consumePendingDeepLink()
}
