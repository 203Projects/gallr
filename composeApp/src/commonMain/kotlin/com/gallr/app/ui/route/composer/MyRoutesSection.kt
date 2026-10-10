package com.gallr.app.ui.route.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallr.app.ShareHandler
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.DraftRouteRow
import com.gallr.app.viewmodel.ListingMessage
import com.gallr.app.viewmodel.MyRoutesUiState
import com.gallr.app.viewmodel.MyRoutesViewModel
import com.gallr.app.viewmodel.SavedRoutesState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PersonalRouteSummary
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_more_horiz
import org.jetbrains.compose.resources.painterResource

/** Where the route list sits: under a "내 동선" heading in the route planner, or as the MY tab's 동선 section. */
enum class MyRoutesLayout { PLANNER, ARCHIVE }

/**
 * The "내 동선" list (spec 089 US4, DR-D10): the unsaved draft first, then the account's saved routes with open,
 * share and delete. Navigation, sign-in and sharing are the caller's.
 */
@Composable
fun MyRoutesSectionRoute(
    viewModel: MyRoutesViewModel,
    language: AppLanguage,
    shareHandler: ShareHandler,
    darkCard: Boolean,
    onOpenComposer: () -> Unit,
    onSignIn: () -> Unit,
    layout: MyRoutesLayout = MyRoutesLayout.PLANNER,
    modifier: Modifier = Modifier,
    onSeeAll: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The MY tab section is shown once per selection. In the route sheet this is a lazy item that re-enters
    // composition on every scroll back, so the sheet asks for the read instead (RoutePlannerScreen.onShown).
    if (layout == MyRoutesLayout.ARCHIVE) LaunchedEffect(Unit) { viewModel.sectionShown() }
    LaunchedEffect(state.openComposer) {
        if (state.openComposer) {
            viewModel.onComposerOpened()
            onOpenComposer()
        }
    }
    LaunchedEffect(state.signInRequested) {
        if (state.signInRequested) {
            viewModel.onSignInRequestHandled()
            onSignIn()
        }
    }
    RouteShareEffect(state.sharePayload, language, shareHandler, darkCard, onConsumed = viewModel::onShareSheetShown)

    val snackbarHostState = remember { SnackbarHostState() }
    ListingMessageEffect(
        message = state.listingMessage,
        hostState = snackbarHostState,
        language = language,
        onRetry = viewModel::retryListing,
        onShown = viewModel::dismissListingMessage,
    )
    // The snackbar overlays the section: in the route sheet's scrolling column a weighted section would
    // measure to nothing, while the MY tab still gives it the full height through [modifier].
    Box(modifier = modifier) {
        MyRoutesSection(
            state = state,
            language = language,
            layout = layout,
            onNewRoute = viewModel::startNewRoute,
            onOpenDraft = onOpenComposer,
            onOpen = viewModel::open,
            onShare = viewModel::share,
            onDelete = viewModel::delete,
            onListingAction = { route, action ->
                when (action) {
                    MyRouteListingAction.UNLIST -> viewModel.withdrawListing(route)
                    MyRouteListingAction.EDIT -> viewModel.open(route)
                    else -> viewModel.requestListing(route)
                }
            },
            onRetry = viewModel::refresh,
            onSignIn = onSignIn,
            onDismissError = viewModel::dismissError,
            onSeeAll = onSeeAll,
        )
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter)) { data ->
            Snackbar(snackbarData = data, shape = RectangleShape)
        }
    }

    state.confirmListing?.let { consent ->
        ListingConsentDialog(
            consent = consent,
            language = language,
            onConfirm = viewModel::confirmListing,
            onDismiss = viewModel::dismissDialogs,
        )
    }

    state.confirmDelete?.let { route ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.background,
            title = { Text(myRouteDisplayName(route.name, language), style = MaterialTheme.typography.titleMedium) },
            text = { Text(myRouteDeleteMessage(route, language), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete, shape = RectangleShape) {
                    Text(
                        text = myRouteActionLabel(MyRouteAction.DELETE, language),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialogs, shape = RectangleShape) {
                    Text(myRoutesCancelLabel(language), color = MaterialTheme.colorScheme.onBackground)
                }
            },
        )
    }
    if (state.confirmOpen != null) {
        ReplaceDraftDialog(
            language = language,
            onDiscard = viewModel::confirmOpen,
            onKeepEditing = viewModel::keepEditing,
            onDismiss = viewModel::dismissDialogs,
        )
    }
    if (state.confirmNewRoute) {
        ReplaceDraftDialog(
            language = language,
            onDiscard = viewModel::confirmNewRoute,
            onKeepEditing = viewModel::keepEditing,
            onDismiss = viewModel::dismissDialogs,
        )
    }
}

