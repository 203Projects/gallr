package com.gallr.shared.notifications

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
class IosNotificationScheduler : NotificationScheduler {

    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun hasPermission(): Boolean = suspendCancellableCoroutine { cont ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            cont.resume(
                status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional,
            )
        }
    }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            cont.resume(granted)
        }
    }

    override suspend fun schedule(spec: NotificationSpec) = suspendCancellableCoroutine<Unit> { cont ->
        val content = UNMutableNotificationContent().apply {
            setTitle(spec.title)
            setBody(spec.body)
            setUserInfo(buildDeepLinkUserInfo(spec.deepLink))
        }

        val date = NSDate.dateWithTimeIntervalSince1970(spec.triggerAt.toEpochMilliseconds() / 1000.0)
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute,
            date,
        )
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, false)

        val request = UNNotificationRequest.requestWithIdentifier(spec.id, content, trigger)
        center.addNotificationRequest(request) { _ -> cont.resume(Unit) }
    }

    override suspend fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    override suspend fun cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    override suspend fun scheduledIds(): Set<String> = suspendCancellableCoroutine { cont ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val list = requests as? List<*> ?: emptyList<Any>()
            cont.resume(list.filterIsInstance<UNNotificationRequest>().map { it.identifier }.toSet())
        }
    }

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink
    override fun setPendingDeepLink(link: DeepLink) { _pendingDeepLink.value = link }
    override fun consumePendingDeepLink() { _pendingDeepLink.value = null }

    private fun buildDeepLinkUserInfo(link: DeepLink): Map<Any?, *> = when (link) {
        is DeepLink.Exhibition -> mapOf(
            "deepLinkType" to "exhibition",
            "exhibitionId" to link.id,
        )
        is DeepLink.MyList -> mapOf("deepLinkType" to "mylist")
    }
}
