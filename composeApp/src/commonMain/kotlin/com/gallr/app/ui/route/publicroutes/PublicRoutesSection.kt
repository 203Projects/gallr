package com.gallr.app.ui.route.publicroutes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.route.composer.SkeletonRow
import com.gallr.app.ui.route.composer.myRoutesRetryLabel
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.PublicRoutesListState
import com.gallr.app.viewmodel.PublicRoutesUiState
import com.gallr.app.viewmodel.PublicRoutesViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate

/**
 * 추천 동선 in the Map route sheet (spec 089 US9): the top three ranked routes, expanding in place to the ten
 * fetched (DD2), with placeholders while loading, nothing when empty, and an error with retry (DD7, R8).
 *
 * This is a lazy item of the sheet that re-enters composition on every scroll back, so the sheet, not this
 * section, tells the ViewModel it was shown (RoutePlannerScreen.onShown).
 */
@Composable
fun PublicRoutesSectionRoute(
    viewModel: PublicRoutesViewModel,
    language: AppLanguage,
    onOpenRoute: (PublicRouteSummary) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PublicRoutesSection(
        state = state,
        language = language,
        onOpenRoute = { route ->
            viewModel.openPreview(route)
            onOpenRoute(route)
        },
        onToggleExpanded = viewModel::toggleExpanded,
        onRetry = viewModel::retry,
    )
}

@Composable
internal fun PublicRoutesSection(
    state: PublicRoutesUiState,
    language: AppLanguage,
    onOpenRoute: (PublicRouteSummary) -> Unit,
    onToggleExpanded: () -> Unit,
    onRetry: () -> Unit,
) {
    val reduceMotion = isReduceMotionOrScreenReaderActive()
    AnimatedContent(
        targetState = state.list,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                fadeIn(tween(CROSSFADE_MILLIS)) togetherWith fadeOut(tween(CROSSFADE_MILLIS))
            }
        },
        contentKey = { it::class },
        label = "public-routes",
    ) { list ->
        if (list == PublicRoutesListState.Hidden) return@AnimatedContent
        Column(modifier = Modifier.fillMaxWidth().padding(top = GallrSpacing.md)) {
            Text(
                text = publicRoutesHeading(language),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = publicRoutesOrderNote(language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (list) {
                PublicRoutesListState.Loading -> {
                    repeat(SKELETON_ROWS) { SkeletonRow() }
                }

                PublicRoutesListState.Error -> {
                    GallrErrorMessage(
                        message = publicRoutesLoadFailedMessage(language),
                        actionLabel = myRoutesRetryLabel(language),
                        onAction = onRetry,
                    )
                }

                is PublicRoutesListState.Loaded -> {
                    state.visibleRows.forEach { route ->
                        PublicRouteRow(route, state.today, language) { onOpenRoute(route) }
                    }
                    if (state.canExpand) ExpandButton(state.expanded, language, onToggleExpanded)
                }

                PublicRoutesListState.Hidden -> {
                    Unit
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = GallrSpacing.md),
            )
        }
    }
}

/** One ranked route: name, then districts, stops, author and copies, read as one description (DD5, D26). */
@Composable
private fun PublicRouteRow(
    route: PublicRouteSummary,
    today: LocalDate?,
    language: AppLanguage,
    onOpen: () -> Unit,
) {
    val line = publicRouteRowLine(route, today ?: route.firstSharedDay, language)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .semantics(mergeDescendants = true) {}
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = GallrSpacing.sm),
    ) {
        Text(
            text = route.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = MAX_ROW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = MAX_ROW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ExpandButton(
    expanded: Boolean,
    language: AppLanguage,
    onToggle: () -> Unit,
) {
    val state = publicRoutesExpandedState(expanded, language)
    TextButton(
        onClick = onToggle,
        shape = RectangleShape,
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .semantics { stateDescription = state },
    ) {
        Text(
            text = publicRoutesExpandLabel(expanded, language),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private const val SKELETON_ROWS = 3
private const val CROSSFADE_MILLIS = 200
private const val MAX_ROW_LINES = 2
private val ROW_MIN_HEIGHT = 52.dp
