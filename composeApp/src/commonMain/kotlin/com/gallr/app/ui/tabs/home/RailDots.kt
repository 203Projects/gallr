package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.HomeVisualTokens

/**
 * The card a rail's dots mark (spec 091, R4): the first visible card while its hidden part is at most half the gap
 * between cards, otherwise the next one, so the dot follows the first card that reads as fully on screen.
 */
fun highlightedCard(
    firstVisibleIndex: Int,
    firstVisibleOffsetPx: Int,
    spacingPx: Int,
    count: Int,
): Int {
    if (count <= 0) return 0
    val hidden = firstVisibleOffsetPx.coerceAtLeast(0)
    val index = if (hidden <= spacingPx / 2) firstVisibleIndex else firstVisibleIndex + 1
    return index.coerceIn(0, count - 1)
}

/** Dots are a hint for a short rail: more than one card and at most the token limit. */
fun showsRailDots(count: Int): Boolean = count in 2..HomeVisualTokens.DOT_LIMIT

/**
 * A row of position dots under a rail, the highlighted one in `onBackground`, the rest `outlineVariant`, with no
 * animation and no semantics of its own: every card already announces its position.
 */
@Composable
internal fun RailDots(
    count: Int,
    highlighted: Int,
    modifier: Modifier = Modifier,
) {
    if (!showsRailDots(count)) return
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(HomeVisualTokens.dotSpacing),
        modifier = modifier.clearAndSetSemantics { },
    ) {
        repeat(count) { index ->
            Box(
                modifier =
                    Modifier
                        .size(HomeVisualTokens.dotSize)
                        .background(
                            color = if (index == highlighted) scheme.onBackground else scheme.outlineVariant,
                            shape = CircleShape,
                        ),
            )
        }
    }
}

/**
 * Where a paged carousel stands (the story carousel): one dot per page, the current page a wider pill in the active
 * indicator colour. It changes with the settled page, with no animation and no semantics of its own.
 */
@Composable
internal fun StoryDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    if (!showsRailDots(count)) return
    val idle = MaterialTheme.colorScheme.outlineVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(HomeVisualTokens.dotSpacing),
        modifier = modifier.clearAndSetSemantics { },
    ) {
        repeat(count) { index ->
            val isCurrent = index == current
            Box(
                modifier =
                    Modifier
                        .width(if (isCurrent) HomeVisualTokens.activeDotWidth else HomeVisualTokens.dotSize)
                        .height(HomeVisualTokens.dotSize)
                        .background(if (isCurrent) GallrAccent.activeIndicator else idle, CircleShape),
            )
        }
    }
}
