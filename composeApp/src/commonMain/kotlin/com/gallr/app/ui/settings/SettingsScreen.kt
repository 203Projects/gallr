package com.gallr.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gallr.app.PlatformBackHandler
import com.gallr.app.platform.appVersionName
import com.gallr.app.platform.rememberOpenAppSettings
import com.gallr.app.platform.rememberOpenExternalUri
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.util.runSuspendCatching
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private const val ABOUT_URL = "https://gallrmap.com/about/"
private const val PRIVACY_POLICY_URL = "https://gallrmap.com/privacy/"
private const val ANALYTICS_PRIVACY_POLICY_URL = "https://gallrmap.com/privacy/#choices"
private const val INSTAGRAM_URL = "https://instagram.com/gallrmap"
private const val FEEDBACK_URL = "mailto:hello@gallrmap.com?subject=gallr%20feedback"
private const val REPORT_URL =
    "mailto:hello@gallrmap.com?subject=Exhibition%20information%20correction"

private enum class SettingsDestination {
    ROOT,
    LANGUAGE,
    APPEARANCE,
    NOTIFICATIONS,
    USAGE_ANALYTICS,
}

private enum class UnavailableAction {
    EMAIL,
    SYSTEM_SETTINGS,
    WEB_LINK,
}

@Composable
fun SettingsScreen(
    lang: AppLanguage,
    themeMode: ThemeMode,
    analyticsEnabled: Boolean?,
    isAuthenticated: Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    hasNotificationPermission: suspend () -> Boolean,
    requestNotificationPermission: suspend () -> Boolean,
    onAnalyticsEnabledChange: suspend (Boolean) -> Unit,
    onShareApp: () -> Unit,
    onSignOut: suspend () -> Unit,
    onDeleteAccount: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(SettingsDestination.ROOT) }
    var notificationsEnabled by remember { mutableStateOf(false) }
    var notificationStatusLoaded by remember { mutableStateOf(false) }
    var analyticsError by remember { mutableStateOf<String?>(null) }
    var isAnalyticsPreferenceSaving by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var isAccountActionRunning by remember { mutableStateOf(false) }
    var unavailableAction by remember { mutableStateOf<UnavailableAction?>(null) }

    LaunchedEffect(Unit) {
        notificationsEnabled = runSuspendCatching { hasNotificationPermission() }.getOrDefault(false)
        notificationStatusLoaded = true
    }

    val navigateBack = {
        if (destination == SettingsDestination.USAGE_ANALYTICS && isAnalyticsPreferenceSaving) {
            Unit
        } else if (destination == SettingsDestination.ROOT) {
            onBack()
        } else {
            destination = SettingsDestination.ROOT
        }
    }
    PlatformBackHandler(onBack = navigateBack)

    when (destination) {
        SettingsDestination.ROOT -> {
            SettingsRoot(
                lang = lang,
                themeMode = themeMode,
                notificationsEnabled = notificationsEnabled,
                notificationStatusLoaded = notificationStatusLoaded,
                analyticsEnabled = analyticsEnabled,
                isAuthenticated = isAuthenticated,
                onDestination = { destination = it },
                onShareApp = onShareApp,
                onSignOut = {
                    scope.launch {
                        accountError = null
                        isAccountActionRunning = true
                        runSuspendCatching { onSignOut() }
                            .onFailure {
                                accountError =
                                    settingsAccountFailure(SettingsAccountAction.SIGN_OUT, it)
                                        .localizedMessage(lang)
                            }
                        isAccountActionRunning = false
                    }
                },
                onDeleteAccount = {
                    accountError = null
                    showDeleteConfirm = true
                },
                onActionUnavailable = { unavailableAction = it },
                accountError = accountError,
                isAccountActionRunning = isAccountActionRunning,
                onBack = navigateBack,
                modifier = modifier,
            )
        }

        SettingsDestination.LANGUAGE -> {
            SettingsChoiceScreen(
                title = if (lang == AppLanguage.KO) "언어" else "LANGUAGE",
                selectionLabel = if (lang == AppLanguage.KO) "선택" else "SELECT ONE",
                backContentDescription =
                    if (lang == AppLanguage.KO) {
                        "뒤로"
                    } else {
                        "Back"
                    },
                prompt =
                    if (lang ==
                        AppLanguage.KO
                    ) {
                        "앱에서 사용할 언어를 선택하세요."
                    } else {
                        "Choose the language used throughout gallr."
                    },
                choices = AppLanguage.entries,
                selected = lang,
                label = { language -> if (language == AppLanguage.KO) "한국어" else "English" },
                onSelect = { language ->
                    onLanguageChange(language)
                    destination = SettingsDestination.ROOT
                },
                onBack = navigateBack,
                modifier = modifier,
            )
        }

        SettingsDestination.APPEARANCE -> {
            SettingsChoiceScreen(
                title = if (lang == AppLanguage.KO) "화면 모드" else "APPEARANCE",
                selectionLabel = if (lang == AppLanguage.KO) "선택" else "SELECT ONE",
                backContentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back",
                prompt =
                    if (lang == AppLanguage.KO) {
                        "시스템은 기기의 화면 모드를 자동으로 따릅니다."
                    } else {
                        "System follows your device appearance automatically."
                    },
                choices = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = themeMode,
                label = { mode -> mode.localizedLabel(lang) },
                onSelect = { mode ->
                    onThemeChange(mode)
                    destination = SettingsDestination.ROOT
                },
                onBack = navigateBack,
                modifier = modifier,
            )
        }

        SettingsDestination.NOTIFICATIONS -> {
            NotificationSettingsScreen(
                lang = lang,
                enabled = notificationsEnabled,
                isLoaded = notificationStatusLoaded,
                onRequestPermission = {
                    scope.launch {
                        notificationStatusLoaded = false
                        notificationsEnabled =
                            runSuspendCatching { requestNotificationPermission() }.getOrDefault(false)
                        notificationStatusLoaded = true
                    }
                },
                onActionUnavailable = { unavailableAction = it },
                onBack = navigateBack,
                modifier = modifier,
            )
        }

        SettingsDestination.USAGE_ANALYTICS -> {
            UsageAnalyticsSettingsScreen(
                lang = lang,
                enabled = analyticsEnabled,
                isSaving = isAnalyticsPreferenceSaving,
                error = analyticsError,
                onSelect = select@{ selected ->
                    if (
                        isAnalyticsPreferenceSaving ||
                        (selected == analyticsEnabled && analyticsError == null)
                    ) {
                        return@select
                    }
                    scope.launch {
                        analyticsError = null
                        isAnalyticsPreferenceSaving = true
                        runSuspendCatching { onAnalyticsEnabledChange(selected) }
                            .onFailure {
                                analyticsError = analyticsPreferenceErrorMessage(lang)
                            }
                        isAnalyticsPreferenceSaving = false
                    }
                },
                onActionUnavailable = { unavailableAction = it },
                onBack = navigateBack,
                modifier = modifier,
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isAccountActionRunning) showDeleteConfirm = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = if (lang == AppLanguage.KO) "계정을 삭제할까요?" else "Delete account?",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column {
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "프로필, 북마크와 감상이 영구적으로 삭제됩니다. 이 작업은 되돌릴 수 없습니다."
                            } else {
                                "Your profile, bookmarks, and thoughts will be permanently deleted. This cannot be undone."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (accountError != null) {
                        Text(
                            text = "! $accountError",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = GallrSpacing.md),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    enabled = !isAccountActionRunning,
                    shape = RectangleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (lang == AppLanguage.KO) "취소" else "Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            accountError = null
                            isAccountActionRunning = true
                            runSuspendCatching { onDeleteAccount() }
                                .onSuccess { showDeleteConfirm = false }
                                .onFailure {
                                    accountError =
                                        settingsAccountFailure(SettingsAccountAction.DELETE_ACCOUNT, it)
                                            .localizedMessage(lang)
                                }
                            isAccountActionRunning = false
                        }
                    },
                    enabled = !isAccountActionRunning,
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onBackground,
                            contentColor = MaterialTheme.colorScheme.background,
                        ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (lang == AppLanguage.KO) "영구 삭제" else "Permanently delete")
                }
            },
        )
    }

    unavailableAction?.let { action ->
        AlertDialog(
            onDismissRequest = { unavailableAction = null },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = unavailableActionTitle(action, lang),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = unavailableActionMessage(action, lang),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { unavailableAction = null },
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onBackground,
                            contentColor = MaterialTheme.colorScheme.background,
                        ),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(if (lang == AppLanguage.KO) "확인" else "OK")
                }
            },
        )
    }
}

