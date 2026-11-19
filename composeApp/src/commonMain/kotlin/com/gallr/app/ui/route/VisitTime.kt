package com.gallr.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrFilterChip
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.RouteVisitTime
import com.gallr.shared.route.VisitTimeOptions
import kotlinx.datetime.LocalDate

/** The row under a route summary that opens the visit time sheet (spec 096 contracts/visit-time-control.md). */
@Composable
internal fun VisitTimeRow(
    visitTime: RouteVisitTime?,
    today: LocalDate,
    language: AppLanguage,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "${visitTimeRowLabel(visitTime, today, language)} ›",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
            modifier
                .heightIn(min = ROW_TARGET)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = GallrSpacing.sm),
    )
}

/** The visit time row under a route summary, and the sheet it opens. */
@Composable
internal fun VisitTimeControl(
    visitTime: RouteVisitTime?,
    options: VisitTimeOptions,
    today: LocalDate,
    language: AppLanguage,
    onChoose: (RouteVisitTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    VisitTimeRow(visitTime, today, language, onOpen = { open = true }, modifier = modifier)
    if (open) {
        VisitTimeSheet(
            options = options,
            selected = visitTime,
            today = today,
            language = language,
            onChoose = { chosen ->
                open = false
                onChoose(chosen)
            },
            onDismiss = { open = false },
        )
    }
}

/** "언제 가세요?": choose now, or a day and a start time; choosing now or a time closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisitTimeSheet(
    options: VisitTimeOptions,
    selected: RouteVisitTime?,
    today: LocalDate,
    language: AppLanguage,
    onChoose: (RouteVisitTime?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = GallrShapeTokens.sheetTop,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        VisitTimeSheetContent(options, selected, today, language, onChoose)
    }
}

/** The sheet's content, apart from the sheet itself, so it can be tested on its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VisitTimeSheetContent(
    options: VisitTimeOptions,
    selected: RouteVisitTime?,
    today: LocalDate,
    language: AppLanguage,
    onChoose: (RouteVisitTime?) -> Unit,
) {
    // Null while "지금" is the choice; the times shown are then today's.
    var chosenDay by remember { mutableStateOf(selected?.day) }
    val shownDay = chosenDay ?: today
    val times = options.timesFor(shownDay)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = GallrSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        Text(
            text = visitTimeSheetTitle(language),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin).semantics { heading() },
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = GallrSpacing.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VisitTimeChip(
                label = visitTimeNowChipLabel(language),
                selected = chosenDay == null,
                onClick = { onChoose(null) },
            )
            options.days.forEach { day ->
                VisitTimeChip(
                    label = visitTimeDayChipLabel(day, today, language),
                    selected = chosenDay == day,
                    onClick = { chosenDay = day },
                )
            }
        }
        if (times.isEmpty()) {
            Text(
                text = visitTimeNoTimesLeft(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin),
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
            ) {
                times.forEach { time ->
                    VisitTimeChip(
                        label = clockText(time),
                        selected = chosenDay != null && selected == RouteVisitTime(shownDay, time),
                        onClick = { onChoose(RouteVisitTime(shownDay, time)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VisitTimeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GallrFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = Modifier.heightIn(min = ROW_TARGET).semantics { role = Role.RadioButton },
    )
}

private val ROW_TARGET = 44.dp
