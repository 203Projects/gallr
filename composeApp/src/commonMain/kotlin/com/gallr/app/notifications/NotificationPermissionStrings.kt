package com.gallr.app.notifications

import com.gallr.shared.data.model.AppLanguage

data class NotificationPermissionStrings(
    val title: String,
    val body: String,
    val confirm: String,
    val dismiss: String,
)

fun notificationPermissionStrings(lang: AppLanguage): NotificationPermissionStrings = when (lang) {
    AppLanguage.EN -> NotificationPermissionStrings(
        title = "Get reminders for your saved exhibitions",
        body = "We'll let you know when bookmarked exhibitions are closing soon, opening soon, or hosting a reception today.",
        confirm = "Enable",
        dismiss = "Not now",
    )
    AppLanguage.KO -> NotificationPermissionStrings(
        title = "저장한 전시 알림 받기",
        body = "북마크한 전시가 곧 마감되거나 개막되거나 오프닝 리셉션이 열릴 때 알려드릴게요.",
        confirm = "켜기",
        dismiss = "다음에",
    )
}
