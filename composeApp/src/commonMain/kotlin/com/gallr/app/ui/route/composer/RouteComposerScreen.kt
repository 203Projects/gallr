package com.gallr.app.ui.route.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallr.app.ShareHandler
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.route.RouteMap
import com.gallr.app.ui.tabs.map.LocationPermissionStatus
import com.gallr.app.ui.tabs.map.rememberLastKnownCoordinates
import com.gallr.app.ui.tabs.map.rememberLocationPermissionState
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ComposerMessage
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.PersonalRouteComposerViewModel
import com.gallr.app.viewmodel.RouteComposerUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.MAX_ROUTE_NAME_LENGTH
import com.gallr.shared.route.PersonalRouteEvaluation
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.RouteSaveProblem
import com.gallr.shared.route.routeNameLength
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource

/**
 * Route-level composer (spec 089 US1): wires the ViewModel, the platform location helpers and the picker. Location
 * is read only when already granted; the system prompt appears only from the "현재 위치에서 출발" button (FR-008).
 */
@Composable
fun PersonalRouteComposerRoute(
    viewModel: PersonalRouteComposerViewModel,
    catalogue: ExhibitionListState,
    onRetryCatalogue: () -> Unit,
    shareHandler: ShareHandler,
    darkCard: Boolean,
    onSignInRequested: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    RouteShareEffect(state.sharePayload, language, shareHandler, darkCard, onConsumed = viewModel::onShareSheetShown)
    LaunchedEffect(state.signInRequested) {
        if (state.signInRequested) {
            viewModel.onSignInRequestHandled()
            onSignInRequested()
        }
    }
    val permission = rememberLocationPermissionState()
    var locationRequestKey by remember { mutableStateOf(0) }
    val coordinates =
        rememberLastKnownCoordinates(enabled = permission.isGranted, requestKey = locationRequestKey)
    val origin = coordinates?.let { runCatching { GeoPoint(it.latitude, it.longitude) }.getOrNull() }
    LaunchedEffect(permission.status, origin) { viewModel.updateLocation(permission.status, origin) }
    var showPicker by remember { mutableStateOf(false) }

    RouteComposerContent(
        state = state,
        language = language,
        onBack = onBack,
        onRename = viewModel::rename,
        onMove = viewModel::move,
        onRemove = viewModel::remove,
        onUndo = viewModel::undo,
        onMessageShown = viewModel::dismissMessage,
        onStartFromLocation = {
            if (permission.status == LocationPermissionStatus.CAN_ASK) permission.request()
            locationRequestKey += 1
        },
        onAddExhibitions = { showPicker = true },
        onSave = viewModel::save,
        onShare = viewModel::share,
        onRemoveBlockedAndSave = viewModel::removeBlockedStopsAndSave,
    )

    if (showPicker) {
        RoutePickerSheet(
            catalogue = catalogue,
            routeStopIds = state.stops.map { it.exhibitionId }.toSet(),
            today = state.today,
            language = language,
            onRetry = onRetryCatalogue,
            onAdd = { picked ->
                viewModel.append(picked)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** The composer's content; stateless apart from the drag in progress and the undo snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteComposerContent(
    state: RouteComposerUiState,
    language: AppLanguage,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onUndo: () -> Unit,
    onMessageShown: () -> Unit,
    onStartFromLocation: () -> Unit,
    onAddExhibitions: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRemoveBlockedAndSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = isReduceMotionOrScreenReaderActive()
    val undoWindowMillis = if (reduceMotion) ASSISTED_UNDO_WINDOW_MILLIS else UNDO_WINDOW_MILLIS
    ComposerSnackbarEffect(state.message, snackbarHostState, language, undoWindowMillis, onUndo, onMessageShown)
    val listState = rememberLazyListState()
    val drag = rememberStopDragState(listState, state.stops)
    var announcement by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { ComposerTopBar(state, language, onBack) },
        bottomBar = { ComposerBottomBar(state, language, onSave, onShare, onRemoveBlockedAndSave) },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data, shape = RectangleShape)
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = GallrSpacing.xl),
        ) {
            item(key = "name") {
                RouteNameField(
                    draftId = state.draftId,
                    name = state.name,
                    problem = state.nameProblem,
                    showEmptyError = state.showNameError,
                    language = language,
                    onRename = onRename,
                )
            }
            if (state.stops.isEmpty()) {
                item(key = "empty") { EmptyComposer(language, onAddExhibitions) }
                return@LazyColumn
            }
            item(key = "map") { ComposerMap(state, language) }
            item(key = "summary") {
                ComposerSummary(
                    state = state,
                    language = language,
                    onStartFromLocation = onStartFromLocation,
                )
            }
            if (state.stops.size == 1) {
                item(key = "one-stop") {
                    Text(
                        text = composerOneStopMessage(language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm),
                    )
                }
            }
            reorderableStops(
                drag = drag,
                evaluation = state.evaluation,
                blockedStopIds = state.blockedStopIds,
                language = language,
                reduceMotion = reduceMotion,
                onMove = { from, to ->
                    onMove(from, to)
                    announcement = composerMovedAnnouncement(to, language)
                },
                onRemove = onRemove,
            )
            item(key = "add") {
                TextButton(
                    onClick = onAddExhibitions,
                    shape = RectangleShape,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = GallrSpacing.sm),
                ) {
                    Text(
                        text = composerAddExhibitionsLabel(language),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Spoken after a reorder so screen-reader users hear where the stop went (DR-D26).
        Text(
            text = announcement,
            modifier = Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.background,
        )
    }
}

/** Shows removal with a 5-second undo, and undo failures (DR-D17, RO4). */
@Composable
private fun ComposerSnackbarEffect(
    message: ComposerMessage?,
    hostState: SnackbarHostState,
    language: AppLanguage,
    undoWindowMillis: Long,
    onUndo: () -> Unit,
    onMessageShown: () -> Unit,
) {
    LaunchedEffect(message) {
        when (message) {
            null -> {
                hostState.currentSnackbarData?.dismiss()
            }

            is ComposerMessage.Removed -> {
                // Five seconds to undo, longer with a screen reader (DR-D17); Material's short duration is four.
                val result =
                    withTimeoutOrNull(undoWindowMillis) {
                        hostState.showSnackbar(
                            message = composerRemovedMessage(language),
                            actionLabel = composerUndoLabel(language),
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                if (result == SnackbarResult.ActionPerformed) onUndo() else onMessageShown()
            }

            is ComposerMessage.UndoFailed -> {
                composerUndoFailure(message.result, language)?.let { hostState.showSnackbar(it) }
                onMessageShown()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerTopBar(
    state: RouteComposerUiState,
    language: AppLanguage,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = composerHeaderTitle(state.hasRemoteRoute, language),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() },
                )
                val labels =
                    listOfNotNull(
                        (if (language == AppLanguage.KO) "공개됨" else "PUBLIC").takeIf { state.isPublished },
                        state.saveStatus?.let { composerSaveStatusLabel(it, language) },
                    )
                if (labels.isNotEmpty()) {
                    Text(
                        text = labels.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = GallrSpacing.sm),
                    )
                }
            }
        },
        navigationIcon = {
            val backLabel = if (language == AppLanguage.KO) "뒤로" else "Back"
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Icon(painter = painterResource(Res.drawable.ic_arrow_back), contentDescription = null)
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
    )
}

@Composable
private fun RouteNameField(
    draftId: String,
    name: String,
    problem: RouteSaveProblem?,
    showEmptyError: Boolean,
    language: AppLanguage,
    onRename: (String) -> Unit,
) {
    // An empty name is reported once a save is tried; a name that is too long is reported while typing.
    val shown = problem.takeIf { it == RouteSaveProblem.NAME_TOO_LONG || showEmptyError }
    val error = composerNameError(shown, language)
    // The field owns what is typed: the stored name lags behind fast typing, so echoing it back would drop
    // characters. The stored name seeds the field only when a draft is loaded or another draft replaces it.
    var text by remember(draftId) { mutableStateOf(name) }
    Column(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onRename(it)
            },
            singleLine = true,
            isError = error != null,
            placeholder = {
                Text(composerNameLabel(language), style = MaterialTheme.typography.bodyMedium)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    errorBorderColor = MaterialTheme.colorScheme.onBackground,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    errorTextColor = MaterialTheme.colorScheme.onBackground,
                    cursorColor = MaterialTheme.colorScheme.onBackground,
                    errorCursorColor = MaterialTheme.colorScheme.onBackground,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = composerNameLabel(language)
                        if (error != null) error(error)
                    },
        )
        // The counter counts what the limit counts: characters of the trimmed name, not UTF-16 units.
        val nameLength = routeNameLength(text)
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = GallrSpacing.xs),
            )
        } else if (nameLength > MAX_ROUTE_NAME_LENGTH - NAME_COUNTER_THRESHOLD) {
            Text(
                text = "$nameLength/$MAX_ROUTE_NAME_LENGTH",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GallrSpacing.xs),
            )
        }
    }
}

