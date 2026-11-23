package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.gallr.app.AppTab
import com.gallr.app.BrowseFold
import com.gallr.app.browseTabFrames
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/**
 * Reductionist navigation bar.
 *
 * Active tab is indicated by a 4dp #FF5400 top border — the sole orange element in the nav bar.
 * Labels use Inter Medium (labelLarge), uppercase.
 */
@Composable
internal fun GallrNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
    folds: List<BrowseFold> = emptyList(),
) {
    val tabs =
        when (lang) {
            AppLanguage.KO -> listOf("홈", "목록", "지도", "MY")
            AppLanguage.EN -> listOf("HOME", "LIST", "MAP", "MY")
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
            if (folds.none(BrowseFold::vertical)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    AppTab.entries.zip(tabs).forEach { (tab, label) ->
                        GallrNavItem(
                            label = label,
                            selected = selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                FoldAwareTabRow(folds) {
                    AppTab.entries.zip(tabs).forEach { (tab, label) ->
                        GallrNavItem(
                            label = label,
                            selected = selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldAwareTabRow(
    folds: List<BrowseFold>,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Layout(
        content = content,
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow().round() },
    ) { items, constraints ->
        val width = constraints.maxWidth
        val viewport = IntRect(origin.x, origin.y, origin.x + width, origin.y + 56.dp.roundToPx())
        val frames = browseTabFrames(viewport, folds, items.size, 48.dp.roundToPx())
        val children =
            items.zip(frames).map { (item, frame) ->
                item.measure(Constraints.fixedWidth(frame.width))
            }
        layout(width, children.maxOf { it.height }) {
            children.zip(frames).forEach { (child, frame) -> child.place(frame.left, frame.top) }
        }
    }
}

@Composable
private fun GallrNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        if (selected) GallrAccent.activeIndicator else MaterialTheme.colorScheme.background,
                    ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = GallrSpacing.md),
        )
    }
}