@Composable
internal fun MyRoutesSection(
    state: MyRoutesUiState,
    language: AppLanguage,
    layout: MyRoutesLayout,
    onNewRoute: () -> Unit,
    onOpenDraft: () -> Unit,
    onOpen: (PersonalRouteSummary) -> Unit,
    onShare: (PersonalRouteSummary) -> Unit,
    onDelete: (PersonalRouteSummary) -> Unit,
    onListingAction: (PersonalRouteSummary, MyRouteListingAction) -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: () -> Unit = {},
) {
    val saved = state.saved
    val nothingYet = myRoutesShowsEmptyState(state)
    val container =
        when (layout) {
            MyRoutesLayout.PLANNER -> {
                Modifier.fillMaxWidth().padding(top = GallrSpacing.md)
            }

            MyRoutesLayout.ARCHIVE -> {
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md)
            }
        }
    Column(modifier = modifier.then(container)) {
        when (layout) {
            MyRoutesLayout.PLANNER -> {
                PlannerHeader(language, showNewRoute = !nothingYet, onNewRoute = onNewRoute)
            }

            MyRoutesLayout.ARCHIVE -> {
                if (!nothingYet) ArchiveNewRouteButton(language, onNewRoute)
            }
        }
        state.error?.let { error ->
            GallrErrorMessage(
                message = myRoutesErrorMessage(error, language),
                actionLabel = if (language == AppLanguage.KO) "닫기" else "DISMISS",
                onAction = onDismissError,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.draftRow?.let { draft -> DraftRow(draft, language, onOpenDraft) }
        when (saved) {
            SavedRoutesState.Loading -> {
                repeat(SKELETON_ROWS) { SkeletonRow() }
            }

            SavedRoutesState.Error -> {
                GallrErrorMessage(
                    message = myRoutesLoadFailedMessage(language),
                    actionLabel = myRoutesRetryLabel(language),
                    onAction = onRetry,
                )
            }

            SavedRoutesState.SignedOut -> {
                if (nothingYet) EmptyRoutes(language, onNewRoute)
                TextButton(onClick = onSignIn, shape = RectangleShape, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(
                        text = myRoutesSignInLabel(language),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            is SavedRoutesState.Loaded -> {
                if (nothingYet) EmptyRoutes(language, onNewRoute)
                // The route sheet keeps to three saved routes; the MY tab lists them all (DD3).
                val shown = if (layout == MyRoutesLayout.PLANNER) myRoutesPlannerRows(saved.routes) else saved.routes
                shown.forEach { route ->
                    SavedRouteRow(
                        route = route,
                        language = language,
                        listingBusy = route.id in state.listingBusy,
                        onOpen = { onOpen(route) },
                        onShare = { onShare(route) },
                        onDelete = { onDelete(route) },
                        onListingAction = { action -> onListingAction(route, action) },
                    )
                }
                if (layout == MyRoutesLayout.PLANNER && myRoutesShowsSeeAll(saved.routes)) {
                    TextButton(onClick = onSeeAll, shape = RectangleShape, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(
                            text = myRoutesSeeAllLabel(language),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
        if (layout == MyRoutesLayout.PLANNER) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = GallrSpacing.md),
            )
        }
    }
}

@Composable
private fun PlannerHeader(
    language: AppLanguage,
    showNewRoute: Boolean,
    onNewRoute: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = myRoutesTitle(language),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showNewRoute) {
            TextButton(onClick = onNewRoute, shape = RectangleShape, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(
                    text = if (language == AppLanguage.KO) "+ 만들기" else "+ NEW",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** Matches "+ 지난 전시 추가" in the 방문 section. */
@Composable
private fun ArchiveNewRouteButton(
    language: AppLanguage,
    onNewRoute: () -> Unit,
) {
    OutlinedButton(
        onClick = onNewRoute,
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Text(text = myRoutesNewRouteLabel(language), style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(GallrSpacing.sm))
}

@Composable
private fun EmptyRoutes(
    language: AppLanguage,
    onNewRoute: () -> Unit,
) {
    // The orange CTA is the GallrEmptyState primary action, the one place it is allowed (DESIGN.md, DR-D21).
    GallrEmptyState(
        message = myRoutesEmptyMessage(language),
        actionLabel = createPersonalRouteLabel(language),
        onAction = onNewRoute,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DraftRow(
    draft: DraftRouteRow,
    language: AppLanguage,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = GallrSpacing.sm),
    ) {
        Text(
            text = myRouteDisplayName(draft.name, language),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = myRoutesDraftLabel(draft.stopCount, language),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SavedRouteRow(
    route: PersonalRouteSummary,
    language: AppLanguage,
    listingBusy: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onListingAction: (MyRouteListingAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val statusLine = myRouteRowLabel(route, language)
    val reasons = myRouteListingReasons(route, language)
    // The clickable row is the one merged Button node that reads the name with lines 2 and 3; a status change is
    // announced politely from that node (D26). The ⋯ button stays its own node.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(vertical = GallrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = myRouteDisplayName(route.name, language),
                style = MaterialTheme.typography.titleSmall,
                maxLines = MAX_ROW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = MAX_ROW_LINES,
            )
            reasons.forEach { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            val label = if (language == AppLanguage.KO) "동선 메뉴" else "Route actions"
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(44.dp).semantics { contentDescription = label },
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
                MyRouteAction.entries
                    .filterNot { it == MyRouteAction.SHARE && route.isRevoked }
                    .forEach { action ->
                        DropdownMenuItem(
                            text = { Text(myRouteActionLabel(action, language)) },
                            onClick = {
                                menuOpen = false
                                when (action) {
                                    MyRouteAction.OPEN -> onOpen()
                                    MyRouteAction.SHARE -> onShare()
                                    MyRouteAction.DELETE -> onDelete()
                                }
                            },
                        )
                    }
                myRouteListingActions(route).forEach { action ->
                    DropdownMenuItem(
                        text = { Text(myRouteListingActionLabel(action, language)) },
                        enabled = !listingBusy,
                        onClick = {
                            menuOpen = false
                            onListingAction(action)
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A loading placeholder row, shared with 추천 동선 (DD7). */
@Composable
internal fun SkeletonRow() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = GallrSpacing.sm)) {
        Box(
            Modifier
                .width(160.dp)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(GallrSpacing.xs))
        Box(
            Modifier
                .width(96.dp)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/**
 * The listing snackbar (DD12, DD14): a confirmation, a failure whose action repeats the same call, or a refusal
 * that explains the server's state and offers nothing to retry.
 */
@Composable
private fun ListingMessageEffect(
    message: ListingMessage?,
    hostState: SnackbarHostState,
    language: AppLanguage,
    onRetry: () -> Unit,
    onShown: () -> Unit,
) {
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        val result =
            when (shown) {
                is ListingMessage.Requested -> {
                    val confirmation = listingRequestedMessage(shown.isEditor, language)
                    hostState.showSnackbar(confirmation, duration = SnackbarDuration.Short)
                }

                ListingMessage.Failed -> {
                    hostState.showSnackbar(
                        message = listingRequestFailedMessage(language),
                        actionLabel = myRoutesRetryLabel(language),
                        duration = SnackbarDuration.Long,
                    )
                }

                is ListingMessage.Refused -> {
                    val explanation = listingRefusedMessage(shown.reason, language)
                    hostState.showSnackbar(explanation, duration = SnackbarDuration.Long)
                }
            }
        if (shown == ListingMessage.Failed && result == SnackbarResult.ActionPerformed) onRetry() else onShown()
    }
}

private const val SKELETON_ROWS = 2

/** Above 1.3× text the name and status wrap to two lines instead of being cut (D27). */
private const val MAX_ROW_LINES = 2
