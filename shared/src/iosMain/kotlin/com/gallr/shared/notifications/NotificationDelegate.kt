package com.gallr.shared.notifications

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class NotificationDelegate(
    private val scheduler: IosNotificationScheduler,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val userInfo = didReceiveNotificationResponse.notification.request.content.userInfo
        val type = userInfo["deepLinkType"] as? String
        val link = when (type) {
            "exhibition" -> {
                val id = userInfo["exhibitionId"] as? String
                if (id != null) DeepLink.Exhibition(id) else DeepLink.MyList
            }
            "mylist" -> DeepLink.MyList
            else -> null
        }
        link?.let { scheduler.setPendingDeepLink(it) }
        withCompletionHandler()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList)
    }
}
