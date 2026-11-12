package com.gallr.app.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.HiddenAuthorsUiState
import com.gallr.shared.data.model.AppLanguage

/** Settings → 숨긴 사용자 (spec 103 FR-024): one row per hidden person with 숨기기 해제. */
@Composable
internal fun HiddenAuthorsScreen(
    lang: AppLanguage,
    state: HiddenAuthorsUiState,
    onUnhide: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ko = lang == AppLanguage.KO
    SettingsScaffold(
        title = if (ko) "숨긴 사용자" else "HIDDEN PEOPLE",
        backContentDescription = if (ko) "뒤로" else "Back",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = GallrSpacing.screenMargin,
                    end = GallrSpacing.screenMargin,
                    top = GallrSpacing.lg,
                    bottom = GallrSpacing.xl,
                ),
        ) {
            if (state.unhideFailed) {
                item(key = "unhide-failed") {
                    Text(
                        text =
                            if (ko) {
                                "! 숨기기를 해제하지 못했어요. 다시 시도해 주세요."
                            } else {
                                "! Couldn't unhide. Please try again."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier =
                            Modifier
                                .padding(bottom = GallrSpacing.sm)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (state.hasLoaded && state.authors.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (ko) "숨긴 사용자가 없어요" else "No hidden people",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(items = state.authors, key = { _, author -> author.userId }) { index, author ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = author.displayName.ifBlank { if (ko) "익명" else "Anonymous" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onUnhide(author.userId) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = TARGET),
                    ) {
                        Text(
                            text = if (ko) "숨기기 해제" else "UNHIDE",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                if (index < state.authors.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private val ROW_MIN_HEIGHT = 52.dp
private val TARGET = 44.dp
