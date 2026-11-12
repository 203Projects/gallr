package com.gallr.app.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.MyTabRequest
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ui.diary.DiaryEntryFailureNotice
import com.gallr.app.ui.diary.DiaryEntrySheet
import com.gallr.app.ui.diary.DiaryList
import com.gallr.app.ui.diary.DiaryListActions
import com.gallr.app.ui.mygallr.AddGalleriesScreen
import com.gallr.app.ui.mygallr.AddPastVisitsScreen
import com.gallr.app.ui.mygallr.MyGallrScreen
import com.gallr.app.viewmodel.DiaryEntryFailure
import com.gallr.app.viewmodel.DiaryEntryViewModel
import com.gallr.app.viewmodel.DiaryViewModel
import com.gallr.app.viewmodel.MyGallrMode
import com.gallr.app.viewmodel.MyGallrSection
import com.gallr.app.viewmodel.MyGallrViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.shouldShowAccountNudge
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.diary.DiaryFilter
import com.gallr.shared.repository.DiaryDeviceStateRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MyGallrAccountNudgeRepository
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What MY 일기 reads besides the visits (spec 103 US2), handed over from the composition root. */
class DiarySources(
    val publicNoteRepository: PublicNoteRepository,
    val deviceStateRepository: DiaryDeviceStateRepository,
    val authState: Flow<AuthState>,
    val syncStatus: Flow<MyGallrSyncStatus>,
)

@Composable
fun ProfileTab(
    authState: AuthState,
    visitRepository: VisitRepository,
    followedGalleryRepository: FollowedGalleryRepository,
    accountNudgeRepository: MyGallrAccountNudgeRepository,
    diarySources: DiarySources,
    myGallrSyncStatus: MyGallrSyncStatus,
    onRetryMyGallrSync: () -> Unit,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onGalleryTap: (Exhibition) -> Unit = {},
    myTabRequest: MyTabRequest? = null,
    onMyTabRequestHandled: () -> Unit = {},
    onAccount: () -> Unit = {},
    routeCount: Int = 0,
    routes: @Composable (Modifier) -> Unit = {},
    routesEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val myGallrViewModel: MyGallrViewModel =
        viewModel(
            key = "my-gallr",
            factory =
                MyGallrViewModel.factory(
                    visitRepository = visitRepository,
                    followedGalleryRepository = followedGalleryRepository,
                    accountNudgeRepository = accountNudgeRepository,
                    exhibitionsState = tabsViewModel.allExhibitions,
                    language = tabsViewModel.language,
                    galleryProfileImages = tabsViewModel.galleryProfileImages,
                    savedIds = tabsViewModel.bookmarkedIds,
                    publicNoteIds =
                        diarySources.publicNoteRepository
                            .observeOwnNotes()
                            .map { notes -> notes.mapTo(mutableSetOf()) { it.exhibitionId } },
                ),
        )
    val diaryViewModel: DiaryViewModel =
        viewModel(
            key = "my-diary",
            factory =
                DiaryViewModel.factory(
                    visitRepository = visitRepository,
                    publicNoteRepository = diarySources.publicNoteRepository,
                    deviceStateRepository = diarySources.deviceStateRepository,
                    exhibitionsState = tabsViewModel.allExhibitions,
                    savedIds = tabsViewModel.bookmarkedIds,
                    syncStatus = diarySources.syncStatus,
                    authState = diarySources.authState,
                    language = tabsViewModel.language,
                ),
        )
    val myGallrState by myGallrViewModel.uiState.collectAsState()
    val diaryState by diaryViewModel.state.collectAsState()
    // The show whose entry sheet is open over MY 일기; a new request each tap, so a closed sheet can open again. Kept
    // across sign-in from the sheet, which opens over this tab and returns to it.
    var openEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var openRequest by rememberSaveable { mutableIntStateOf(0) }
    val openEntry =
        openEntryId?.let { id ->
            diaryState.diary
                ?.let { diary -> diary.months.flatMap { it.entries } + diary.undated }
                ?.firstOrNull { it.exhibitionId == id }
                ?.let(diaryViewModel::exhibitionFor)
                ?.let { exhibition -> OpenEntry(exhibition, openRequest) }
        }
    // A save or delete from the sheet that failed after it closed shows under its row (as on the exhibition page).
    var entryFailure by remember { mutableStateOf<DiaryEntryFailureNotice?>(null) }

    // Each request is handled once and then cleared, so coming back to the tab later shows it as it was left.
    LaunchedEffect(myTabRequest) {
        when (myTabRequest ?: return@LaunchedEffect) {
            MyTabRequest.ADD_PAST_VISITS -> {
                myGallrViewModel.startAddingVisits()
            }

            MyTabRequest.MY_ROUTES -> {
                myGallrViewModel.selectSection(MyGallrSection.ROUTES)
            }

            MyTabRequest.DIARY_PUBLISHED -> {
                myGallrViewModel.selectSection(MyGallrSection.DIARY)
                diaryViewModel.selectFilter(DiaryFilter.PUBLISHED_NOTES)
            }
        }
        onMyTabRequestHandled()
    }

    when (myGallrState.mode) {
        MyGallrMode.ARCHIVE -> {
            MyGallrScreen(
                state = myGallrState,
                onAddGalleries = myGallrViewModel::startAddingGalleries,
                onUnfollowGallery = myGallrViewModel::unfollowGallery,
                onOpenGallery = { followed ->
                    myGallrViewModel.acknowledgeGallery(followed.record.galleryKey)
                    followed.latestRelevantExhibition?.let(onGalleryTap)
                },
                onSelectSection = myGallrViewModel::selectSection,
                showAccountNudge = myGallrState.shouldShowAccountNudge(authState),
                isAuthenticated = authState is AuthState.Authenticated,
                syncStatus = myGallrSyncStatus,
                onRetrySync = onRetryMyGallrSync,
                onDismissAccountNudge = myGallrViewModel::dismissAccountNudge,
                onAccount = onAccount,
                routeCount = routeCount,
                routes = routes,
                diaryCount = diaryState.entryCount,
                diary = { diaryModifier ->
                    DiaryList(
                        state = diaryState,
                        actions =
                            DiaryListActions(
                                onSelectFilter = diaryViewModel::selectFilter,
                                onOpenEntry = { entry ->
                                    if (diaryViewModel.exhibitionFor(entry) != null) {
                                        if (entryFailure?.exhibitionId == entry.exhibitionId) entryFailure = null
                                        openRequest += 1
                                        openEntryId = entry.exhibitionId
                                    }
                                },
                                onAddPastVisits = myGallrViewModel::startAddingVisits,
                                onAddEndedSaved = myGallrViewModel::startAddingEndedSaved,
                                onDismissEndedSaved = diaryViewModel::dismissEndedSavedStrip,
                                onRetry = diaryViewModel::retry,
                                onApprovalLineShown = diaryViewModel::acknowledgeApprovals,
                            ),
                        modifier = diaryModifier,
                        entryFailure = entryFailure,
                    )
                },
                routesEnabled = routesEnabled,
                modifier = modifier,
            )
            openEntry?.let { entry ->
                DiaryEntrySheetHost(
                    entry = entry,
                    visitRepository = visitRepository,
                    diarySources = diarySources,
                    language = lang,
                    onSignIn = onAccount,
                    onClosed = { failure ->
                        if (openEntryId == entry.exhibition.id) openEntryId = null
                        entryFailure = failure?.let { DiaryEntryFailureNotice(entry.exhibition.id, it) }
                    },
                )
            }
        }

        MyGallrMode.ADD_VISITS, MyGallrMode.ADD_ENDED_SAVED -> {
            PlatformBackHandler(onBack = myGallrViewModel::cancelAddingVisits)
            AddPastVisitsScreen(
                state = myGallrState,
                onBack = myGallrViewModel::cancelAddingVisits,
                onSearchQueryChange = myGallrViewModel::setSearchQuery,
                onToggleSelection = myGallrViewModel::toggleSelection,
                onSave = myGallrViewModel::saveSelected,
                modifier = modifier,
            )
        }

        MyGallrMode.ADD_GALLERIES -> {
            PlatformBackHandler(onBack = myGallrViewModel::cancelAddingGalleries)
            AddGalleriesScreen(
                state = myGallrState,
                onBack = myGallrViewModel::cancelAddingGalleries,
                onSearchQueryChange = myGallrViewModel::setGallerySearchQuery,
                onToggleSelection = myGallrViewModel::toggleGallerySelection,
                onSave = myGallrViewModel::saveSelectedGalleries,
                modifier = modifier,
            )
        }
    }
}

