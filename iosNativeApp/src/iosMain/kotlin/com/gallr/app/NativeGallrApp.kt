package com.gallr.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gallr.app.analytics.DiscoveryAttribution
import com.gallr.app.analytics.MobileAnalyticsTracker
import com.gallr.app.analytics.toAnalyticsSummary
import com.gallr.app.platform.appStoreListingUrl
import com.gallr.app.ui.discovery.recommendationReasonLabel
import com.gallr.app.ui.update.showUpdateNudge
import com.gallr.app.viewmodel.AccountSyncViewModel
import com.gallr.app.viewmodel.AddToRouteUiState
import com.gallr.app.viewmodel.AddToRouteViewModel
import com.gallr.app.viewmodel.AuthSessionViewModel
import com.gallr.app.viewmodel.CuratorNoteUiState
import com.gallr.app.viewmodel.CuratorNoteViewModel
import com.gallr.app.viewmodel.DiaryEntryUiState
import com.gallr.app.viewmodel.DiaryEntryViewModel
import com.gallr.app.viewmodel.DiaryUiState
import com.gallr.app.viewmodel.DiaryViewModel
import com.gallr.app.viewmodel.EditProfileUiState
import com.gallr.app.viewmodel.EditProfileViewModel
import com.gallr.app.viewmodel.EditorDetailUiState
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.app.viewmodel.EditorSelectorState
import com.gallr.app.viewmodel.EditorSelectorViewModel
import com.gallr.app.viewmodel.EventDetailUiState
import com.gallr.app.viewmodel.EventDetailViewModel
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.ExhibitionThoughtsUiState
import com.gallr.app.viewmodel.ExhibitionThoughtsViewModel
import com.gallr.app.viewmodel.GalleryCandidate
import com.gallr.app.viewmodel.GalleryDetailUiState
import com.gallr.app.viewmodel.GalleryDetailViewModel
import com.gallr.app.viewmodel.GallerySearchResult
import com.gallr.app.viewmodel.HiddenAuthorsUiState
import com.gallr.app.viewmodel.HiddenAuthorsViewModel
import com.gallr.app.viewmodel.HomeUiState
import com.gallr.app.viewmodel.HomeViewModel
import com.gallr.app.viewmodel.ListScreenUiState
import com.gallr.app.viewmodel.LocalDiscoveryViewModel
import com.gallr.app.viewmodel.MapScreenUiState
import com.gallr.app.viewmodel.MyGallrUiState
import com.gallr.app.viewmodel.MyGallrViewModel
import com.gallr.app.viewmodel.MyRoutesUiState
import com.gallr.app.viewmodel.MyRoutesViewModel
import com.gallr.app.viewmodel.PersonalMapUiState
import com.gallr.app.viewmodel.PersonalMapViewModel
import com.gallr.app.viewmodel.PersonalRouteComposerViewModel
import com.gallr.app.viewmodel.ProfileUiState
import com.gallr.app.viewmodel.ProfileViewModel
import com.gallr.app.viewmodel.PublicRouteBrowseUiState
import com.gallr.app.viewmodel.PublicRouteBrowseViewModel
import com.gallr.app.viewmodel.PublicRoutesUiState
import com.gallr.app.viewmodel.PublicRoutesViewModel
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.app.viewmodel.RouteComposerUiState
import com.gallr.app.viewmodel.RouteUiState
import com.gallr.app.viewmodel.SignInUiState
import com.gallr.app.viewmodel.SignInViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.TastePickBlockers
import com.gallr.app.viewmodel.TastePickSheetState
import com.gallr.app.viewmodel.TastePickViewModel
import com.gallr.app.viewmodel.gallerySearchResults
import com.gallr.app.viewmodel.shouldOfferTastePick
import com.gallr.shared.analytics.AnalyticsEntryPoint
import com.gallr.shared.analytics.AnalyticsIntentAction
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.DiscoveryKind
import com.gallr.shared.analytics.PositionBucket
import com.gallr.shared.app.UpdateRequirement
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.Profile
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.network.ExhibitionCatalogSource
import com.gallr.shared.data.network.handleAuthDeeplink
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.ReminderBookmarks
import com.gallr.shared.observability.AppLog
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Retained Kotlin application state for the SwiftUI host. Call from the main thread. */
class NativeGallrApp internal constructor(
    private val dependencies: NativeGallrDependencies,
    val routesEnabled: Boolean = ReleaseFeatures.ROUTES,
) {
    constructor(
        supabaseUrl: String,
        supabaseApiKey: String,
        exhibitionCatalogSource: String,
        promotionEnabled: Boolean,
        mobileAnalyticsReleaseEnabled: Boolean,
    ) : this(
        createNativeGallrDependencies(
            supabaseUrl = supabaseUrl,
            supabaseApiKey = supabaseApiKey,
            exhibitionCatalogSource = ExhibitionCatalogSource.fromConfig(exhibitionCatalogSource),
            promotionEnabled = promotionEnabled,
            mobileAnalyticsReleaseEnabled = mobileAnalyticsReleaseEnabled,
        ),
    )

    constructor(fixtureCatalogue: List<Exhibition>) : this(
        createNativeGallrDependencies(
            supabaseUrl = "https://fixture.invalid",
            supabaseApiKey = "fixture",
            exhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
            promotionEnabled = false,
            mobileAnalyticsReleaseEnabled = false,
            fixtureCatalogue = requireNativeDebugFixture(fixtureCatalogue),
        ),
        routesEnabled = true,
    )

    private val scope = MainScope()
    private val viewModels = ViewModelStore()
    private val publicRouteSelections = mutableMapOf<String, PublicRouteSummary>()
    private val screenViewModels = mutableMapOf<ViewModel, ViewModelStore>()
    private var closed = false

    private val authSessionViewModel: AuthSessionViewModel =
        retained(key = "auth-session", factory = AuthSessionViewModel.factory(dependencies.authRepository))
    private val authStateFlow = authSessionViewModel.authState

    // The shared account-scoped repositories outlive individual SwiftUI views.
    private val accountSync: AccountSyncViewModel =
        retained(
            key = "account-sync",
            factory =
                AccountSyncViewModel.factory(
                    localBookmarkRepository = dependencies.localBookmarkRepository,
                    cloudBookmarkRepository = dependencies.cloudBookmarkRepository,
                    authState = authStateFlow,
                    visitRepository = dependencies.visitRepository,
                    followedGalleryRepository = dependencies.followedGalleryRepository,
                    accountStore = dependencies.myGallrAccountStore,
                    accountSource = dependencies.myGallrAccountSource,
                ),
        )
    private val cloudBookmarks = accountSync.cloudBookmarks
    private val syncBookmarkRepository = accountSync.bookmarks
    private val myGallrSyncCoordinator = accountSync.coordinator
    private val syncedVisitRepository = accountSync.visits
    private val syncedFollowedGalleryRepository = accountSync.followedGalleries
    val tabs: TabsViewModel =
        retained(
            factory =
                TabsViewModel.factory(
                    exhibitionRepository = dependencies.exhibitionRepository,
                    bookmarkRepository = syncBookmarkRepository,
                    languageRepository = dependencies.languageRepository,
                    themeRepository = dependencies.themeRepository,
                    eventRepository = dependencies.eventRepository,
                    authState = authStateFlow,
                    profileNudgeRepository = dependencies.localBookmarkRepository,
                    promotionRepository = dependencies.promotionRepository,
                    galleryProfileImageRepository = dependencies.galleryProfileImageRepository,
                ),
        )

    val personalMap: PersonalMapViewModel by lazy {
        retained(
            key = "personal-map",
            factory =
                PersonalMapViewModel.factory(
                    exhibitionsState = tabs.allExhibitions,
                    bookmarkedIds = tabs.bookmarkedIds,
                    language = tabs.language,
                ),
        )
    }

    val localDiscovery: LocalDiscoveryViewModel =
        retained(
            key = "local-discovery",
            factory =
                LocalDiscoveryViewModel.factory(
                    exhibitionsState = tabs.allExhibitions,
                    // The store's own reads: the shared state starts empty, which would read as a visitor with no saves.
                    bookmarkedIds = syncBookmarkRepository.observeBookmarkedIds(),
                    visitRepository = syncedVisitRepository,
                    followedGalleryRepository = syncedFollowedGalleryRepository,
                    language = tabs.language,
                    walkingRoutes = dependencies.walkingRouteRepository,
                    tastePreferences = dependencies.tastePreferenceRepository,
                    accountHistory = accountSync.accountHistory,
                ),
        )

    val tastePick: TastePickViewModel =
        retained(
            factory =
                TastePickViewModel.factory(
                    exhibitionsState = tabs.allExhibitions,
                    featuredState = tabs.featuredState,
                    tastePreferences = dependencies.tastePreferenceRepository,
                ),
        )

    val home: HomeViewModel =
        retained(
            key = "home",
            factory =
                HomeViewModel.factory(
                    exhibitionsState = tabs.allExhibitions,
                    featuredState = tabs.featuredState,
                    followedGalleryRepository = syncedFollowedGalleryRepository,
                    authState = authStateFlow,
                    curatorNoteRepository = dependencies.curatorNoteRepository,
                    routeRepository = dependencies.personalRouteRepository,
                    mostViewedRepository = dependencies.mostViewedExhibitionRepository,
                    routesEnabled = routesEnabled,
                ),
        )

    private val mobileAnalyticsTracker =
        MobileAnalyticsTracker(dependencies.mobileAnalyticsController, dependencies.mobileAnalyticsEventFactory)
    val routeComposer: PersonalRouteComposerViewModel by lazy {
        retained(
            key = "route-composer",
            factory =
                PersonalRouteComposerViewModel.factory(
                    draftRepository = dependencies.personalRouteDraftRepository,
                    routeRepository = dependencies.personalRouteRepository,
                    exhibitionsState = tabs.allExhibitions,
                    language = tabs.language,
                    authState = authStateFlow,
                    analytics = mobileAnalyticsTracker,
                    walkingRoutes = dependencies.walkingRouteRepository,
                    visitTimeRepository = dependencies.routeVisitTimeRepository,
                ),
        )
    }

    val myRoutes: MyRoutesViewModel by lazy {
        retained(
            key = "my-routes",
            factory =
                MyRoutesViewModel.factory(
                    draftRepository = dependencies.personalRouteDraftRepository,
                    routeRepository = dependencies.personalRouteRepository,
                    authState = authStateFlow,
                    analytics = mobileAnalyticsTracker,
                    routesEnabled = routesEnabled,
                ),
        )
    }

    val publicRoutes: PublicRoutesViewModel by lazy {
        retained(
            key = "public-routes",
            factory =
                PublicRoutesViewModel.factory(
                    routeRepository = dependencies.personalRouteRepository,
                    draftRepository = dependencies.personalRouteDraftRepository,
                    exhibitionsState = tabs.allExhibitions,
                    authState = authStateFlow,
                    analytics = mobileAnalyticsTracker,
                    walkingRoutes = dependencies.walkingRouteRepository,
                    routesEnabled = routesEnabled,
                ),
        )
    }

    val publicRouteBrowse: PublicRouteBrowseViewModel by lazy {
        retained(
            key = "public-route-browse",
            factory = PublicRouteBrowseViewModel.factory(routeRepository = dependencies.personalRouteRepository),
        )
    }

    val signIn: SignInViewModel by lazy {
        retained(factory = SignInViewModel.factory(dependencies.authRepository))
    }
    val editorSelector: EditorSelectorViewModel by lazy {
        retained(factory = EditorSelectorViewModel.factory(dependencies.editorRepository, tabs))
    }
    val hiddenAuthors: HiddenAuthorsViewModel by lazy {
        retained(factory = HiddenAuthorsViewModel.factory(dependencies.hiddenThoughtAuthorRepository))
    }
    val myGallr: MyGallrViewModel by lazy {
        retained(
            factory =
                MyGallrViewModel.factory(
                    visitRepository = syncedVisitRepository,
                    followedGalleryRepository = syncedFollowedGalleryRepository,
                    accountNudgeRepository = dependencies.accountNudgeRepository,
                    exhibitionsState = tabs.allExhibitions,
                    language = tabs.language,
                    galleryProfileImages = tabs.galleryProfileImages,
                    savedIds = tabs.bookmarkedIds,
                    publicNoteIds =
                        accountSync.publicNotes
                            .observeOwnNotes()
                            .map { notes -> notes.mapTo(mutableSetOf()) { it.exhibitionId } },
                ),
        )
    }
    val diary: DiaryViewModel by lazy {
        retained(
            factory =
                DiaryViewModel.factory(
                    visitRepository = syncedVisitRepository,
                    publicNoteRepository = accountSync.publicNotes,
                    deviceStateRepository = dependencies.diaryDeviceStateRepository,
                    exhibitionsState = tabs.allExhibitions,
                    savedIds = tabs.bookmarkedIds,
                    syncStatus = myGallrSyncCoordinator.observeStatus(),
                    authState = authStateFlow,
                    language = tabs.language,
                    refreshAccount = myGallrSyncCoordinator::refresh,
                ),
        )
    }

    private val updateRefresh = AppUpdateRefresh(dependencies.appUpdateRepository)
    private val navigation = MutableStateFlow<NativeNavigationRequest?>(null)
    private val notificationPrompt = MutableStateFlow(false)
    private var lastRecordedRouteBuild: Long? = null

    init {
        scope.launch { updateRefresh.refreshNow() }
        scope.launch {
            runCatching { dependencies.mobileAnalyticsController.initialize() }
                .onFailure { log.warn("mobile_analytics_initialize", it) }
        }
        scope.launch {
            while (isActive) {
                delay(CATALOG_REFRESH_CHECK_INTERVAL_MILLIS)
                tabs.refreshIfStale()
            }
        }
        scope.launch { synchronizeAccounts() }
        scope.launch { synchronizeGalleryAlerts() }
        scope.launch {
            dependencies.notificationSyncService.follow(
                ReminderBookmarks.forAccount(syncBookmarkRepository, authStateFlow, cloudBookmarks.readBookmarkedIds),
            )
        }
        syncBookmarkRepository.setMutationListener {
            if (!dependencies.notificationPreferences.observePermissionPrompted().first() &&
                !dependencies.notificationScheduler.hasPermission()
            ) {
                notificationPrompt.value = true
            }
            dependencies.notificationSyncService.sync(triggeredByMutation = true)
        }
        scope.launch {
            tabs.featuredState.first { it is ExhibitionListState.Success }
            dependencies.notificationSyncService.sync(triggeredByMutation = false)
        }
        scope.launch {
            dependencies.notificationScheduler.pendingDeepLink.filterNotNull().collect { request ->
                val exhibition =
                    when (request) {
                        is DeepLink.Exhibition -> tabs.exhibitionForDeepLink(request.id)
                        DeepLink.MyList -> null
                    }
                navigation.value = NativeNavigationRequest(exhibition)
                dependencies.notificationScheduler.consumePendingDeepLink()
            }
        }
    }

    private suspend fun synchronizeAccounts() {
        authStateFlow.collectLatest { state ->
            when (state) {
                is AuthState.Authenticated -> {
                    val canSync = !state.isSessionRefreshPending
                    if (canSync) cloudBookmarks.markStale()
                    try {
                        myGallrSyncCoordinator.activateAccount(state.user.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log.warn("my_gallr_account_sync", error)
                    }
                    if (canSync) {
                        try {
                            syncBookmarkRepository.migrateLocalToCloud()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            log.warn("bookmark_cloud_sync", error)
                        }
                    }
                }

                AuthState.Anonymous -> {
                    myGallrSyncCoordinator.deactivateAccount()
                    cloudBookmarks.forgetAccount()
                }

                AuthState.Loading -> {}
            }
        }
    }

    private suspend fun synchronizeGalleryAlerts() {
        combine(tabs.language, authStateFlow) { language, _ -> language }.collectLatest { language ->
            if (!dependencies.notificationScheduler.hasPermission()) return@collectLatest
            val galleries =
                syncedFollowedGalleryRepository
                    .observeFollowedGalleries()
                    .first()
                    .filter { it.newExhibitionAlertsEnabled && it.galleryId != null }
            if (galleries.isEmpty()) return@collectLatest
            val address = dependencies.remotePushAddressProvider.currentAddress() ?: return@collectLatest
            val locale = if (language == AppLanguage.KO) "ko-KR" else "en-US"
            galleries.forEach { gallery ->
                dependencies.galleryAlertRegistrationRepository
                    .enableGallery(checkNotNull(gallery.galleryId), address, locale)
                    .onFailure { log.warn("gallery_alert_registration_sync", it) }
            }
        }
    }

    fun foreground() {
        tabs.refreshIfStale()
        scope.launch { updateRefresh.resumed() }
        scope.launch { myGallrSyncCoordinator.refresh() }
        scope.launch {
            runCatching { dependencies.mobileAnalyticsController.onResume() }
                .onFailure { log.warn("mobile_analytics_resume", it) }
        }
    }

    fun background() {
        updateRefresh.paused()
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        screenViewModels.values.forEach(ViewModelStore::clear)
        screenViewModels.clear()
        viewModels.clear()
        releaseNativeGallrDependencies(dependencies)
    }

    fun openUrl(url: String) {
        scope.launch { handleAuthDeeplink(dependencies.networkClients.supabaseClient, url) }
    }

    fun claimNavigation(request: NativeNavigationRequest): Boolean {
        if (!navigation.compareAndSet(request, null)) return false
        request.exhibition?.let { exhibition ->
            recordExhibitionOpened(exhibition.id, AnalyticsSurface.MY_GALLR, DiscoveryKind.NOTIFICATION)
        }
        return true
    }

    fun respondToNotificationPrompt(allow: Boolean) {
        notificationPrompt.value = false
        scope.launch {
            dependencies.notificationPreferences.setPermissionPrompted()
            if (allow && dependencies.notificationScheduler.requestPermission()) {
                dependencies.notificationSyncService.sync(triggeredByMutation = true)
            }
        }
    }

    fun updateStoreUrl(): String = appStoreListingUrl()

    fun dismissUpdateNudge() {
        scope.launch { dependencies.appUpdateRepository.dismissNudge(Clock.System.todayIn(TimeZone.of("Asia/Seoul"))) }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        scope.launch { dependencies.mobileAnalyticsController.setUserEnabled(enabled) }
    }

    fun recordSurface(
        surface: AnalyticsSurface,
        entryPoint: AnalyticsEntryPoint,
    ) {
        scope.launch { mobileAnalyticsTracker.surfaceViewed(surface, entryPoint) }
    }

    fun recordExhibitionOpened(
        id: String,
        surface: AnalyticsSurface,
        kind: DiscoveryKind,
    ) {
        recordExhibitionOpened(id, surface, kind, PositionBucket.UNRANKED)
    }

    fun recordExhibitionOpened(
        id: String,
        surface: AnalyticsSurface,
        kind: DiscoveryKind,
        position: PositionBucket,
    ) {
        scope.launch { mobileAnalyticsTracker.exhibitionOpened(id, DiscoveryAttribution(surface, kind, position)) }
    }

    fun recordExhibitionImpression(
        id: String,
        surface: AnalyticsSurface,
        kind: DiscoveryKind,
        position: PositionBucket,
    ) {
        scope.launch { mobileAnalyticsTracker.exhibitionImpression(id, DiscoveryAttribution(surface, kind, position)) }
    }

    fun recordRecommendationFeedShown(count: Int) {
        scope.launch { mobileAnalyticsTracker.recommendationsShown(AnalyticsSurface.FEATURED, count) }
    }

    fun recordExhibitionIntent(
        id: String,
        surface: AnalyticsSurface,
        action: AnalyticsIntentAction,
    ) {
        scope.launch { mobileAnalyticsTracker.exhibitionIntent(id, surface, action) }
    }

    fun toggleBookmark(
        id: String,
        surface: AnalyticsSurface,
    ) {
        tabs.toggleBookmark(id) { saved ->
            val action = if (saved) AnalyticsIntentAction.BOOKMARK_ADD else AnalyticsIntentAction.BOOKMARK_REMOVE
            recordExhibitionIntent(id, surface, action)
        }
    }

    fun logout() {
        scope.launch {
            runCatching { dependencies.authRepository.signOut() }.onFailure { log.warn("sign_out", it) }
        }
    }

    fun deleteAccount(onResult: (Boolean) -> Unit): NativeObservation =
        NativeObservation(
            scope.launch {
                val result = runCatching { dependencies.authRepository.deleteAccount() }
                result.onFailure { log.warn("delete_account", it) }
                onResult(result.isSuccess)
            },
        )

    fun findExhibition(id: String): Exhibition? = tabs.findExhibitionById(id)

    fun resolveExhibition(
        id: String,
        onResult: (Exhibition?) -> Unit,
    ): NativeObservation = NativeObservation(scope.launch { onResult(tabs.exhibitionForDeepLink(id)) })

    fun setListFilters(
        showFeatured: Boolean,
        openingThisWeek: Boolean,
        closingThisWeek: Boolean,
    ) {
        tabs.updateFilter {
            copy(showFeatured = showFeatured, openingThisWeek = openingThisWeek, closingThisWeek = closingThisWeek)
        }
    }

    fun searchGalleries(query: String): List<GallerySearchResult> {
        val exhibitions = (tabs.allExhibitions.value as? ExhibitionListState.Success)?.exhibitions.orEmpty()
        val visitedIds =
            myGallr.uiState.value.visits
                .mapTo(mutableSetOf()) { it.exhibitionId }
        return exhibitions.gallerySearchResults(query, tabs.language.value, visitedIds)
    }

    fun recommendationReason(
        recommendation: ExhibitionRecommendation,
        language: AppLanguage,
    ): String = recommendationReasonLabel(recommendation.evidence, language)

    fun observeFollowedGalleries(onChange: (List<FollowedGallery>) -> Unit): NativeObservation =
        syncedFollowedGalleryRepository.observeFollowedGalleries().observeForNative(scope, onChange)

    fun followGallery(candidate: GalleryCandidate) {
        scope.launch {
            runCatching {
                syncedFollowedGalleryRepository.followGallery(
                    FollowedGallery(
                        galleryKey = candidate.galleryKey,
                        galleryId = candidate.galleryId,
                        snapshot = candidate.snapshot,
                        knownExhibitionIds = candidate.exhibitions.mapTo(mutableSetOf()) { it.id },
                        followedAt = Clock.System.now(),
                    ),
                )
            }.onSuccess {
                candidate.exhibitions.firstOrNull()?.let { exhibition ->
                    recordExhibitionIntent(exhibition.id, AnalyticsSurface.LIST, AnalyticsIntentAction.FOLLOW_GALLERY)
                }
            }.onFailure { log.warn("follow_gallery", it) }
        }
    }

    fun galleryDetail(exhibition: Exhibition): GalleryDetailViewModel =
        screenModel(
            factory =
                GalleryDetailViewModel.factory(
                    representative = exhibition,
                    exhibitionsState = tabs.allExhibitions,
                    followedGalleryRepository = syncedFollowedGalleryRepository,
                    notificationScheduler = dependencies.notificationScheduler,
                    galleryAlertRegistrationRepository = dependencies.galleryAlertRegistrationRepository,
                    remotePushAddressProvider = dependencies.remotePushAddressProvider,
                    visitRepository = syncedVisitRepository,
                    locale = if (tabs.language.value == AppLanguage.KO) "ko-KR" else "en-US",
                    galleryProfileImages = tabs.galleryProfileImages,
                ),
        )

    fun selectPublicRoute(summary: PublicRouteSummary) {
        publicRouteSelections[summary.id] = summary
    }

    fun publicRoutePreview(id: String): PublicRoutesViewModel? {
        val summary = publicRouteSelections[id] ?: return null
        val model: PublicRoutesViewModel =
            screenModel(
                PublicRoutesViewModel.factory(
                    routeRepository = dependencies.personalRouteRepository,
                    draftRepository = dependencies.personalRouteDraftRepository,
                    exhibitionsState = tabs.allExhibitions,
                    authState = authStateFlow,
                    analytics = mobileAnalyticsTracker,
                    walkingRoutes = dependencies.walkingRouteRepository,
                    routesEnabled = false,
                ),
            )
        model.openPreview(summary)
        return model
    }

    fun observePublicRoutePreview(
        viewModel: PublicRoutesViewModel,
        onChange: (PublicRoutesUiState) -> Unit,
    ): NativeObservation = viewModel.state.observeForNative(scope, onChange)

    fun eventDetail(id: String): EventDetailViewModel =
        screenModel(EventDetailViewModel.factory(id, dependencies.eventRepository))

    fun editorDetail(id: String): EditorDetailViewModel =
        screenModel(EditorDetailViewModel.factory(id, dependencies.editorRepository, tabs))

    fun exhibitionThoughts(id: String): ExhibitionThoughtsViewModel =
        screenModel(
            factory =
                ExhibitionThoughtsViewModel.factory(
                    exhibitionId = id,
                    authState = authStateFlow.value,
                    thoughtRepository = dependencies.thoughtRepository,
                    hiddenThoughtAuthorRepository = dependencies.hiddenThoughtAuthorRepository,
                    publicNoteRepository = accountSync.publicNotes,
                ),
        )

    fun curatorNote(id: String): CuratorNoteViewModel =
        screenModel(CuratorNoteViewModel.factory(id, dependencies.curatorNoteRepository))

    fun addToRoute(exhibition: Exhibition): AddToRouteViewModel =
        screenModel(
            factory =
                AddToRouteViewModel.factory(
                    exhibition,
                    dependencies.personalRouteDraftRepository,
                    mobileAnalyticsTracker,
                ),
        )

    fun diaryEntry(exhibition: Exhibition): DiaryEntryViewModel =
        screenModel(
            factory =
                DiaryEntryViewModel.factory(
                    exhibition = exhibition,
                    visitRepository = syncedVisitRepository,
                    publicNoteRepository = accountSync.publicNotes,
                    authState = authStateFlow,
                    syncStatus = myGallrSyncCoordinator.observeStatus(),
                    onVisitRecorded = {
                        recordExhibitionIntent(
                            exhibition.id,
                            AnalyticsSurface.EXHIBITION_DETAIL,
                            AnalyticsIntentAction.VISIT_RECORDED,
                        )
                    },
                    refreshAccount = myGallrSyncCoordinator::refresh,
                ),
        )

    fun profile(user: GallrUser): ProfileViewModel =
        screenModel(
            factory = ProfileViewModel.factory(user, dependencies.profileRepository, accountSync.publicNotes),
        )

    fun editProfile(
        user: GallrUser,
        profile: Profile?,
    ): EditProfileViewModel = screenModel(EditProfileViewModel.factory(user, profile, dependencies.profileRepository))

    fun observe(
        state: StateFlow<*>,
        onChange: (Any?) -> Unit,
    ): NativeObservation = state.observeForNative(scope, onChange)

    fun observeHome(onChange: (HomeUiState) -> Unit): NativeObservation = home.state.observeForNative(scope, onChange)

    fun observeList(onChange: (ListScreenUiState) -> Unit): NativeObservation =
        tabs.listScreenState.observeForNative(scope, onChange)

    fun observeMap(onChange: (MapScreenUiState) -> Unit): NativeObservation =
        tabs.mapScreenState.observeForNative(scope, onChange)

    fun observePersonalMap(onChange: (PersonalMapUiState) -> Unit): NativeObservation =
        personalMap.uiState.observeForNative(scope, onChange)

    fun observeCatalogue(onChange: (ExhibitionListState) -> Unit): NativeObservation =
        tabs.allExhibitions.observeForNative(scope, onChange)

    fun observeBookmarks(onChange: (Set<String>) -> Unit): NativeObservation =
        tabs.bookmarkedIds.observeForNative(scope, onChange)

    fun observeInitialPresentation(onChange: (AppLanguage, ThemeMode) -> Unit): NativeObservation =
        NativeObservation(
            scope.launch {
                val language = dependencies.languageRepository.observeLanguage().first()
                val theme = dependencies.themeRepository.observeThemeMode().first()
                onChange(language, theme)
            },
        )

    fun observeLanguage(onChange: (AppLanguage) -> Unit): NativeObservation =
        tabs.language.observeForNative(scope, onChange)

    fun observeTheme(onChange: (ThemeMode) -> Unit): NativeObservation =
        tabs.themeMode.observeForNative(scope, onChange)

    fun observeAuth(onChange: (AuthState) -> Unit): NativeObservation = authStateFlow.observeForNative(scope, onChange)

    fun observeDiary(onChange: (DiaryUiState) -> Unit): NativeObservation =
        diary.state.observeForNative(scope, onChange)

    fun observeMyGallr(onChange: (MyGallrUiState) -> Unit): NativeObservation =
        myGallr.uiState.observeForNative(scope, onChange)

    fun observeEditorSelector(onChange: (EditorSelectorState) -> Unit): NativeObservation =
        editorSelector.state.observeForNative(scope, onChange)

    fun observeTastePick(onChange: (TastePickSheetState?) -> Unit): NativeObservation =
        tastePick.sheet.observeForNative(scope, onChange)

    fun observeTasteOffer(onChange: (Boolean) -> Unit): NativeObservation =
        combine(localDiscovery.hasHistory, localDiscovery.tasteState) { hasHistory, taste ->
            shouldOfferTastePick(
                hasHistory,
                taste,
                TastePickBlockers(updateNudgeShowing = false, splashVisible = false),
            )
        }.observeForNative(scope, onChange)

    fun observeRecommendations(onChange: (RecommendationUiState) -> Unit): NativeObservation =
        localDiscovery.recommendationState.observeForNative(scope, onChange)

    fun observePublicRouteBrowse(onChange: (PublicRouteBrowseUiState) -> Unit): NativeObservation =
        publicRouteBrowse.state.observeForNative(scope, onChange)

    fun observeRoutes(onChange: (PublicRoutesUiState) -> Unit): NativeObservation =
        publicRoutes.state.observeForNative(scope, onChange)

    fun observeMyRoutes(onChange: (MyRoutesUiState) -> Unit): NativeObservation =
        myRoutes.state.observeForNative(scope, onChange)

    fun openPlannerStop(
        exhibition: Exhibition,
        start: Boolean,
    ): Boolean {
        val result = openExhibitionInMap(exhibition, tabs.language.value, IosExternalMapLauncher())
        if (result == null || result.isFailure) {
            result?.exceptionOrNull()?.let { log.warn("route_map_handoff", it) }
            return false
        }
        val state = localDiscovery.routeState.value as? RouteUiState.Ready
        if (start && state != null && localDiscovery.markRouteStarted()) {
            val summary = state.estimate.toAnalyticsSummary()
            scope.launch {
                mobileAnalyticsTracker.routeStarted(
                    summary.mode,
                    summary.stopCount,
                    summary.distanceBand,
                    summary.durationBand,
                )
            }
        }
        return true
    }

    fun observePlanner(onChange: (RouteUiState) -> Unit): NativeObservation =
        localDiscovery.routeState.observeForNative(scope) { state ->
            if (state is RouteUiState.Ready && state.buildId != lastRecordedRouteBuild) {
                lastRecordedRouteBuild = state.buildId
                val summary = state.estimate.toAnalyticsSummary()
                scope.launch {
                    mobileAnalyticsTracker.routeCreated(
                        summary.mode,
                        summary.stopCount,
                        summary.distanceBand,
                        summary.durationBand,
                    )
                }
            }
            onChange(state)
        }

    fun observeRouteComposer(onChange: (RouteComposerUiState) -> Unit): NativeObservation =
        routeComposer.state.observeForNative(scope, onChange)

    fun observeSignIn(onChange: (SignInUiState) -> Unit): NativeObservation =
        signIn.uiState.observeForNative(scope, onChange)

    fun observeHiddenAuthors(onChange: (HiddenAuthorsUiState) -> Unit): NativeObservation =
        hiddenAuthors.state.observeForNative(scope, onChange)

    fun observeNavigation(onChange: (NativeNavigationRequest) -> Unit): NativeObservation =
        navigation.filterNotNull().observeForNative(scope, onChange)

    fun observeUpdateRequirement(onChange: (UpdateRequirement) -> Unit): NativeObservation =
        dependencies.appUpdateRepository.requirement.observeForNative(scope, onChange)

    fun observeUpdateNudge(onChange: (Boolean) -> Unit): NativeObservation =
        combine(
            dependencies.appUpdateRepository.requirement,
            dependencies.appUpdateRepository.nudgeDismissedOn,
        ) { requirement, dismissed ->
            showUpdateNudge(requirement, dismissed, Clock.System.todayIn(TimeZone.of("Asia/Seoul")))
        }.observeForNative(scope, onChange)

    fun observeNotificationPrompt(onChange: (Boolean) -> Unit): NativeObservation =
        notificationPrompt.observeForNative(scope, onChange)

    fun observeAnalyticsEnabled(onChange: (Boolean?) -> Unit): NativeObservation =
        dependencies.mobileAnalyticsController.observeUserEnabled().observeForNative(scope, onChange)

    fun observeGalleryDetail(
        viewModel: GalleryDetailViewModel,
        onChange: (GalleryDetailUiState) -> Unit,
    ): NativeObservation = viewModel.uiState.observeForNative(scope, onChange)

    fun observeEventDetail(
        viewModel: EventDetailViewModel,
        onChange: (EventDetailUiState) -> Unit,
    ): NativeObservation = viewModel.state.observeForNative(scope, onChange)

    fun observeEditorDetail(
        viewModel: EditorDetailViewModel,
        onChange: (EditorDetailUiState) -> Unit,
    ): NativeObservation = viewModel.state.observeForNative(scope, onChange)

    fun observeExhibitionThoughts(
        viewModel: ExhibitionThoughtsViewModel,
        onChange: (ExhibitionThoughtsUiState) -> Unit,
    ): NativeObservation =
        NativeObservation(
            scope.launch {
                launch { authStateFlow.collect(viewModel::onAuthStateChanged) }
                viewModel.uiState.collect(onChange)
            },
        )

    fun observeCuratorNote(
        viewModel: CuratorNoteViewModel,
        onChange: (CuratorNoteUiState) -> Unit,
    ): NativeObservation = viewModel.state.observeForNative(scope, onChange)

    fun observeCuratorImages(
        viewModel: CuratorNoteViewModel,
        onChange: (List<ExhibitionImage>) -> Unit,
    ): NativeObservation = viewModel.images.observeForNative(scope, onChange)

    fun observeAddToRoute(
        viewModel: AddToRouteViewModel,
        onChange: (AddToRouteUiState) -> Unit,
    ): NativeObservation = viewModel.state.observeForNative(scope, onChange)

    fun observeDiaryEntry(
        viewModel: DiaryEntryViewModel,
        onChange: (DiaryEntryUiState) -> Unit,
    ): NativeObservation = viewModel.state.observeForNative(scope, onChange)

    fun observeProfile(
        viewModel: ProfileViewModel,
        onChange: (ProfileUiState) -> Unit,
    ): NativeObservation = viewModel.uiState.observeForNative(scope, onChange)

    fun observeEditProfile(
        viewModel: EditProfileViewModel,
        onChange: (EditProfileUiState) -> Unit,
    ): NativeObservation = viewModel.uiState.observeForNative(scope, onChange)

    fun releaseViewModel(viewModel: ViewModel) {
        screenViewModels.remove(viewModel)?.clear()
    }

    private inline fun <reified T : ViewModel> screenModel(factory: ViewModelProvider.Factory): T {
        check(!closed)
        val store = ViewModelStore()
        val model = ViewModelProvider.create(store, factory)[T::class]
        screenViewModels[model] = store
        return model
    }

    private inline fun <reified T : ViewModel> retained(
        key: String = T::class.simpleName.orEmpty(),
        factory: ViewModelProvider.Factory,
    ): T {
        check(!closed)
        return ViewModelProvider.create(viewModels, factory)[key, T::class]
    }

    private companion object {
        const val CATALOG_REFRESH_CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1_000L
        val log = AppLog.tagged("NativeGallrApp")
    }
}

class NativeNavigationRequest internal constructor(
    val exhibition: Exhibition?,
)
