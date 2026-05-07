package com.gallr.shared.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeNotificationScheduler(
    private var permissionGranted: Boolean = true,
) : NotificationScheduler {

    val scheduled = mutableMapOf<String, NotificationSpec>()
    val scheduleCalls = mutableListOf<NotificationSpec>()
    val cancelCalls = mutableListOf<String>()
    val cancelAllCallCount: Int get() = _cancelAllCalls
    private var _cancelAllCalls = 0
    var requestPermissionResult: Boolean = true
    var requestPermissionCalls: Int = 0
        private set

    override suspend fun hasPermission(): Boolean = permissionGranted

    override suspend fun requestPermission(): Boolean {
        requestPermissionCalls++
        permissionGranted = requestPermissionResult
        return permissionGranted
    }

    override suspend fun schedule(spec: NotificationSpec) {
        scheduleCalls.add(spec)
        scheduled[spec.id] = spec
    }

    override suspend fun cancel(id: String) {
        cancelCalls.add(id)
        scheduled.remove(id)
    }

    override suspend fun cancelAll() {
        _cancelAllCalls++
        scheduled.clear()
    }

    override suspend fun scheduledIds(): Set<String> = scheduled.keys.toSet()

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink

    override fun setPendingDeepLink(link: DeepLink) { _pendingDeepLink.value = link }
    override fun consumePendingDeepLink() { _pendingDeepLink.value = null }

    fun setPermission(granted: Boolean) { permissionGranted = granted }
}
