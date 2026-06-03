package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate

data class Event(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val descriptionKo: String,
    val descriptionEn: String,
    val locationLabelKo: String,
    val locationLabelEn: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val brandColor: String,
    val ticketUrl: String?,
    val isActive: Boolean,
    val coverImageUrl: String? = null,
    val shortLabel: String? = null,
) {
    fun localizedName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> nameEn.ifEmpty { nameKo }
        AppLanguage.KO -> nameKo
    }

    /**
     * Compact identifier for the exhibition-card corner ribbon. Uses the
     * admin-authored [shortLabel] when present; otherwise truncates the
     * localized name to [maxChars] (default 12) with an ellipsis.
     */
    fun ribbonLabel(lang: AppLanguage, maxChars: Int = 12): String {
        val explicit = shortLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        val name = localizedName(lang)
        return if (name.length > maxChars) name.take(maxChars) + "…" else name
    }

    fun localizedDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> descriptionEn.ifEmpty { descriptionKo }
        AppLanguage.KO -> descriptionKo
    }

    fun localizedLocationLabel(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> locationLabelEn.ifEmpty { locationLabelKo }
        AppLanguage.KO -> locationLabelKo
    }

    /**
     * Whether this event should surface its chrome (promotion card, list banner,
     * map FAB, exhibition-card ribbon) on [today]. An active event is shown from
     * now until it ends — including before [startDate], so admins can promote an
     * upcoming event. Ended events auto-retire (no manual `is_active` flip needed).
     */
    fun isVisibleOn(today: LocalDate): Boolean =
        isActive && today <= endDate

    /**
     * Whether the event is still upcoming or already running on [today]. An ENDED
     * phase is intentionally absent: [isVisibleOn] excludes `today > endDate`, so
     * no display path ever renders an ended event.
     */
    fun phaseOn(today: LocalDate): EventPhase =
        if (today < startDate) EventPhase.UPCOMING else EventPhase.LIVE

    /**
     * Date-aware eyebrow label for the promotion card and list banner: an upcoming
     * event reads "예정" / "Upcoming", a running one "지금 진행 중" / "NOW ON".
     * "예정" matches the app's status vocabulary (ExhibitionStatus.UPCOMING → "오픈 예정").
     */
    fun statusEyebrow(today: LocalDate, lang: AppLanguage): String = when (phaseOn(today)) {
        EventPhase.UPCOMING -> if (lang == AppLanguage.KO) "예정" else "Upcoming"
        EventPhase.LIVE -> if (lang == AppLanguage.KO) "지금 진행 중" else "NOW ON"
    }

    /**
     * Compact status label for the event detail banner eyebrow: "예정" / "Upcoming"
     * when upcoming, "진행 중" / "NOW ON" when running. Shorter than [statusEyebrow]'s
     * active form ("지금 진행 중") because the detail eyebrow is a terse single line.
     */
    fun statusLabel(today: LocalDate, lang: AppLanguage): String = when (phaseOn(today)) {
        EventPhase.UPCOMING -> if (lang == AppLanguage.KO) "예정" else "Upcoming"
        EventPhase.LIVE -> if (lang == AppLanguage.KO) "진행 중" else "NOW ON"
    }
}

enum class EventPhase { UPCOMING, LIVE }
