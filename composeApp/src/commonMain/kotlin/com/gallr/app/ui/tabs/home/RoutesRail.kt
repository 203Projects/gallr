package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.SectionHeader
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.home.HomeRoute
import com.gallr.shared.route.PublicRouteSummary

/**
 * The 지금 주목받는 동선 rail (spec 091, US2): the public ranking's first routes as compact cards. A card says who
 * made the route and how often it was copied, names it, gives its stop count and districts, and shows the covers
 * of its first stops; tapping opens the existing public route preview, and [action] opens 동선 둘러보기 (spec 092).
 */
@Composable
internal fun RoutesRail(
    title: String,
    subtitle: String,
    routes: List<HomeRoute>,
    lang: AppLanguage,
    onRouteTap: (PublicRouteSummary) -> Unit,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, subtitle = subtitle, action = action, onAction = onAction)
        Spacer(Modifier.height(GallrSpacing.md))
        LazyRow(
            contentPadding = PaddingValues(horizontal = GallrSpacing.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            items(routes.withIndex().toList(), key = { it.value.summary.id }) { (index, route) ->
                RouteCard(
                    route = route,
                    description = routeCardDescription(route.summary, index, routes.size, title, lang),
                    lang = lang,
                    onTap = { onRouteTap(route.summary) },
                )
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: HomeRoute,
    description: String,
    lang: AppLanguage,
    onTap: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val summary = route.summary
    // Larger type gets a wider card, as on the exhibition rails, so the name keeps its line.
    val cardWidth = ROUTE_CARD_WIDTH * LocalDensity.current.fontScale.coerceIn(1f, ROUTE_CARD_MAX_SCALE)
    Column(
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        modifier =
            Modifier
                .width(cardWidth)
                .clip(HomeVisualTokens.shape)
                .border(1.dp, scheme.outlineVariant, HomeVisualTokens.shape)
                .background(scheme.background)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = description }
                .padding(GallrSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = routeAuthorLine(summary, lang),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (summary.isEditor) FontWeight.Bold else FontWeight.Normal,
                color = if (summary.isEditor) scheme.onBackground else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            routeCopyCount(summary.copyCount30d, lang)?.let { copies ->
                Spacer(Modifier.width(GallrSpacing.sm))
                Text(
                    text = copies,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = summary.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${routeStopCount(summary.stopCount, lang)} · ${routeDistricts(summary, lang)}",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (route.shownCovers.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(GallrSpacing.xs + GallrSpacing.xs / 2)) {
                route.shownCovers.forEach { cover ->
                    AsyncImage(
                        model = nativeSupabaseImageUrl(cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(STOP_COVER_SIZE)
                                .clip(HomeVisualTokens.innerShape)
                                .background(scheme.surfaceVariant),
                    )
                }
                if (route.hiddenStopCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(STOP_COVER_SIZE)
                                .background(scheme.surfaceVariant, HomeVisualTokens.innerShape),
                    ) {
                        Text(
                            text = "+${route.hiddenStopCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val ROUTE_CARD_WIDTH = 262.dp

/** At 1.3× the card still leaves the next one peeking on a 360dp-wide phone. */
private const val ROUTE_CARD_MAX_SCALE = 1.3f
private val STOP_COVER_SIZE = 40.dp
