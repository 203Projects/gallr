package com.gallr.app.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
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
import com.gallr.app.viewmodel.TastePickMode
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
    onReleaseTerm: (String) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = GallrShapeTokens.sheetTop,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        TastePickSheetContent(state, language, onToggleTerm, onToggleCover, onConfirm, onSkip, onReleaseTerm)
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
    onReleaseTerm: (String) -> Unit = {},
) {
    val copy =
        tastePickSheetCopy(
            language,
            state.mode,
            state.remaining,
            state.selectionCount,
            hasTerms = state.terms.isNotEmpty(),
        )
    val scheme = MaterialTheme.colorScheme
    // The choices scroll; the count and the buttons stay pinned, so start and skip are always in reach.
    val choicesScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(choicesScroll)
                    .padding(horizontal = GallrSpacing.screenMargin)
                    .padding(top = GallrSpacing.lg, bottom = GallrSpacing.md),
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
                            selectedColor = scheme.onBackground,
                            modifier = Modifier.heightIn(min = TARGET),
                        )
                    }
                }
            }
            if (state.covers.isNotEmpty()) {
                Text(
                    text = copy.coversLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
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
            if (state.seeingLess.isNotEmpty()) {
                Text(
                    text = copy.seeingLess,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                ) {
                    state.seeingLess.forEach { term ->
                        ReleaseChip(
                            label = "${term.localizedName(language)} · ${copy.release}",
                            onClick = { onReleaseTerm(term.id) },
                        )
                    }
                }
            }
        }
        // A hairline marks where the choices continue under the pinned footer.
        if (choicesScroll.canScrollForward || choicesScroll.canScrollBackward) {
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(Modifier.height(GallrSpacing.sm))
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin)
                    .padding(bottom = GallrSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            // One polite line for the selection: how many more a first run needs, otherwise how many are picked.
            Text(
                text = copy.remainingHint ?: copy.selectedCount,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                copy.skip?.let { skip ->
                    TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = TARGET).weight(1f)) {
                        Text(text = skip, style = MaterialTheme.typography.bodyMedium, color = scheme.onBackground)
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
                    Text(text = copy.confirm, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Releases a term the visitor sees less of: an action, so it reads as a button, not as a choice to toggle. It
 * stands at the term chips' height so the two rows of outline chips line up.
 */
@Composable
private fun ReleaseChip(
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = TARGET)
                .clip(GallrShapeTokens.small)
                .clickable(role = Role.Button, onClick = onClick)
                .border(1.dp, scheme.outline, GallrShapeTokens.small)
                .padding(horizontal = GallrSpacing.md),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = scheme.onBackground)
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
