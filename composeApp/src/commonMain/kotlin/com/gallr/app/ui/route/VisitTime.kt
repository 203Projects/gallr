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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.tabs.list.GallrFilterChip
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.RouteVisitTime
import com.gallr.shared.route.VisitTimeOptions
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil

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
        shape = RectangleShape,
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

internal fun visitTimeRowLabel(
    visitTime: RouteVisitTime?,
    today: LocalDate,
    language: AppLanguage,
): String {
    val value =
        when {
            visitTime == null -> {
                visitTimeNowChipLabel(language)
            }

            today.daysUntil(visitTime.day) in 0..1 -> {
                "${visitTimeDayChipLabel(visitTime.day, today, language)} ${clockText(visitTime.start)}"
            }

            else -> {
                datedClock(visitTime, language)
            }
        }
    return if (language == AppLanguage.KO) "방문 시간 · $value" else "VISIT TIME · $value"
}

internal fun visitTimeNowChipLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "지금" else "NOW"

internal fun visitTimeDayChipLabel(
    day: LocalDate,
    today: LocalDate,
    language: AppLanguage,
): String =
    when (today.daysUntil(day)) {
        0 -> if (language == AppLanguage.KO) "오늘" else "TODAY"
        1 -> if (language == AppLanguage.KO) "내일" else "TOMORROW"
        else -> "${weekdayShort(day.dayOfWeek, language)} ${monthDay(day)}"
    }

/** The summary's reference line for a chosen time: "10.18(토) 13:00 기준" / "SAT 10.18 · FROM 13:00". */
internal fun visitTimeReference(
    visitTime: RouteVisitTime,
    language: AppLanguage,
): String {
    val weekday = weekdayShort(visitTime.day.dayOfWeek, language)
    val date = monthDay(visitTime.day)
    val clock = clockText(visitTime.start)
    return when (language) {
        AppLanguage.KO -> "$date($weekday) $clock 기준"
        AppLanguage.EN -> "$weekday $date · FROM $clock"
    }
}

internal fun visitTimeSheetTitle(language: AppLanguage): String =
    if (language == AppLanguage.KO) "언제 가세요?" else "WHEN ARE YOU GOING?"

internal fun visitTimeNoTimesLeft(language: AppLanguage): String =
    if (language == AppLanguage.KO) "오늘은 더 고를 시간이 없어요" else "No more times today"

internal fun visitTimePassedMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) {
        "선택한 날짜가 지나 지금 기준으로 돌아왔어요"
    } else {
        "That day has passed, so the route is checked from now"
    }

private fun datedClock(
    visitTime: RouteVisitTime,
    language: AppLanguage,
): String {
    val weekday = weekdayShort(visitTime.day.dayOfWeek, language)
    val date = monthDay(visitTime.day)
    val clock = clockText(visitTime.start)
    return when (language) {
        AppLanguage.KO -> "$date($weekday) $clock"
        AppLanguage.EN -> "$weekday $date $clock"
    }
}

private fun monthDay(day: LocalDate): String = "${day.month.ordinal + 1}.${day.day.toString().padStart(2, '0')}"

private fun clockText(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

private fun weekdayShort(
    day: DayOfWeek,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (day) {
                DayOfWeek.MONDAY -> "월"
                DayOfWeek.TUESDAY -> "화"
                DayOfWeek.WEDNESDAY -> "수"
                DayOfWeek.THURSDAY -> "목"
                DayOfWeek.FRIDAY -> "금"
                DayOfWeek.SATURDAY -> "토"
                DayOfWeek.SUNDAY -> "일"
            }
        }

        AppLanguage.EN -> {
            day.name.take(3)
        }
    }

private val ROW_TARGET = 44.dp
