package com.gallr.app.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrFilterChip
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.TastePickSheetState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl

/** The taste sheet (spec 101 US1, contracts/taste-ui.md). Back or a tap outside closes it without a skip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TastePickSheet(
    state: TastePickSheetState,
    language: AppLanguage,
    onToggleTerm: (String) -> Unit,
    onToggleCover: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = GallrShapeTokens.sheetTop,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        TastePickSheetContent(state, language, onToggleTerm, onToggleCover, onConfirm, onSkip)
    }
}

/** The sheet's content, apart from the sheet itself, so it can be tested on its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TastePickSheetContent(
    state: TastePickSheetState,
    language: AppLanguage,
    onToggleTerm: (String) -> Unit,
    onToggleCover: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    val copy = tastePickSheetCopy(language, state.mode, state.remaining, state.selectionCount)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = copy.subline, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        if (state.terms.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
            ) {
                state.terms.forEach { term ->
                    GallrFilterChip(
                        selected = term.id in state.selectedTermIds,
                        onClick = { onToggleTerm(term.id) },
                        label = term.localizedName(language),
                        modifier = Modifier.heightIn(min = TARGET),
                    )
                }
            }
        }
        if (state.covers.isNotEmpty()) {
            Text(text = copy.coversLabel, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            state.covers.chunked(COVER_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
                    row.forEach { exhibition ->
                        TasteCover(
                            exhibition = exhibition,
                            language = language,
                            selected = exhibition.id in state.selectedExhibitionIds,
                            onToggle = { onToggleCover(exhibition.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(COVER_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        copy.remainingHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            copy.skip?.let { skip ->
                TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = TARGET).weight(1f)) {
                    Text(text = skip, color = scheme.onBackground)
                }
            }
            Button(
                onClick = onConfirm,
                enabled = state.canConfirm,
                shape = MaterialTheme.shapes.small,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = scheme.onBackground,
                        contentColor = scheme.background,
                    ),
                modifier = Modifier.heightIn(min = TARGET).weight(1f),
            ) {
                Text(text = copy.confirm)
            }
        }
    }
}

@Composable
private fun TasteCover(
    exhibition: Exhibition,
    language: AppLanguage,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(COVER_ASPECT_RATIO)
                    .clip(GallrShapeTokens.small)
                    .background(scheme.surfaceVariant)
                    .then(
                        if (selected) Modifier.border(2.dp, scheme.onBackground, GallrShapeTokens.small) else Modifier,
                    ),
        ) {
            exhibition.coverImageUrl?.let { url ->
                AsyncImage(
                    model = nativeSupabaseImageUrl(url),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (selected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(GallrSpacing.xs)
                            .size(CHECK_SIZE)
                            .clip(CircleShape)
                            .background(scheme.onBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✓", style = MaterialTheme.typography.labelSmall, color = scheme.background)
                }
            }
        }
        Text(
            text = exhibition.localizedName(language),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val COVER_COLUMNS = 3
private const val COVER_ASPECT_RATIO = 1f
private val TARGET = 44.dp
private val CHECK_SIZE = 18.dp