@Composable
private fun SettingsRoot(
    lang: AppLanguage,
    themeMode: ThemeMode,
    notificationsEnabled: Boolean,
    notificationStatusLoaded: Boolean,
    analyticsEnabled: Boolean?,
    isAuthenticated: Boolean,
    onDestination: (SettingsDestination) -> Unit,
    onShareApp: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onActionUnavailable: (UnavailableAction) -> Unit,
    accountError: String?,
    isAccountActionRunning: Boolean,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val openExternalUri = rememberOpenExternalUri()
    val sections =
        settingsSections(
            lang = lang,
            themeMode = themeMode,
            notificationsEnabled = notificationsEnabled,
            analyticsEnabled = analyticsEnabled,
            version = appVersionName(),
            isAuthenticated = isAuthenticated,
        )

    SettingsScaffold(
        title = if (lang == AppLanguage.KO) "설정" else "SETTINGS",
        backContentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = GallrSpacing.screenMargin,
                    end = GallrSpacing.screenMargin,
                    bottom = GallrSpacing.xl,
                ),
        ) {
            sections.forEachIndexed { sectionIndex, section ->
                item(key = "section-${section.label}") {
                    Text(
                        text = section.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier =
                            Modifier.padding(
                                start = GallrSpacing.sm,
                                top = if (sectionIndex == 0) GallrSpacing.lg else GallrSpacing.xl,
                                bottom = GallrSpacing.sm,
                            ),
                    )
                }
                itemsIndexed(
                    items = section.rows,
                    key = { _, row -> row.id },
                ) { rowIndex, row ->
                    SettingsRow(
                        row =
                            if (row.id == SettingsRowId.NOTIFICATIONS && !notificationStatusLoaded) {
                                row.copy(value = "—")
                            } else {
                                row
                            },
                        showDivider = rowIndex < section.rows.lastIndex,
                        enabled =
                            !isAccountActionRunning &&
                                (row.id != SettingsRowId.USAGE_ANALYTICS || analyticsEnabled != null),
                        onClick = onClick@{
                            when (row.id) {
                                SettingsRowId.LANGUAGE -> {
                                    onDestination(SettingsDestination.LANGUAGE)
                                }

                                SettingsRowId.APPEARANCE -> {
                                    onDestination(SettingsDestination.APPEARANCE)
                                }

                                SettingsRowId.NOTIFICATIONS -> {
                                    onDestination(SettingsDestination.NOTIFICATIONS)
                                }

                                SettingsRowId.USAGE_ANALYTICS -> {
                                    onDestination(SettingsDestination.USAGE_ANALYTICS)
                                }

                                SettingsRowId.SEND_FEEDBACK -> {
                                    openExternalUri(FEEDBACK_URL) { opened ->
                                        if (!opened) onActionUnavailable(UnavailableAction.EMAIL)
                                    }
                                }

                                SettingsRowId.REPORT_INCORRECT_EXHIBITION -> {
                                    openExternalUri(REPORT_URL) { opened ->
                                        if (!opened) onActionUnavailable(UnavailableAction.EMAIL)
                                    }
                                }

                                SettingsRowId.SHARE_GALLR -> {
                                    onShareApp()
                                }

                                SettingsRowId.INSTAGRAM -> {
                                    openExternalUri(INSTAGRAM_URL) { opened ->
                                        if (!opened) onActionUnavailable(UnavailableAction.WEB_LINK)
                                    }
                                }

                                SettingsRowId.ABOUT_GALLR -> {
                                    openExternalUri(ABOUT_URL) { opened ->
                                        if (!opened) onActionUnavailable(UnavailableAction.WEB_LINK)
                                    }
                                }

                                SettingsRowId.PRIVACY_POLICY -> {
                                    openExternalUri(PRIVACY_POLICY_URL) { opened ->
                                        if (!opened) onActionUnavailable(UnavailableAction.WEB_LINK)
                                    }
                                }

                                SettingsRowId.SIGN_OUT -> {
                                    onSignOut()
                                }

                                SettingsRowId.DELETE_ACCOUNT -> {
                                    onDeleteAccount()
                                }

                                SettingsRowId.VERSION -> {
                                    return@onClick
                                }
                            }
                        },
                    )
                }
            }

            if (accountError != null) {
                item {
                    Text(
                        text = "! $accountError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = GallrSpacing.sm, start = GallrSpacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    row: SettingsRowModel,
    showDivider: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isInteractive = row.isDisclosure && enabled
    val foreground = if (isPressed) Color.Black else MaterialTheme.colorScheme.onBackground
    val secondaryForeground = if (isPressed) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
    val accessibilityModifier =
        if (row.id == SettingsRowId.USAGE_ANALYTICS && row.value != null) {
            Modifier.semantics(mergeDescendants = true) {
                stateDescription = row.value
            }
        } else {
            Modifier.semantics(mergeDescendants = true) {}
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(if (isPressed) GallrAccent.interactionFeedback else Color.Transparent)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = isInteractive,
                        role = Role.Button,
                        onClick = onClick,
                    ).then(accessibilityModifier)
                    .padding(horizontal = GallrSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
                modifier = Modifier.weight(1f),
            )
            if (row.value != null) {
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryForeground,
                    textAlign = TextAlign.End,
                    modifier =
                        if (row.id == SettingsRowId.USAGE_ANALYTICS) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                )
            }
            if (row.isDisclosure) {
                Spacer(Modifier.width(GallrSpacing.sm))
                Text(
                    text = "›",
                    color = foreground,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun UsageAnalyticsSettingsScreen(
    lang: AppLanguage,
    enabled: Boolean?,
    isSaving: Boolean,
    error: String?,
    onSelect: (Boolean) -> Unit,
    onActionUnavailable: (UnavailableAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val openExternalUri = rememberOpenExternalUri()
    val choices = listOf(false, true)

    SettingsScaffold(
        title = if (lang == AppLanguage.KO) "사용 분석" else "USAGE ANALYTICS",
        backContentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back",
        onBack = onBack,
        backEnabled = !isSaving,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = GallrSpacing.screenMargin,
                        end = GallrSpacing.screenMargin,
                        bottom = GallrSpacing.xl,
                    ),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "선택" else "SELECT ONE",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = GallrSpacing.sm, top = GallrSpacing.lg),
            )
            Text(
                text = analyticsDisclosureMessage(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        start = GallrSpacing.sm,
                        end = GallrSpacing.sm,
                        top = GallrSpacing.sm,
                        bottom = GallrSpacing.lg,
                    ),
            )
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                choices.forEachIndexed { index, choice ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .selectable(
                                    selected = enabled == choice,
                                    enabled = enabled != null && !isSaving,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(choice) },
                                ).padding(horizontal = GallrSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = analyticsChoiceLabel(choice, lang),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (enabled == choice) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.clearAndSetSemantics { },
                                )
                                HorizontalDivider(
                                    color = GallrAccent.activeIndicator,
                                    thickness = 2.dp,
                                    modifier = Modifier.width(24.dp).clearAndSetSemantics { },
                                )
                            }
                        }
                    }
                    if (index < choices.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            if (error != null) {
                GallrErrorMessage(
                    message = error,
                    modifier =
                        Modifier
                            .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.md)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Spacer(Modifier.height(GallrSpacing.md))
            }

            TextButton(
                onClick = {
                    openExternalUri(ANALYTICS_PRIVACY_POLICY_URL) { opened ->
                        if (!opened) onActionUnavailable(UnavailableAction.WEB_LINK)
                    }
                },
                shape = RectangleShape,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                Text(
                    text =
                        if (lang == AppLanguage.KO) {
                            "개인정보 처리방침 보기"
                        } else {
                            "Read Privacy Policy"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

internal fun analyticsDisclosureMessage(lang: AppLanguage): String =
    if (lang == AppLanguage.KO) {
        "계정과 연결되지 않은 제한된 사용 정보를 공유할지 선택하세요. 화면과 전시를 본 기록, 저장·방문·팔로우·" +
            "지도 연결이 완료된 기록, 공유 화면을 연 기록, 추천 결과 수와 순위 구간, 이동 경로의 대략적인 정류장 " +
            "수·거리·시간 구간만 포함합니다. 계정 정보, " +
            "검색어, 정확한 위치, 저장·방문 목록, 추천 취향 정보나 실제 이동 경로는 전송하지 않습니다. 사용 분석은 " +
            "기본적으로 꺼져 있으며, 공유하지 않아도 모든 기능을 사용할 수 있습니다."
    } else {
        "Choose whether to share limited usage information that isn’t linked to your account. This includes screens " +
            "and exhibitions shown; completed saves, visits, follows, and map handoffs; when sharing options open; " +
            "recommendation result counts and rank ranges; and coarse route size, distance and time ranges. It never " +
            "includes your account, searches, precise location, " +
            "saved or visited lists, recommendation profile, or route. Usage analytics is off by default, and every " +
            "feature works without it."
    }

internal fun analyticsChoiceLabel(
    enabled: Boolean,
    lang: AppLanguage,
): String =
    when {
        !enabled && lang == AppLanguage.KO -> "공유하지 않음"
        !enabled -> "Don’t share"
        lang == AppLanguage.KO -> "사용 분석 공유"
        else -> "Share usage analytics"
    }

private fun analyticsPreferenceErrorMessage(lang: AppLanguage): String =
    if (lang == AppLanguage.KO) {
        "변경을 완료하지 못했습니다. 사용 정보 수집은 중지되어 있습니다. 원하는 항목을 다시 선택해 주세요."
    } else {
        "Couldn’t finish this change. Usage collection is paused. Select your preferred option again to retry."
    }

@Composable
private fun <T> SettingsChoiceScreen(
    title: String,
    selectionLabel: String,
    backContentDescription: String,
    prompt: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(
        title = title,
        backContentDescription = backContentDescription,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = GallrSpacing.screenMargin),
        ) {
            Text(
                text = selectionLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = GallrSpacing.sm, top = GallrSpacing.lg),
            )
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        start = GallrSpacing.sm,
                        end = GallrSpacing.sm,
                        top = GallrSpacing.sm,
                        bottom = GallrSpacing.lg,
                    ),
            )
            choices.forEachIndexed { index, choice ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = choice == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(choice) },
                            ).padding(horizontal = GallrSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label(choice),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (choice == selected) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            HorizontalDivider(
                                color = GallrAccent.activeIndicator,
                                thickness = 2.dp,
                                modifier = Modifier.width(24.dp),
                            )
                        }
                    }
                }
                if (index < choices.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsScreen(
    lang: AppLanguage,
    enabled: Boolean,
    isLoaded: Boolean,
    onRequestPermission: () -> Unit,
    onActionUnavailable: (UnavailableAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val openAppSettings = rememberOpenAppSettings()

    SettingsScaffold(
        title = if (lang == AppLanguage.KO) "알림" else "NOTIFICATIONS",
        backContentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = GallrSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
        ) {
            Spacer(Modifier.height(GallrSpacing.sm))
            Text(
                text =
                    if (lang == AppLanguage.KO) {
                        if (enabled) "알림이 켜져 있습니다." else "전시 알림을 받아보세요."
                    } else {
                        if (enabled) "Notifications are on." else "Stay updated on saved exhibitions."
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text =
                    if (lang == AppLanguage.KO) {
                        if (enabled) {
                            "알림을 끄려면 기기의 시스템 설정을 이용하세요."
                        } else {
                            "북마크한 전시의 마감 소식을 알려드립니다."
                        }
                    } else {
                        if (enabled) {
                            "Use your device settings if you want to turn notifications off."
                        } else {
                            "gallr can remind you before bookmarked exhibitions close."
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick =
                    if (enabled) {
                        {
                            openAppSettings { opened ->
                                if (!opened) onActionUnavailable(UnavailableAction.SYSTEM_SETTINGS)
                            }
                        }
                    } else {
                        onRequestPermission
                    },
                enabled = enabled || isLoaded,
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text(
                    text =
                        if (enabled) {
                            if (lang == AppLanguage.KO) "시스템 설정 열기" else "Open system settings"
                        } else {
                            if (lang == AppLanguage.KO) "알림 허용" else "Allow notifications"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun unavailableActionTitle(
    action: UnavailableAction,
    lang: AppLanguage,
): String =
    when (action) {
        UnavailableAction.EMAIL -> if (lang == AppLanguage.KO) "이메일 앱을 열 수 없습니다" else "Email app unavailable"
        UnavailableAction.SYSTEM_SETTINGS -> if (lang == AppLanguage.KO) "설정을 열 수 없습니다" else "Settings unavailable"
        UnavailableAction.WEB_LINK -> if (lang == AppLanguage.KO) "링크를 열 수 없습니다" else "Link unavailable"
    }

private fun unavailableActionMessage(
    action: UnavailableAction,
    lang: AppLanguage,
): String =
    when (action) {
        UnavailableAction.EMAIL -> {
            if (lang == AppLanguage.KO) {
                "이 기기에 이메일 앱이 설정되어 있지 않습니다. hello@gallrmap.com으로 문의해 주세요."
            } else {
                "No email app is configured on this device. You can contact hello@gallrmap.com directly."
            }
        }

        UnavailableAction.SYSTEM_SETTINGS -> {
            if (lang == AppLanguage.KO) {
                "기기 설정에서 gallr를 선택한 다음 알림 설정을 변경해 주세요."
            } else {
                "Open your device Settings, choose gallr, then update Notifications."
            }
        }

        UnavailableAction.WEB_LINK -> {
            if (lang == AppLanguage.KO) {
                "브라우저를 열 수 없습니다. 연결 상태를 확인한 후 다시 시도해 주세요."
            } else {
                "The browser could not open this link. Check your connection and try again."
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    backContentDescription: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    modifier: Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = backEnabled,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = backContentDescription,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        content = content,
    )
}
