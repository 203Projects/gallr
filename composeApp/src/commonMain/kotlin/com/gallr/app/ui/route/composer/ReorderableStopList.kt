package com.gallr.app.ui.route.composer

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.route.routeLegLabel
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.map.EstimatedRouteLeg
import com.gallr.shared.route.EvaluatedStop
import com.gallr.shared.route.PersonalRouteEvaluation
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.RouteStopVerdict
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_drag_handle
import gallr.composeapp.generated.resources.ic_more_horiz
import org.jetbrains.compose.resources.painterResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The drag in progress over the composer's stops. Rows move in [order] while the author drags; the draft changes
 * once, on drop, so other writers never see a half-finished reorder (RR3).
 */
@Stable
internal class StopDragState(
    initialOrder: List<PersonalRouteStop>,
    private val haptics: HapticFeedback,
) {
    var order by mutableStateOf(initialOrder)
        internal set
    var draggingKey by mutableStateOf<String?>(null)
        private set
    private var startIndex = -1
    internal lateinit var reorderable: ReorderableLazyListState

    internal fun moveDuringDrag(
        fromKey: Any,
        toKey: Any,
    ) {
        val from = order.indexOfFirst { stopKey(it) == fromKey }
        val to = order.indexOfFirst { stopKey(it) == toKey }
        if (from < 0 || to < 0) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    internal fun start(key: String) {
        draggingKey = key
        startIndex = order.indexOfFirst { stopKey(it) == key }
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    /** Ends the drag and returns the committed move, or null when the stop is back where it started. */
    internal fun drop(): Pair<Int, Int>? {
        val key = draggingKey ?: return null
        draggingKey = null
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        val end = order.indexOfFirst { stopKey(it) == key }
        return (startIndex to end).takeIf { startIndex >= 0 && end >= 0 && startIndex != end }
    }
}

@Composable
internal fun rememberStopDragState(
    listState: LazyListState,
    stops: List<PersonalRouteStop>,
): StopDragState {
    val haptics = LocalHapticFeedback.current
    val drag = remember(haptics) { StopDragState(stops, haptics) }
    drag.reorderable =
        rememberReorderableLazyListState(listState) { from, to ->
            drag.moveDuringDrag(from.key, to.key)
        }
    LaunchedEffect(stops) {
        if (drag.draggingKey == null) drag.order = stops
    }
    return drag
}

/**
 * The itinerary rows: number, title, venue, leg, visit window and status, with a long-press drag handle and a
 * ⋯ menu whose actions are also screen-reader custom actions (DR-D23, DR-D26, DR-D29).
 */
internal fun LazyListScope.reorderableStops(
    drag: StopDragState,
    evaluation: PersonalRouteEvaluation?,
    blockedStopIds: Set<String>,
    language: AppLanguage,
    reduceMotion: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val evaluatedById = evaluation?.stops?.associateBy { it.stop.exhibitionId }.orEmpty()
    val legsById = evaluation?.legs?.associateBy { it.toExhibitionId }.orEmpty()
    val stops = drag.order
    items(stops, key = ::stopKey) { stop ->
        val index = stops.indexOf(stop)
        ReorderableItem(
            state = drag.reorderable,
            key = stopKey(stop),
            animateItemModifier =
                if (reduceMotion) Modifier else Modifier.animateItem(placementSpec = tween(REORDER_MILLIS)),
        ) { isDragging ->
            StopRow(
                index = index,
                stopCount = stops.size,
                stop = stop,
                evaluated = evaluatedById[stop.exhibitionId],
                refused = stop.exhibitionId in blockedStopIds,
                leg = legsById[stop.exhibitionId],
                language = language,
                isDragging = isDragging,
                handle =
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { drag.start(stopKey(stop)) },
                        onDragStopped = { drag.drop()?.let { (from, to) -> onMove(from, to) } },
                    ),
                onAction = { action ->
                    val target = action.targetFor(index, stops.size)
                    when {
                        action == ComposerStopAction.REMOVE -> onRemove(index)
                        target != null -> onMove(index, target)
                    }
                },
            )
        }
    }
}

@Composable
private fun StopRow(
    index: Int,
    stopCount: Int,
    stop: PersonalRouteStop,
    evaluated: EvaluatedStop?,
    refused: Boolean,
    leg: EstimatedRouteLeg?,
    language: AppLanguage,
    isDragging: Boolean,
    handle: Modifier,
    onAction: (ComposerStopAction) -> Unit,
) {
    // A stop the last save refused is marked even when the catalogue still lists it (E-D18).
    val status =
        evaluated?.let { stopStatusLine(it.verdict, language) }?.takeIf { it.isBlocking || !refused }
            ?: stopStatusLine(RouteStopVerdict.Unavailable, language).takeIf { refused }
    val actions =
        ComposerStopAction.entries.filter { it == ComposerStopAction.REMOVE || it.targetFor(index, stopCount) != null }
    val title = if (language == AppLanguage.KO) stop.nameKo else stop.nameEn.ifBlank { stop.nameKo }
    val venue = if (language == AppLanguage.KO) stop.venueNameKo else stop.venueNameEn.ifBlank { stop.venueNameKo }
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .then(if (isDragging) heldRowBorder() else Modifier)
                .semantics {
                    customActions =
                        actions.map { action ->
                            CustomAccessibilityAction(composerStopActionLabel(action, language)) {
                                onAction(action)
                                true
                            }
                        }
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(start = GallrSpacing.screenMargin, top = GallrSpacing.sm, bottom = GallrSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(STOP_NUMBER_WIDTH),
            )
            StopDetails(
                index = index,
                title = title,
                venue = venue,
                evaluated = evaluated,
                status = status,
                leg = leg,
                language = language,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    handle
                        .size(TOUCH_TARGET)
                        .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(Res.drawable.ic_drag_handle), contentDescription = null)
            }
            Box {
                val menuLabel =
                    if (language == AppLanguage.KO) "${index + 1}번 정류장 메뉴" else "Stop ${index + 1} actions"
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(TOUCH_TARGET).semantics { contentDescription = menuLabel },
                ) {
                    Icon(painter = painterResource(Res.drawable.ic_more_horiz), contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RectangleShape,
                    containerColor = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(composerStopActionLabel(action, language)) },
                            onClick = {
                                menuOpen = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A stop's title, venue, walk from the previous stop, visit window and status line, as the composer shows them. */
@Composable
internal fun StopDetails(
    index: Int,
    title: String,
    venue: String,
    evaluated: EvaluatedStop?,
    status: StopStatusLine?,
    leg: EstimatedRouteLeg?,
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color =
                if (status?.isBlocking == true) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = venue,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        leg?.let {
            Text(text = routeLegLabel(index, it, language), style = MaterialTheme.typography.bodySmall)
        }
        evaluated?.let { composerVisitWindow(it, language) }?.let { window ->
            Text(
                text = window,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status?.let { line -> StatusLine(line) }
    }
}

/** A stop in a route someone else made: the composer's row without the drag handle or menu (spec 089 DD1). */
@Composable
internal fun ReadOnlyStopRow(
    index: Int,
    stop: PersonalRouteStop,
    evaluated: EvaluatedStop?,
    leg: EstimatedRouteLeg?,
    language: AppLanguage,
) {
    val title = if (language == AppLanguage.KO) stop.nameKo else stop.nameEn.ifBlank { stop.nameKo }
    val venue = if (language == AppLanguage.KO) stop.venueNameKo else stop.venueNameEn.ifBlank { stop.venueNameKo }
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(
                        start = GallrSpacing.screenMargin,
                        end = GallrSpacing.screenMargin,
                        top = GallrSpacing.sm,
                        bottom = GallrSpacing.sm,
                    ),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(STOP_NUMBER_WIDTH),
            )
            StopDetails(
                index = index,
                title = title,
                venue = venue,
                evaluated = evaluated,
                status = evaluated?.let { stopStatusLine(it.verdict, language) },
                leg = leg,
                language = language,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Blocking lines are `bodySmall` with error semantics; "운영 시간 미확인" is an informational `labelSmall` (DR-D8). */
@Composable
private fun StatusLine(line: StopStatusLine) {
    if (line.isBlocking) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = GallrSpacing.xs).semantics { error(line.text) },
        )
    } else {
        Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GallrSpacing.xs),
        )
    }
}

/** The 2dp `interactionFeedback` border on the row being dragged (DR-D23). */
private fun heldRowBorder(): Modifier = Modifier.border(2.dp, GallrAccent.interactionFeedback, RectangleShape)

internal fun stopKey(stop: PersonalRouteStop): String = "stop-${stop.exhibitionId}"

private const val REORDER_MILLIS = 150
private val STOP_NUMBER_WIDTH = 40.dp
private val TOUCH_TARGET = 44.dp
