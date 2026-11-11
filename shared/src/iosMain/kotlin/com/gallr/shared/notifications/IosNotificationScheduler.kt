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
import platform.Foundation.timeIntervalSince1970
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
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class)
class IosNotificationScheduler : NotificationScheduler {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun hasPermission(): Boolean =
        suspendCancellableCoroutine { cont ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                cont.resume(
                    status == UNAuthorizationStatusAuthorized ||
                        status == UNAuthorizationStatusProvisional,
                )
            }
        }

    override suspend fun requestPermission(): Boolean =
        suspendCancellableCoroutine { cont ->
            val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
            center.requestAuthorizationWithOptions(options) { granted, _ ->
                cont.resume(granted)
            }
        }

    override suspend fun schedule(spec: NotificationSpec) =
        suspendCancellableCoroutine<Unit> { cont ->
            val content =
                UNMutableNotificationContent().apply {
                    setTitle(spec.title)
                    setBody(spec.body)
                    setUserInfo(buildDeepLinkUserInfo(spec.deepLink))
                }

            val date = NSDate.dateWithTimeIntervalSince1970(spec.triggerAt.toEpochMilliseconds() / 1000.0)
            val components =
                NSCalendar.currentCalendar.components(
                    NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or
                        NSCalendarUnitMinute,
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

    /** Delivered reminders leave the pending list on their own, so it is exactly what is still to come. */
    override suspend fun pendingReminders(): List<NotificationSpec> =
        suspendCancellableCoroutine { cont ->
            center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                cont.resume(
                    requests
                        .orEmpty()
                        .filterIsInstance<UNNotificationRequest>()
                        .map { it.toSpec() },
                )
            }
        }

    private fun UNNotificationRequest.toSpec(): NotificationSpec {
        // A calendar trigger fires on the minute it was given, so the next date reads back the scheduled instant.
        // Without one it is not a reminder this app set: it never matches one that is owed, so a sync replaces it.
        val fireDate = (trigger as? UNCalendarNotificationTrigger)?.nextTriggerDate()
        val triggerAt =
            fireDate?.let { Instant.fromEpochMilliseconds((it.timeIntervalSince1970 * 1000).toLong()) }
                ?: Instant.DISTANT_PAST
        return NotificationSpec(
            id = identifier,
            title = content.title,
            body = content.body,
            triggerAt = triggerAt,
            deepLink = deepLinkFrom(content.userInfo) ?: DeepLink.MyList,
        )
    }

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink

    override fun setPendingDeepLink(link: DeepLink) {
        _pendingDeepLink.value = link
    }

    override fun consumePendingDeepLink() {
        _pendingDeepLink.value = null
    }

    private fun buildDeepLinkUserInfo(link: DeepLink): Map<Any?, *> =
        when (link) {
            is DeepLink.Exhibition -> {
                mapOf(
                    "deepLinkType" to "exhibition",
                    "exhibitionId" to link.id,
                )
            }

            is DeepLink.MyList -> {
                mapOf("deepLinkType" to "mylist")
            }
        }
}

/** The deep link a reminder's `userInfo` carries (written by [IosNotificationScheduler.schedule]), if any. */
internal fun deepLinkFrom(userInfo: Map<Any?, *>): DeepLink? =
    when (userInfo["deepLinkType"] as? String) {
        "exhibition" -> {
            (userInfo["exhibitionId"] as? String)?.let { DeepLink.Exhibition(it) } ?: DeepLink.MyList
        }

        "mylist" -> {
            DeepLink.MyList
        }

        else -> {
            null
        }
    }
