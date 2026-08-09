package com.gallr.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_email
import gallr.composeapp.generated.resources.ic_info
import gallr.composeapp.generated.resources.ic_language
import gallr.composeapp.generated.resources.ic_lock
import gallr.composeapp.generated.resources.ic_person
import gallr.composeapp.generated.resources.ic_settings
import gallr.composeapp.generated.resources.ic_share
import org.jetbrains.compose.resources.painterResource

private const val PRIVACY_POLICY_URL = "https://gallrmap.com/privacy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignUpNudgeSheet(
    lang: AppLanguage,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "전시 5개를 저장했어요."
                        AppLanguage.EN -> "You've saved 5 exhibitions."
                    },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "재설치하면 목록이 사라질 수 있어요. 로그인하면 안전하게 보관됩니다."
                        AppLanguage.EN -> "Sign in so your list doesn't disappear if you reinstall."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onSignIn,
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "gallr 로그인"
                            AppLanguage.EN -> "Sign in to gallr"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = onDismiss,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "나중에"
                            AppLanguage.EN -> "Not now"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
// ── Connect menu ────────────────────────────────────────────────────────────

@Composable
internal fun ConnectMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    lang: AppLanguage,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    shareHandler: ShareHandler,
) {
    Box {
        IconButton(onClick = onToggle) {
            Image(
                painter = painterResource(Res.drawable.ic_info),
                contentDescription = if (lang == AppLanguage.KO) "연결" else "Connect",
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RectangleShape,
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (lang == AppLanguage.KO) "인스타그램 팔로우" else "Follow on Instagram",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_person),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
                onClick = {
                    uriHandler.openUri("https://instagram.com/gallrmap")
                    onDismiss()
                },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onBackground),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (lang == AppLanguage.KO) "이메일 문의" else "Email us",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_email),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
                onClick = {
                    uriHandler.openUri("mailto:hello@gallrmap.com")
                    onDismiss()
                },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onBackground),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (lang == AppLanguage.KO) "앱 공유하기" else "Tell friends",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_share),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
                onClick = {
                    shareHandler.shareApp()
                    onDismiss()
                },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onBackground),
            )
        }
    }
}

// ── Settings menu ───────────────────────────────────────────────────────────

@Composable
internal fun SettingsMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    lang: AppLanguage,
    currentThemeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLanguageToggle: () -> Unit,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    Box {
        IconButton(onClick = onToggle) {
            Image(
                painter = painterResource(Res.drawable.ic_settings),
                contentDescription = if (lang == AppLanguage.KO) "설정" else "Settings",
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RectangleShape,
        ) {
            // ── Theme ──
            ThemeMode.entries.forEach { mode ->
                val label =
                    when (mode) {
                        ThemeMode.LIGHT -> if (lang == AppLanguage.KO) "테마: 라이트" else "Theme: Light"
                        ThemeMode.DARK -> if (lang == AppLanguage.KO) "테마: 다크" else "Theme: Dark"
                        ThemeMode.SYSTEM -> if (lang == AppLanguage.KO) "테마: 시스템" else "Theme: System"
                    }
                val isActive = currentThemeMode == mode
                if (isActive) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        },
                        onClick = {
                            val next =
                                when (mode) {
                                    ThemeMode.SYSTEM -> ThemeMode.LIGHT
                                    ThemeMode.LIGHT -> ThemeMode.DARK
                                    ThemeMode.DARK -> ThemeMode.SYSTEM
                                }
                            onThemeChange(next)
                        },
                        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onBackground),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // ── Language toggle ──
            DropdownMenuItem(
                text = {
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "언어: 한국어 → English"
                            } else {
                                "Language: English → 한국어"
                            },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_language),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
                onClick = onLanguageToggle,
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onBackground),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // ── Privacy policy ──
            DropdownMenuItem(
                text = {
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "개인정보 처리방침"
                            } else {
                                "Privacy Policy"
                            },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_lock),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
                onClick = {
                    uriHandler.openUri(PRIVACY_POLICY_URL)
                    onDismiss()
                },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onBackground),
            )
        }
    }
}
