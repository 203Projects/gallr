package com.gallr.shared.notifications

import kotlinx.datetime.Instant

data class NotificationSpec(
    val id: String,
    val title: String,
    val body: String,
    val triggerAt: Instant,
    val deepLink: DeepLink,
)

const val INACTIVITY_NOTIFICATION_ID = "inactivity"

fun notificationId(exhibitionId: String, type: TriggerType): String = when (type) {
    TriggerType.CLOSING -> "${exhibitionId}_closing"
    TriggerType.OPENING -> "${exhibitionId}_opening"
    TriggerType.RECEPTION -> "${exhibitionId}_reception"
    TriggerType.INACTIVITY -> INACTIVITY_NOTIFICATION_ID
}
