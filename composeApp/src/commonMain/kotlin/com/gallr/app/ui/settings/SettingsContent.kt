package com.gallr.app.ui.settings

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode

enum class SettingsRowId {
    LANGUAGE,
    APPEARANCE,
    NOTIFICATIONS,
    SEND_FEEDBACK,
    REPORT_INCORRECT_EXHIBITION,
    SHARE_GALLR,
    INSTAGRAM,
    ABOUT_GALLR,
    PRIVACY_POLICY,
    VERSION,
    SIGN_OUT,
    DELETE_ACCOUNT,
}

data class SettingsRowModel(
    val id: SettingsRowId,
    val label: String,
    val value: String? = null,
    val isDisclosure: Boolean = true,
)

data class SettingsSectionModel(
    val label: String,
    val rows: List<SettingsRowModel>,
)

fun settingsSections(
    lang: AppLanguage,
    themeMode: ThemeMode,
    notificationsEnabled: Boolean,
    version: String,
    isAuthenticated: Boolean,
): List<SettingsSectionModel> {
    val preferences = SettingsSectionModel(
        label = if (lang == AppLanguage.KO) "환경 설정" else "PREFERENCES",
        rows = listOf(
            SettingsRowModel(
                id = SettingsRowId.LANGUAGE,
                label = if (lang == AppLanguage.KO) "언어" else "Language",
                value = if (lang == AppLanguage.KO) "한국어" else "English",
            ),
            SettingsRowModel(
                id = SettingsRowId.APPEARANCE,
                label = if (lang == AppLanguage.KO) "화면 모드" else "Appearance",
                value = themeMode.localizedLabel(lang),
            ),
            SettingsRowModel(
                id = SettingsRowId.NOTIFICATIONS,
                label = if (lang == AppLanguage.KO) "알림" else "Notifications",
                value = if (notificationsEnabled) {
                    if (lang == AppLanguage.KO) "켜짐" else "On"
                } else {
                    if (lang == AppLanguage.KO) "꺼짐" else "Off"
                },
            ),
        ),
    )

    val support = SettingsSectionModel(
        label = if (lang == AppLanguage.KO) "지원" else "SUPPORT",
        rows = listOf(
            SettingsRowModel(
                id = SettingsRowId.SEND_FEEDBACK,
                label = if (lang == AppLanguage.KO) "의견 보내기" else "Send feedback",
            ),
            SettingsRowModel(
                id = SettingsRowId.REPORT_INCORRECT_EXHIBITION,
                label = if (lang == AppLanguage.KO) "전시 정보 오류 신고" else "Report incorrect exhibition",
            ),
            SettingsRowModel(
                id = SettingsRowId.SHARE_GALLR,
                label = if (lang == AppLanguage.KO) "gallr 공유하기" else "Share gallr",
            ),
            SettingsRowModel(
                id = SettingsRowId.INSTAGRAM,
                label = if (lang == AppLanguage.KO) "인스타그램" else "Instagram",
            ),
        ),
    )

    val about = SettingsSectionModel(
        label = if (lang == AppLanguage.KO) "정보" else "ABOUT",
        rows = listOf(
            SettingsRowModel(
                id = SettingsRowId.ABOUT_GALLR,
                label = if (lang == AppLanguage.KO) "gallr 소개" else "About gallr",
            ),
            SettingsRowModel(
                id = SettingsRowId.PRIVACY_POLICY,
                label = if (lang == AppLanguage.KO) "개인정보 처리방침" else "Privacy Policy",
            ),
            SettingsRowModel(
                id = SettingsRowId.VERSION,
                label = if (lang == AppLanguage.KO) "버전" else "Version",
                value = version,
                isDisclosure = false,
            ),
        ),
    )

    val account = SettingsSectionModel(
        label = if (lang == AppLanguage.KO) "계정" else "ACCOUNT",
        rows = listOf(
            SettingsRowModel(
                id = SettingsRowId.SIGN_OUT,
                label = if (lang == AppLanguage.KO) "로그아웃" else "Sign out",
            ),
            SettingsRowModel(
                id = SettingsRowId.DELETE_ACCOUNT,
                label = if (lang == AppLanguage.KO) "계정 삭제" else "Delete account",
            ),
        ),
    )

    return buildList {
        add(preferences)
        add(support)
        add(about)
        if (isAuthenticated) add(account)
    }
}

fun ThemeMode.localizedLabel(lang: AppLanguage): String = when (this) {
    ThemeMode.LIGHT -> if (lang == AppLanguage.KO) "라이트" else "Light"
    ThemeMode.DARK -> if (lang == AppLanguage.KO) "다크" else "Dark"
    ThemeMode.SYSTEM -> if (lang == AppLanguage.KO) "시스템" else "System"
}