@Composable
private fun EmptyComposer(
    language: AppLanguage,
    onAddExhibitions: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
                .fillMaxWidth()
                .height(COMPOSER_MAP_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(GallrSpacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = composerEmptyMessage(language),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onAddExhibitions,
            shape = RectangleShape,
            modifier = Modifier.padding(top = GallrSpacing.sm).heightIn(min = 44.dp),
        ) {
            Text(
                text = composerAddExhibitionsLabel(language),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ComposerMap(
    state: RouteComposerUiState,
    language: AppLanguage,
) {
    RouteEvaluationMap(state.stops, state.evaluation, language)
}

/** The route map panel for these stops, drawn with the evaluation's walking legs when available (DR-D5). */
@Composable
internal fun RouteEvaluationMap(
    stops: List<PersonalRouteStop>,
    evaluation: PersonalRouteEvaluation?,
    language: AppLanguage,
) {
    val points = stops.map { it.point }
    val origin = evaluation?.departureOrigin(points) ?: points.first()
    val legGeometries =
        evaluation?.let { evaluated ->
            val geometries = evaluated.legs.map { it.geometry }
            // Without a device origin the route starts at stop 1, which has no leg of its own.
            if (geometries.size == points.size) geometries else listOf(emptyList<GeoPoint>()) + geometries
        } ?: emptyList()
    RouteMap(
        origin = origin,
        stops = points,
        legGeometries = legGeometries,
        language = language,
        minZoom = COMPOSER_MAP_MIN_ZOOM,
        modifier =
            Modifier
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
                .fillMaxWidth()
                .height(COMPOSER_MAP_HEIGHT)
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
    )
}

/** The device origin when the first leg starts somewhere other than stop 1. */
private fun PersonalRouteEvaluation.departureOrigin(points: List<GeoPoint>): GeoPoint? =
    legs
        .takeIf { it.size == points.size }
        ?.firstOrNull()
        ?.geometry
        ?.firstOrNull()

/** Verdict first, then distance and time, then the reference day; unboxed (DR-D5). */
@Composable
private fun ComposerSummary(
    state: RouteComposerUiState,
    language: AppLanguage,
    onStartFromLocation: () -> Unit,
) {
    RouteEvaluationSummary(state.evaluation, state.today, language)
    if (state.showStartFromLocation) {
        TextButton(
            onClick = onStartFromLocation,
            shape = RectangleShape,
            modifier = Modifier.padding(horizontal = GallrSpacing.sm).heightIn(min = 44.dp),
        ) {
            Text(
                text = composerStartFromLocationLabel(language),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** Verdict first, then distance and time, then the reference day (DR-D5); shared by the composer and the preview. */
@Composable
internal fun RouteEvaluationSummary(
    evaluation: PersonalRouteEvaluation?,
    today: LocalDate,
    language: AppLanguage,
) {
    Column(
        modifier =
            Modifier
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
    ) {
        if (evaluation == null) {
            Text(
                text = if (language == AppLanguage.KO) "시간을 계산하고 있어요" else "CHECKING TIMES",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(composerVerdictLine(evaluation, language), style = MaterialTheme.typography.titleSmall)
            Text(
                text = composerSummaryLine(evaluation, language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            composerReferenceDayLine(evaluation, today, language)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 공유 is the standard black action and 저장 is outlined (DR-D21); both need two stops. */
@Composable
private fun ComposerBottomBar(
    state: RouteComposerUiState,
    language: AppLanguage,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRemoveBlockedAndSave: () -> Unit,
) {
    val shareFocus = remember { FocusRequester() }
    LaunchedEffect(state.shareReady) {
        if (state.shareReady) runCatching { shareFocus.requestFocus() }
    }
    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ComposerActionLine(state, language, onRemoveBlockedAndSave)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = state.canSave,
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                val saveLabel =
                    if (state.isSaving) {
                        composerSavingLabel(language)
                    } else {
                        composerSaveLabel(state.isPublished, language)
                    }
                Text(saveLabel, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onShare,
                enabled = state.canShare,
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .focusRequester(shareFocus),
            ) {
                Text(composerShareLabel(language), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * One line above the buttons, by priority: the last action's error (with "빼고 저장" when stops were refused),
 * "공유 준비됐어요" after sign-in, or the public-state note (DR-D7, DR-D15).
 */
@Composable
private fun ComposerActionLine(
    state: RouteComposerUiState,
    language: AppLanguage,
    onRemoveBlockedAndSave: () -> Unit,
) {
    val error = state.actionError?.let { composerActionErrorMessage(it, language) }
    val modifier =
        Modifier
            .fillMaxWidth()
            .padding(start = GallrSpacing.screenMargin, end = GallrSpacing.screenMargin, top = GallrSpacing.sm)
    when {
        error != null -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                error(error)
                            },
                )
                if (state.blockedStopIds.isNotEmpty()) {
                    TextButton(
                        onClick = onRemoveBlockedAndSave,
                        shape = RectangleShape,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Text(
                            text = composerRemoveAndSaveLabel(language),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        state.shareReady -> {
            Text(
                text = composerShareReadyMessage(language),
                style = MaterialTheme.typography.bodySmall,
                modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        state.listingWarning -> {
            Text(
                text = composerListingWarning(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        state.copiedNote -> {
            Text(
                text = composerCopiedNote(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        else -> {
            composerPublicNote(state.isPublished, state.isSaved, state.authorName, language)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier,
                )
            }
        }
    }
}

internal val COMPOSER_MAP_HEIGHT = 220.dp

/** Authors may pick stops across the metropolitan area, beyond the planner's 5 KM radius. */
internal const val COMPOSER_MAP_MIN_ZOOM = 7.0

private const val UNDO_WINDOW_MILLIS = 5_000L
private const val ASSISTED_UNDO_WINDOW_MILLIS = 10_000L

/** The name length counter appears this many characters before the limit. */
private const val NAME_COUNTER_THRESHOLD = 10
