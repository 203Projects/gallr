package com.gallr.app.ui.mygallr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.GalleryCandidate
import com.gallr.app.viewmodel.MyGallrUiState
import com.gallr.shared.data.model.AppLanguage

@Composable
fun AddGalleriesScreen(
    state: MyGallrUiState,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = state.language
    val focusManager = LocalFocusManager.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(text = "←", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = if (lang == AppLanguage.KO) "갤러리 추가" else "ADD GALLERIES",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedTextField(
            value = state.gallerySearchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(if (lang == AppLanguage.KO) "갤러리 검색" else "SEARCH GALLERIES")
            },
            trailingIcon = {
                if (state.gallerySearchQuery.isNotEmpty()) {
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
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.onBackground,
                ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
        )

        Spacer(Modifier.height(GallrSpacing.md))

        if (state.availableGalleryCandidates.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text =
                        if (lang == AppLanguage.KO) {
                            "추가할 갤러리를 찾지 못했습니다."
                        } else {
                            "No galleries available to add."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.availableGalleryCandidates, key = { it.galleryKey }) { candidate ->
                    GallerySelectionRow(
                        candidate = candidate,
                        lang = lang,
                        selected = candidate.galleryKey in state.selectedGalleryKeys,
                        onClick = { onToggleSelection(candidate.galleryKey) },
                    )
                }
            }
        }

        if (state.followSaveFailed) {
            GallrErrorMessage(
                message =
                    if (lang == AppLanguage.KO) {
                        "갤러리를 저장하지 못했습니다. 다시 시도해 주세요."
                    } else {
                        "Couldn’t save galleries. Try again."
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
                enabled = state.canSaveFollows,
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = GallrAccent.ctaPrimary,
                        contentColor = GallrAccent.ctaContent,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.isSavingFollows) {
                    CircularProgressIndicator(
                        color = GallrAccent.ctaContent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "${state.selectedGalleryKeys.size}개 갤러리 추가"
                                AppLanguage.EN -> "ADD ${state.selectedGalleryKeys.size} GALLERIES"
                            },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun GallerySelectionRow(
    candidate: GalleryCandidate,
    lang: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        GallerySelectionBox(selected = selected)
        GalleryMonogram(name = candidate.snapshot.localizedName(lang))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.snapshot.localizedName(lang),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.snapshot.localizedLocation(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "전시 ${candidate.exhibitions.size}개"
                        AppLanguage.EN -> "${candidate.exhibitions.size} EXHIBITIONS"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun GalleryMonogram(name: String) {
    Box(
        modifier = Modifier.size(56.dp).border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(3).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun GallerySelectionBox(selected: Boolean) {
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
                ).border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
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
