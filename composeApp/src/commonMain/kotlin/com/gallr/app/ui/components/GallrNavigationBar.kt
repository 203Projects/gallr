package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage

/**
 * Reductionist navigation bar.
 *
 * Active tab is indicated by a 4dp #FF5400 top border — the sole orange element in the nav bar.
 * Labels use Inter Medium (labelLarge), uppercase.
 */
@Composable
fun GallrNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val tabs = when (lang) {
        AppLanguage.KO -> listOf("추천", "목록", "지도", "프로필")
        AppLanguage.EN -> listOf("FEATURED", "LIST", "MAP", "PROFILE")
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            // Top hairline divider separating content from nav bar
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, label ->
                    GallrNavItem(
                        label = label,
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.GallrNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = 56.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Active indicator: 4dp #FF5400 top border (GallrAccent.activeIndicator)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    if (selected) GallrAccent.activeIndicator else MaterialTheme.colorScheme.background,
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = GallrSpacing.md),
        )
    }
}
