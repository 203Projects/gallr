package com.gallr.app.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.DiaryEntryFailure
import com.gallr.app.viewmodel.DiaryVisitRowState
import com.gallr.shared.data.model.AppLanguage

/**
 * The exhibition page's diary row (DESIGN.md "Diary row on the exhibition page"): one button between hairlines that
 * opens the entry sheet, reading whether and when the visitor went. A failed save or delete takes the row's place until
 * the next try; the row still opens the sheet so the visitor can try again.
 */
@Composable
internal fun DiaryVisitRow(
    state: DiaryVisitRowState,
    failure: DiaryEntryFailure?,
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_HEIGHT)
                    .clickable(role = Role.Button, onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (failure != null) {
                val message = diaryEntryFailureText(failure, language)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackground,
                    modifier =
                        Modifier.weight(1f).semantics {
                            error(message)
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            } else {
                Text(
                    text = rowText(state, language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.heightIn(min = TARGET).padding(start = GallrSpacing.sm),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = if (state == DiaryVisitRowState.NoEntry) "→" else DiaryRowCopy.edit(language),
                        style =
                            if (state == DiaryVisitRowState.NoEntry) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                        color = scheme.onBackground,
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
    }
}

private fun rowText(
    state: DiaryVisitRowState,
    language: AppLanguage,
): String =
    when (state) {
        DiaryVisitRowState.NoEntry -> DiaryRowCopy.noEntry(language)
        is DiaryVisitRowState.Dated -> DiaryRowCopy.dated(state.date, state.feeling, language)
        is DiaryVisitRowState.Undated -> DiaryRowCopy.undated(state.feeling, language)
    }

private val ROW_HEIGHT = 52.dp
private val TARGET = 44.dp
