package com.gallr.shared.diary

import com.gallr.shared.data.model.AppLanguage

/**
 * The one overall feeling a diary entry may carry. Private to its author: synced to their account, never public,
 * never in analytics. [wire] is the stored and sent value; renaming one needs a migration on both ends.
 */
enum class VisitFeeling(
    val wire: String,
    private val labelKo: String,
    private val labelEn: String,
) {
    LOVED("loved", "좋았어요", "Loved it"),
    FINE("fine", "그저 그랬어요", "It was fine"),
    NOT_FOR_ME("not_for_me", "아쉬웠어요", "Not for me"),
    ;

    fun label(language: AppLanguage): String =
        when (language) {
            AppLanguage.KO -> labelKo
            AppLanguage.EN -> labelEn
        }

    companion object {
        /** Null for no feeling and for a value this build does not know, so a newer one never breaks a payload. */
        fun fromWire(value: String?): VisitFeeling? = entries.firstOrNull { it.wire == value }
    }
}
