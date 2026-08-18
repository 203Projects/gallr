package com.gallr.app.ui.mygallr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.MyGallrUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl

@Composable
fun AddPastVisitsScreen(
    state: MyGallrUiState,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = state.language
    val focusManager = LocalFocusManager.current
    val backDescription = if (lang == AppLanguage.KO) "뒤로" else "Back"
    val saveLabel =
        when (lang) {
            AppLanguage.KO -> "${state.selectedExhibitionIds.size}개 방문 저장"
            AppLanguage.EN -> "SAVE ${state.selectedExhibitionIds.size} VISITS"
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = backDescription },
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text = if (lang == AppLanguage.KO) "지난 전시 추가" else "ADD PAST VISITS",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(if (lang == AppLanguage.KO) "전시 또는 갤러리 검색" else "SEARCH EXHIBITIONS OR GALLERIES")
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            shape = RectangleShape,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.onBackground,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin),
        )

        Spacer(Modifier.height(GallrSpacing.md))

        if (state.availableExhibitions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text =
                        if (lang == AppLanguage.KO) {
                            "추가할 전시를 찾지 못했습니다."
                        } else {
                            "No exhibitions available to add."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.availableExhibitions, key = { it.id }) { exhibition ->
                    ExhibitionSelectionRow(
                        exhibition = exhibition,
                        lang = lang,
                        selected = exhibition.id in state.selectedExhibitionIds,
                        onToggle = { onToggleSelection(exhibition.id) },
                    )
                }
            }
        }

        if (state.saveFailed) {
            GallrErrorMessage(
                message =
                    if (lang == AppLanguage.KO) {
                        "방문 기록을 저장하지 못했습니다. 다시 시도해 주세요."
                    } else {
                        "Couldn’t save your visits. Try again."
                    },
                modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
            )
            Spacer(Modifier.height(GallrSpacing.sm))
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md),
        ) {
            Button(
                onClick = onSave,
                enabled = state.canSave,
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = GallrAccent.ctaPrimary,
                        contentColor = GallrAccent.ctaContent,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = GallrAccent.ctaContent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = saveLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExhibitionSelectionRow(
    exhibition: Exhibition,
    lang: AppLanguage,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val selectionState =
        when (lang) {
            AppLanguage.KO -> if (selected) "선택됨" else "선택되지 않음"
            AppLanguage.EN -> if (selected) "Selected" else "Not selected"
        }
    val selectionDescription =
        listOf(
            exhibition.localizedName(lang),
            exhibition.localizedVenueName(lang),
            exhibition.localizedDateRange(lang),
        ).filter(String::isNotBlank).joinToString(", ")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ).clearAndSetSemantics {
                    contentDescription = selectionDescription
                    role = Role.Checkbox
                    stateDescription = selectionState
                    onClick {
                        onToggle()
                        true
                    }
                }.padding(
                    horizontal = GallrSpacing.screenMargin,
                    vertical = GallrSpacing.sm,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        SelectionBox(selected = selected)
        val imageUrl = exhibition.coverImageUrl
        if (imageUrl.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            AsyncImage(
                model = nativeSupabaseImageUrl(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exhibition.localizedName(lang),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = exhibition.localizedVenueName(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = exhibition.localizedDateRange(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SelectionBox(selected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .background(
                    if (selected) {
                        GallrAccent.activeIndicator
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                ).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelLarge,
                color = GallrAccent.ctaContent,
            )
        }
    }
}
