package com.gallr.app.ui.route.publicroutes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.route.composer.ReadOnlyStopRow
import com.gallr.app.ui.route.composer.ReplaceDraftDialog
import com.gallr.app.ui.route.composer.RouteEvaluationMap
import com.gallr.app.ui.route.composer.RouteEvaluationSummary
import com.gallr.app.ui.route.composer.SkeletonRow
import com.gallr.app.ui.route.composer.myRoutesRetryLabel
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.PublicRouteMessage
import com.gallr.app.viewmodel.PublicRouteNotice
import com.gallr.app.viewmodel.PublicRoutePreview
import com.gallr.app.viewmodel.PublicRoutesViewModel
import com.gallr.shared.data.model.AppLanguage
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import gallr.composeapp.generated.resources.ic_more_horiz
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource

/**
 * A listed route opened read-only from 추천 동선 (spec 089 US9, DD1): the composer's map, verdict and stop rows
 * without editing, and a bottom bar that copies the route or, for the author, opens it in 내 동선 (DD19, DD22).
 * The ⋯ menu reports the route (US10, DD10); the author's own route has neither copy nor report.
 */
@Composable
fun PublicRoutePreviewRoute(
    viewModel: PublicRoutesViewModel,
    language: AppLanguage,
    onBack: () -> Unit,
    onOpenOwn: (routeId: String) -> Unit,
    onCopied: (draftId: String) -> Unit,
    onSignIn: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview = state.preview
    LaunchedEffect(preview?.copiedDraftId) {
        val draftId = preview?.copiedDraftId ?: return@LaunchedEffect
        viewModel.onCopyOpened()
        onCopied(draftId)
    }
    LaunchedEffect(preview?.signInRequested) {
        if (preview?.signInRequested == true) {
            viewModel.onSignInRequestHandled()
            onSignIn()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    PublicRouteMessageEffect(
        message = preview?.message,
        hostState = snackbarHostState,
        language = language,
        onRetry = viewModel::retryMessage,
        onShown = viewModel::dismissMessage,
    )
    PublicRoutePreviewScreen(
        preview = preview,
        today = state.today,
        language = language,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRetry = { preview?.summary?.let(viewModel::openPreview) },
        onCopy = viewModel::copy,
        onOpenOwn = onOpenOwn,
        menuItems = { closeMenu ->
            val reported = preview?.reported == true
            DropdownMenuItem(
                text = { Text(reportMenuLabel(reported, language)) },
                enabled = !reported,
                onClick = {
                    closeMenu()
                    viewModel.startReport()
                },
            )
        },
    )
    if (preview?.reportSheet == true) {
        RouteReportSheet(
            language = language,
            busy = preview.reportBusy,
            onReport = viewModel::report,
            onDismiss = viewModel::closeReport,
        )
    }
    if (preview?.confirmReplace == true) {
        ReplaceDraftDialog(
            language = language,
            onDiscard = viewModel::confirmReplace,
            onKeepEditing = {
                viewModel.cancelReplace()
                onKeepEditing()
            },
            onDismiss = viewModel::cancelReplace,
        )
    }
}

/** Copy and report outcomes; a failure's 다시 시도 repeats the same call (DD12). */
@Composable
private fun PublicRouteMessageEffect(
    message: PublicRouteMessage?,
    hostState: SnackbarHostState,
    language: AppLanguage,
    onRetry: () -> Unit,
    onShown: () -> Unit,
) {
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        val retry = publicRouteMessageRetry(shown, language)
        val result =
            hostState.showSnackbar(
                message = publicRouteMessageText(shown, language),
                actionLabel = retry,
                duration = if (retry == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) onRetry() else onShown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PublicRoutePreviewScreen(
    preview: PublicRoutePreview?,
    today: LocalDate?,
    language: AppLanguage,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpenOwn: (routeId: String) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    menuItems: @Composable (closeMenu: () -> Unit) -> Unit = {},
) {
    val canAct = preview?.route != null && preview.notice == null
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data, shape = RectangleShape) }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PreviewTopBar(
                language = language,
                showMenu = canAct && preview?.isOwn == false,
                onBack = onBack,
                menuItems = menuItems,
            )
        },
        bottomBar = {
            if (canAct && preview != null) {
                PreviewActionBar(
                    isOwn = preview.isOwn,
                    copyBusy = preview.copyBusy,
                    language = language,
                    onCopy = onCopy,
                    onOpenOwn = { onOpenOwn(preview.summary.id) },
                )
            }
        },
    ) { padding ->
        if (preview == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = GallrSpacing.md),
        ) {
            item { PreviewHeading(preview, today, language) }
            when {
                preview.notice == PublicRouteNotice.NO_LONGER_LISTED -> {
                    item { PreviewNotice(publicRouteNoLongerListedMessage(language)) }
                }

                preview.notice == PublicRouteNotice.LOAD_FAILED -> {
                    item {
                        GallrErrorMessage(
                            message = publicRouteLoadFailedMessage(language),
                            actionLabel = myRoutesRetryLabel(language),
                            onAction = onRetry,
                        )
                    }
                }

                preview.loading || preview.route == null -> {
                    items(SKELETON_ROWS) {
                        Box(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) { SkeletonRow() }
                    }
                }

                else -> {
                    val stops = preview.route.stops
                    val evaluation = preview.evaluation
                    val evaluatedById = evaluation?.stops?.associateBy { it.stop.exhibitionId }.orEmpty()
                    val legsById = evaluation?.legs?.associateBy { it.toExhibitionId }.orEmpty()
                    item { RouteEvaluationMap(stops, evaluation, language) }
                    if (evaluation != null) {
                        item { RouteEvaluationSummary(evaluation, today ?: evaluation.plannedDay, language) }
                    }
                    itemsIndexed(stops, key = { _, stop -> stop.exhibitionId }) { index, stop ->
                        Box(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) {
                            ReadOnlyStopRow(
                                index = index,
                                stop = stop,
                                evaluated = evaluatedById[stop.exhibitionId],
                                leg = legsById[stop.exhibitionId],
                                language = language,
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewTopBar(
    language: AppLanguage,
    showMenu: Boolean,
    onBack: () -> Unit,
    menuItems: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            val backLabel = if (language == AppLanguage.KO) "뒤로" else "Back"
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Icon(painter = painterResource(Res.drawable.ic_arrow_back), contentDescription = null)
            }
        },
        actions = {
            if (showMenu) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    val label = publicRouteMenuLabel(language)
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics { contentDescription = label },
                    ) {
                        Icon(painter = painterResource(Res.drawable.ic_more_horiz), contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RectangleShape,
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        menuItems { menuOpen = false }
                    }
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
    )
}

/** Name, byline and the shared date line when the route is judged for a later day (DD1, DD21). */
@Composable
private fun PreviewHeading(
    preview: PublicRoutePreview,
    today: LocalDate?,
    language: AppLanguage,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
                .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = preview.summary.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = MAX_NAME_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = publicRoutePreviewByline(preview.summary, language),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GallrSpacing.xs),
        )
        val dateLine =
            preview.laterDay?.let { later -> today?.let { publicRoutePreviewDateLine(later, it, language) } }
        if (dateLine != null) {
            Text(
                text = dateLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Announced politely when the route left the list while the reader was looking (D26). */
@Composable
private fun PreviewNotice(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The composer's filled black button, full width (DD19); "내 동선에서 열기" on the reader's own route (DD22). */
@Composable
private fun PreviewActionBar(
    isOwn: Boolean,
    copyBusy: Boolean,
    language: AppLanguage,
    onCopy: () -> Unit,
    onOpenOwn: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val label = if (isOwn) openInMyRoutesLabel(language) else copyToMyRouteLabel(copyBusy, language)
        Button(
            onClick = { if (!copyBusy) (if (isOwn) onOpenOwn else onCopy)() },
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = GallrSpacing.sm)
                    .heightIn(min = ACTION_MIN_HEIGHT)
                    .semantics { if (copyBusy) stateDescription = label },
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val SKELETON_ROWS = 3
private const val MAX_NAME_LINES = 2
private val ACTION_MIN_HEIGHT = 52.dp
