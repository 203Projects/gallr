package com.gallr.app.ui.update

import com.gallr.shared.app.UpdateRequirement
import com.gallr.shared.data.model.AppLanguage
import kotlinx.datetime.LocalDate

/** The required-update notice and the recommended-update nudge, worded as spec 098 contracts/update-screens.md. */
internal fun requiredUpdateTitle(language: AppLanguage): String =
    if (language == AppLanguage.KO) "업데이트가 필요해요" else "UPDATE REQUIRED"

internal fun requiredUpdateMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) {
        "지금 쓰는 버전은 더 이상 지원되지 않아요. 업데이트하면 계속 쓸 수 있어요."
    } else {
        "This version is no longer supported. Update to keep using gallr."
    }

internal fun updateActionLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "업데이트" else "UPDATE"

internal fun storeFallbackMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "스토어에서 gallr를 업데이트해 주세요" else "Please update gallr from the store"

internal fun updateNudgeText(language: AppLanguage): String =
    if (language == AppLanguage.KO) "새 버전이 있어요" else "A NEW VERSION IS AVAILABLE"

internal fun updateLaterLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "나중에" else "LATER"

/** The nudge shows for a recommended update at most once a calendar day; a required update shows the notice instead. */
internal fun showUpdateNudge(
    requirement: UpdateRequirement,
    dismissedOn: LocalDate?,
    today: LocalDate,
): Boolean = requirement == UpdateRequirement.RECOMMENDED && dismissedOn != today
