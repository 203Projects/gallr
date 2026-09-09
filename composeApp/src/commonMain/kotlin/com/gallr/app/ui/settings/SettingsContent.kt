package com.gallr.app.ui.settings

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.repository.AccountDeletionRateLimitedException
import com.gallr.shared.repository.AccountDeletionReauthenticationRequiredException
import com.gallr.shared.repository.AccountDeletionStatusUnknownException
import com.gallr.shared.repository.AccountDeletionSupportRequiredException

internal enum class SettingsAccountAction {
    SIGN_OUT,
    DELETE_ACCOUNT,
}

internal enum class SettingsAccountFailure {
    SIGN_OUT,
    DELETE_ACCOUNT,
    DELETE_ACCOUNT_REAUTHENTICATION_REQUIRED,
    DELETE_ACCOUNT_SUPPORT_REQUIRED,
    DELETE_ACCOUNT_RATE_LIMITED,
    DELETE_ACCOUNT_STATUS_UNKNOWN,
}

internal fun settingsAccountFailure(
    action: SettingsAccountAction,
    error: Throwable,
): SettingsAccountFailure =
    when (action) {
        SettingsAccountAction.SIGN_OUT -> {
            SettingsAccountFailure.SIGN_OUT
        }

        SettingsAccountAction.DELETE_ACCOUNT -> {
            when (error) {
                is AccountDeletionReauthenticationRequiredException -> {
                    SettingsAccountFailure.DELETE_ACCOUNT_REAUTHENTICATION_REQUIRED
                }

                is AccountDeletionSupportRequiredException -> {
                    SettingsAccountFailure.DELETE_ACCOUNT_SUPPORT_REQUIRED
                }

                is AccountDeletionRateLimitedException -> {
                    SettingsAccountFailure.DELETE_ACCOUNT_RATE_LIMITED
                }

                is AccountDeletionStatusUnknownException -> {
                    SettingsAccountFailure.DELETE_ACCOUNT_STATUS_UNKNOWN
                }

                else -> {
                    SettingsAccountFailure.DELETE_ACCOUNT
                }
            }
        }
    }

internal fun SettingsAccountFailure.localizedMessage(lang: AppLanguage): String =
    when (this) {
        SettingsAccountFailure.SIGN_OUT -> {
            if (lang == AppLanguage.KO) {
                "로그아웃하지 못했습니다. 다시 시도해 주세요."
            } else {
                "Couldn’t sign out. Please try again."
            }
        }

        SettingsAccountFailure.DELETE_ACCOUNT -> {
            if (lang == AppLanguage.KO) {
                "계정 삭제를 완료하지 못했습니다. 데이터는 삭제되지 않았습니다."
            } else {
                "Account deletion couldn’t be completed. Your data was not deleted."
            }
        }

        SettingsAccountFailure.DELETE_ACCOUNT_REAUTHENTICATION_REQUIRED -> {
            if (lang == AppLanguage.KO) {
                "계정 삭제 전에 로그아웃한 후 다시 로그인해 주세요."
            } else {
                "Sign out and sign in again before deleting your account."
            }
        }

        SettingsAccountFailure.DELETE_ACCOUNT_SUPPORT_REQUIRED -> {
            if (lang == AppLanguage.KO) {
                "운영 권한 이전을 위해 privacy@gallrmap.com으로 문의해 주세요."
            } else {
                "Contact privacy@gallrmap.com to transfer your operator access before deletion."
            }
        }

        SettingsAccountFailure.DELETE_ACCOUNT_RATE_LIMITED -> {
            if (lang == AppLanguage.KO) {
                "요청이 너무 많습니다. 15분 후 다시 시도해 주세요."
            } else {
                "Too many attempts. Please try again in 15 minutes."
            }
        }

        SettingsAccountFailure.DELETE_ACCOUNT_STATUS_UNKNOWN -> {
            if (lang == AppLanguage.KO) {
                "삭제 결과를 확인할 수 없습니다. 다시 시도하기 전에 앱을 새로 열어 계정 상태를 확인해 주세요."
            } else {
                "The deletion result couldn’t be confirmed. Reopen the app to check your account before retrying."
            }
        }
    }

enum class SettingsRowId {
    LANGUAGE,
    APPEARANCE,
    NOTIFICATIONS,
    USAGE_ANALYTICS,
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
    analyticsEnabled: Boolean?,
    version: String,
    isAuthenticated: Boolean,
): List<SettingsSectionModel> {
    val preferences =
        SettingsSectionModel(
            label = if (lang == AppLanguage.KO) "환경 설정" else "PREFERENCES",
            rows =
                listOf(
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
                        value =
                            if (notificationsEnabled) {
                                if (lang == AppLanguage.KO) "켜짐" else "On"
                            } else {
                                if (lang == AppLanguage.KO) "꺼짐" else "Off"
                            },
                    ),
                    SettingsRowModel(
                        id = SettingsRowId.USAGE_ANALYTICS,
                        label = if (lang == AppLanguage.KO) "사용 분석" else "Usage analytics",
                        value =
                            when (analyticsEnabled) {
                                true -> if (lang == AppLanguage.KO) "켜짐" else "On"
                                false -> if (lang == AppLanguage.KO) "꺼짐" else "Off"
                                null -> "—"
                            },
                    ),
                ),
        )

    val support =
        SettingsSectionModel(
            label = if (lang == AppLanguage.KO) "지원" else "SUPPORT",
            rows =
                listOf(
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

    val about =
        SettingsSectionModel(
            label = if (lang == AppLanguage.KO) "정보" else "ABOUT",
            rows =
                listOf(
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

    val account =
        SettingsSectionModel(
            label = if (lang == AppLanguage.KO) "계정" else "ACCOUNT",
            rows =
                listOf(
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

fun ThemeMode.localizedLabel(lang: AppLanguage): String =
    when (this) {
        ThemeMode.LIGHT -> if (lang == AppLanguage.KO) "라이트" else "Light"
        ThemeMode.DARK -> if (lang == AppLanguage.KO) "다크" else "Dark"
        ThemeMode.SYSTEM -> if (lang == AppLanguage.KO) "시스템" else "System"
    }
