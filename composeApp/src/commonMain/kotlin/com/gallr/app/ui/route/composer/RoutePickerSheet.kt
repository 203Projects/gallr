package com.gallr.app.ui.route.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.CatalogLoadingState
import com.gallr.app.ui.components.CatalogUnavailableState
import com.gallr.app.ui.components.leadingSelectionBar
import com.gallr.app.ui.tabs.list.GallrFilterChip
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate

/**
 * Picks several exhibitions at once (DR-D9, DR-D22). Reuses the List tab's search matching, filter chips, loading
 * state and quiet recovery line. Selected rows show the leading `activeIndicator` bar and announce "선택됨"; rows
 * already in the route, without a location, or beyond ten stops cannot be selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutePickerSheet(
    catalogue: ExhibitionListState,
    routeStopIds: Set<String>,
    today: LocalDate,
    language: AppLanguage,
    onRetry: () -> Unit,
    onAdd: (List<Exhibition>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var region by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(PICKER_HEIGHT_FRACTION)) {
            Text(
                text = pickerTitle(language),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(GallrSpacing.screenMargin),
            )
            PickerSearchField(query, language) { query = it }
            Text(
                text = pickerHelperText(language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.xs),
            )
            when (catalogue) {
                ExhibitionListState.Loading -> {
                    CatalogLoadingState(lang = language, modifier = Modifier.weight(1f))
                }

                is ExhibitionListState.Error -> {
                    CatalogUnavailableState(
                        isNetworkError = catalogue.message == "network",
                        lang = language,
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }

                is ExhibitionListState.Success -> {
                    val candidates = remember(catalogue, today) { pickerCandidates(catalogue.exhibitions, today) }
                    val regions = remember(candidates, language) { pickerRegions(candidates, language) }
                    RegionChips(regions, region, language) { region = it }
                    PickerResults(
                        results = pickerResults(candidates, query, region),
                        selectedIds = selected.toSet(),
                        routeStopIds = routeStopIds,
                        language = language,
                        onToggle = { exhibition ->
                            val next = toggledSelection(selected.toSet(), exhibition, routeStopIds)
                            selected =
                                if (exhibition.id in next) selected + exhibition.id else selected - exhibition.id
                        },
                        onResetFilters = {
                            query = ""
                            region = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                    val byId = catalogue.exhibitions.associateBy { it.id }
                    PickerCtaBar(
                        cta = pickerCta(selected.size, routeStopIds.size, language),
                        onAdd = { onAdd(selected.mapNotNull(byId::get)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerSearchField(
    query: String,
    language: AppLanguage,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                text = if (language == AppLanguage.KO) "전시 검색..." else "Search exhibitions...",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RectangleShape,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = MaterialTheme.colorScheme.onBackground,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
    )
}

@Composable
private fun RegionChips(
    regions: List<PickerRegion>,
    selectedRegion: String?,
    language: AppLanguage,
    onSelect: (String?) -> Unit,
) {
    if (regions.size < 2) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = GallrSpacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        modifier = Modifier.padding(vertical = GallrSpacing.xs),
    ) {
        item(key = "all") {
            GallrFilterChip(
                selected = selectedRegion == null,
                onClick = { onSelect(null) },
                label = if (language == AppLanguage.KO) "전체" else "ALL",
                small = true,
            )
        }
        items(regions, key = PickerRegion::key) { region ->
            GallrFilterChip(
                selected = region.key == selectedRegion,
                onClick = { onSelect(if (region.key == selectedRegion) null else region.key) },
                label = region.label,
                small = true,
            )
        }
    }
}

@Composable
private fun PickerResults(
    results: List<Exhibition>,
    selectedIds: Set<String>,
    routeStopIds: Set<String>,
    language: AppLanguage,
    onToggle: (Exhibition) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(GallrSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            Text(
                text = if (language == AppLanguage.KO) "검색 결과가 없어요" else "NO MATCHING EXHIBITIONS",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onResetFilters, shape = RectangleShape, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(
                    text = if (language == AppLanguage.KO) "필터 초기화" else "CLEAR FILTERS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(results, key = Exhibition::id) { exhibition ->
            PickerRow(
                exhibition = exhibition,
                state = pickerRowState(exhibition, selectedIds, routeStopIds),
                language = language,
                onToggle = { onToggle(exhibition) },
            )
        }
    }
}

@Composable
private fun PickerRow(
    exhibition: Exhibition,
    state: PickerRowState,
    language: AppLanguage,
    onToggle: () -> Unit,
) {
    val selected = state == PickerRowState.SELECTED
    val dimmed = !state.isEnabled
    val textColor =
        if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground
    val selectedLabel = pickerSelectedAnnouncement(language)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .toggleable(
                    value = selected,
                    enabled = state.isEnabled,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ).semantics { if (selected) stateDescription = selectedLabel }
                .leadingSelectionBar(selected)
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exhibition.localizedName(language),
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = exhibition.localizedVenueName(language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        pickerRowLabel(state, language)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GallrSpacing.sm),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PickerCtaBar(
    cta: PickerCta,
    onAdd: () -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = onAdd,
            enabled = cta.enabled,
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
                    .heightIn(min = 52.dp),
        ) {
            Text(cta.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val PICKER_HEIGHT_FRACTION = 0.92f
