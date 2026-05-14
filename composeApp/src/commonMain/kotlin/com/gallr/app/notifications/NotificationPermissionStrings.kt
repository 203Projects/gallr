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
        body = "We'll let you know when bookmarked exhibitions are closing soon, ending, or hosting an opening reception.",
        confirm = "Enable",
        dismiss = "Not now",
    )
    AppLanguage.KO -> NotificationPermissionStrings(
        title = "저장한 전시 알림 받기",
        body = "북마크한 전시가 곧 마감, 종료하거나 오프닝 리셉션이 있을 때 알려드릴게요.",
        confirm = "켜기",
        dismiss = "다음에",
    )
}