/** One opening of the entry sheet from a diary row. */
private data class OpenEntry(
    val exhibition: Exhibition,
    val request: Int,
)

/**
 * The entry sheet over MY 일기, the same sheet the exhibition page opens (spec 103 US2: a row opens its entry). Its
 * ViewModel is keyed apart from the page's, so the page keeps its own analytics callback for the first visit. The host
 * stays until the save or delete it started has finished, so a failure reaches [onClosed] and shows under the row.
 */
@Composable
private fun DiaryEntrySheetHost(
    entry: OpenEntry,
    visitRepository: VisitRepository,
    diarySources: DiarySources,
    language: AppLanguage,
    onSignIn: () -> Unit,
    onClosed: (DiaryEntryFailure?) -> Unit,
) {
    val exhibition = entry.exhibition
    val entryViewModel: DiaryEntryViewModel =
        viewModel(
            key = "my-diary-entry-${exhibition.id}",
            factory =
                DiaryEntryViewModel.factory(
                    exhibition = exhibition,
                    visitRepository = visitRepository,
                    publicNoteRepository = diarySources.publicNoteRepository,
                    authState = diarySources.authState,
                    syncStatus = diarySources.syncStatus,
                    onVisitRecorded = {},
                ),
        )
    val entryState by entryViewModel.state.collectAsState()
    var shown by remember(entry) { mutableStateOf(false) }
    LaunchedEffect(entry) { entryViewModel.startEditing() }
    val editor = entryState.editor
    val finished = editor == null && !entryState.isWorking
    LaunchedEffect(editor == null, finished) {
        when {
            editor != null -> shown = true
            shown && finished -> onClosed(entryState.failure)
        }
    }
    if (editor != null) {
        DiaryEntrySheet(
            title = exhibition.localizedName(language),
            venue = exhibition.localizedVenueName(language),
            editor = editor,
            language = language,
            actions = entryViewModel,
            onSignIn = onSignIn,
        )
    }
}
