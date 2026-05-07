package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage

object NotificationContent {

    private const val APP_NAME = "gallr"

    fun render(
        type: TriggerType,
        language: AppLanguage,
        exhibitionName: String,
        venueName: String,
    ): Pair<String, String> {
        val body = when (type) {
            TriggerType.CLOSING -> when (language) {
                AppLanguage.EN -> "$exhibitionName closes in 3 days — don't miss it."
                AppLanguage.KO -> "$exhibitionName 마감 3일 전입니다. 놓치지 마세요."
            }
            TriggerType.OPENING -> when (language) {
                AppLanguage.EN -> "$exhibitionName opens in 3 days."
                AppLanguage.KO -> "$exhibitionName 개막 3일 전입니다."
            }
            TriggerType.RECEPTION -> when (language) {
                AppLanguage.EN -> "Reception today at $venueName."
                AppLanguage.KO -> "오늘 ${venueName}에서 오프닝 리셉션이 열립니다."
            }
            TriggerType.INACTIVITY -> when (language) {
                AppLanguage.EN -> "Your list hasn't changed in a while — check what's closing soon."
                AppLanguage.KO -> "마이 리스트를 업데이트한 지 꽤 됐어요. 곧 마감되는 전시를 확인해보세요."
            }
        }
        return APP_NAME to body
    }
}
