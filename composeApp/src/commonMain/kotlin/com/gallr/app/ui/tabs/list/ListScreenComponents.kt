package com.gallr.app.ui.tabs.list

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.EventListBanner
import com.gallr.app.ui.components.rememberCyclingIndex
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlin.math.abs

@Composable
internal fun PromotedExhibitionBand(
    promotion: PromotedExhibition,
    exhibition: Exhibition,
    lang: AppLanguage,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var explanationVisible by remember(promotion.promotionId) { mutableStateOf(false) }
    val name = if (lang == AppLanguage.KO) promotion.nameKo else promotion.nameEn.ifEmpty { promotion.nameKo }
    val venue =
        if (lang ==
            AppLanguage.KO
        ) {
            promotion.venueNameKo
        } else {
            promotion.venueNameEn.ifEmpty { promotion.venueNameKo }
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .padding(GallrSpacing.md),
    ) {
        Text(
            text = if (lang == AppLanguage.KO) "내 주변 프로모션" else "PROMOTED NEAR YOU",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(GallrSpacing.xs))
        Text(
            text = if (lang == AppLanguage.KO) "유료 광고 · 하루 한 번만 표시" else "PAID AD · SHOWN AT MOST ONCE A DAY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = nativeSupabaseImageUrl(exhibition.coverImageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .width(104.dp)
                        .height(136.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(GallrSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text = venue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(GallrSpacing.md))
                Button(
                    onClick = onOpen,
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onBackground,
                            contentColor = MaterialTheme.colorScheme.background,
                        ),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        text = if (lang == AppLanguage.KO) "전시 보기" else "VIEW EXHIBITION",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        TextButton(
            onClick = { explanationVisible = !explanationVisible },
            shape = RectangleShape,
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "왜 이 광고가 보이나요?" else "Why am I seeing this?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (explanationVisible) {
            Text(
                text =
                    if (lang == AppLanguage.KO) {
                        "선택한 지역에서 열리는 전시의 유료 광고입니다. 추천 전시와 검색 순위에는 영향을 주지 않습니다."
                    } else {
                        "This is a paid placement for an exhibition in your selected area. It does not affect Featured or search ranking."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun listScreenContentPadding(navigationBarInset: Dp): PaddingValues =
    PaddingValues(
        start = GallrSpacing.md,
        top = GallrSpacing.md,
        end = GallrSpacing.md,
        bottom = GallrSpacing.md + navigationBarInset,
    )

internal fun filterVisibilityAfterUserScroll(
    currentlyVisible: Boolean,
    scrollDeltaY: Float,
    firstVisibleItemIndex: Int,
): Boolean =
    when {
        scrollDeltaY > 0f -> true
        scrollDeltaY < 0f && firstVisibleItemIndex > 0 -> false
        else -> currentlyVisible
    }

// ── Country dropdown ─────────────────────────────────────────────────────────

@Composable
internal fun CountryDropdown(lang: AppLanguage) {
    var expanded by remember { mutableStateOf(false) }
    val countries = listOf("대한민국" to "South Korea")
    val selected = countries.first()

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = (if (lang == AppLanguage.KO) selected.first else selected.second) + " ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.background,
            border =
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                ),
            shape = RectangleShape,
        ) {
            countries.forEach { (ko, en) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (lang == AppLanguage.KO) ko else en,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

// ── Filter chip ──────────────────────────────────────────────────────────────

@Composable
internal fun GallrFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = RectangleShape,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.background,
                labelColor = MaterialTheme.colorScheme.onBackground,
                selectedContainerColor = GallrAccent.activeIndicator,
                selectedLabelColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outline,
                selectedBorderColor = GallrAccent.activeIndicator,
                borderWidth = 1.dp,
                selectedBorderWidth = 1.dp,
            ),
    )
}

// ── Event filter chip (Phase 2b) ─────────────────────────────────────────────

@Composable
internal fun GallrEventFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    brandColor: Color,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = RectangleShape,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.background,
                labelColor = brandColor,
                selectedContainerColor = brandColor,
                selectedLabelColor = Color.White,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = brandColor,
                selectedBorderColor = brandColor,
                borderWidth = 1.dp,
                selectedBorderWidth = 1.dp,
            ),
    )
}

// ── Cycling event banner ─────────────────────────────────────────────────────

/** Auto-cycle interval for the list banner. Shared by the index timer
 *  ([rememberCyclingIndex]) and the progress-bar tween so they can't drift apart. */
private const val LIST_BANNER_INTERVAL_MS = 3500L

@Composable
internal fun CyclingEventBanner(
    events: List<Event>,
    lang: AppLanguage,
    onEventTap: (String) -> Unit,
) {
    val cycling = rememberCyclingIndex(events.size, intervalMillis = LIST_BANNER_INTERVAL_MS)
    val idx = cycling.index
    val current = events[idx]
    val autoCycle = !isReduceMotionOrScreenReaderActive()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(idx, events.size, autoCycle) {
        progress.snapTo(0f)
        if (events.size > 1 && autoCycle) {
            progress.animateTo(
                1f,
                animationSpec = tween(durationMillis = LIST_BANNER_INTERVAL_MS.toInt(), easing = LinearEasing),
            )
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(events.size) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var dx = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            dx += e.changes.sumOf { it.positionChange().x.toDouble() }.toFloat()
                            if (e.changes.none { it.pressed }) break
                        }
                        val threshold = viewConfiguration.touchSlop * 2.5f
                        // A swipe in either direction advances to the next event (the banner
                        // cycles one way) and works even when auto-cycle is gated off for
                        // reduced motion; a tap (no meaningful horizontal travel) opens detail.
                        if (abs(dx) > threshold) cycling.advance() else onEventTap(current.id)
                    }
                },
    ) {
        Crossfade(targetState = current, animationSpec = tween(180)) { ev ->
            EventListBanner(event = ev, lang = lang, modifier = Modifier.fillMaxSize())
        }
        if (events.size > 1) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.value)
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.35f)),
            )
        }
    }
}
