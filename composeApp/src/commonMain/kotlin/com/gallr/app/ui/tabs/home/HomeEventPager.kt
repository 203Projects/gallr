package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.EventPromotionCard
import com.gallr.app.ui.theme.GallrEventCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The city-wide event promotion at the top of the home tab: one card that wraps its content, or a pager over
 * several that advances every four seconds unless motion is reduced or a screen reader is on.
 */
@Composable
internal fun HomeEventPager(
    activeEvents: List<Event>,
    pagerState: PagerState,
    lang: AppLanguage,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A HorizontalPager cannot wrap content, so it is pinned to the tallest card's natural height, measured
    // offstage below; the token seeds the first frame so nothing jumps once the measurement lands.
    val density = LocalDensity.current
    var maxCardHeightPx by remember(activeEvents) { mutableIntStateOf(0) }
    val pagerCardHeight =
        if (maxCardHeightPx > 0) with(density) { maxCardHeightPx.toDp() } else GallrEventCard.pagerHeight

    val autoCycle = !isReduceMotionOrScreenReaderActive()
    LaunchedEffect(pagerState, autoCycle) {
        if (!autoCycle) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collectLatest {
            if (pagerState.pageCount <= 1) return@collectLatest
            delay(EVENT_CYCLE_MILLIS)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin)) {
        if (activeEvents.size == 1) {
            EventPromotionCard(
                event = activeEvents[0],
                lang = lang,
                onTap = { onEventTap(activeEvents[0].id) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(Modifier.height(0.dp).clipToBounds()) {
                activeEvents.forEach { event ->
                    EventPromotionCard(
                        event = event,
                        lang = lang,
                        onTap = {},
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                .onSizeChanged { size ->
                                    if (size.height >
                                        maxCardHeightPx
                                    ) {
                                        maxCardHeightPx = size.height
                                    }
                                },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pagerCardHeight),
                verticalAlignment = Alignment.Top,
            ) { page ->
                EventPromotionCard(
                    event = activeEvents[page],
                    lang = lang,
                    onTap = { onEventTap(activeEvents[page].id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PagerDots(
                count = activeEvents.size,
                current = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth().height(GallrEventCard.dotsHeight),
            )
        }
    }
}

@Composable
private fun PagerDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            val scheme = MaterialTheme.colorScheme
            val dotColor = if (active) scheme.onBackground else scheme.outlineVariant
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(if (active) 18.dp else 6.dp)
                        .background(dotColor),
            )
        }
    }
}

/** Shown once the event pager has scrolled away: a way back to the events on now. */
@Composable
internal fun EventRevealChip(
    count: Int,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (lang == AppLanguage.KO) "${count}개의 이벤트 진행 중" else "$count Events On Now"
    Row(
        modifier =
            modifier
                .background(Color.Black)
                .clickable(onClick = onTap)
                .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↑ ", color = Color.White, style = MaterialTheme.typography.labelSmall)
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

private const val EVENT_CYCLE_MILLIS = 4_000L
