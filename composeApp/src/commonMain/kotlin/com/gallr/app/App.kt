package com.gallr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.analytics.DiscoveryAttribution
import com.gallr.app.analytics.MobileAnalyticsTracker
import com.gallr.app.analytics.ROUTE_DETAIL_ANALYTICS_SUPPRESSED
import com.gallr.app.analytics.RouteMapHandoffAction
import com.gallr.app.analytics.routeMapHandoffAnalyticsDecision
import com.gallr.app.analytics.toAnalyticsSummary
import com.gallr.app.notifications.NotificationPermissionHandler
import com.gallr.app.notifications.RemotePushAddressProvider
import com.gallr.app.splash.SplashController
import com.gallr.app.splash.SplashOverlay
import com.gallr.app.ui.components.GallrNavigationBar
import com.gallr.app.ui.detail.ExhibitionDetailScreen
import com.gallr.app.ui.discovery.RecommendationsScreen
import com.gallr.app.ui.editor.EditorDetailScreen
import com.gallr.app.ui.editor.EditorSelectorScreen
import com.gallr.app.ui.event.EventDetailScreen
import com.gallr.app.ui.gallery.GalleryDetailScreen
import com.gallr.app.ui.profile.CropOverlayState
import com.gallr.app.ui.profile.CropScreen
import com.gallr.app.ui.profile.LocalCropOverlay
import com.gallr.app.ui.route.RoutePlannerScreen
import com.gallr.app.ui.route.routeMapOpenErrorLabel
import com.gallr.app.ui.settings.SettingsScreen
import com.gallr.app.ui.tabs.featured.FeaturedScreen
import com.gallr.app.ui.tabs.list.ListScreen
import com.gallr.app.ui.tabs.map.MapScreen
import com.gallr.app.ui.theme.GallrTheme
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.app.viewmodel.EditorSelectorViewModel
import com.gallr.app.viewmodel.EventDetailViewModel
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.GalleryDetailViewModel
import com.gallr.app.viewmodel.LocalDiscoveryViewModel
import com.gallr.app.viewmodel.PersonalMapViewModel
import com.gallr.app.viewmodel.RouteUiState
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.visitFromExhibition
import com.gallr.shared.analytics.AnalyticsEntryPoint
import com.gallr.shared.analytics.AnalyticsIntentAction
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.DiscoveryKind
import com.gallr.shared.analytics.MobileAnalyticsController
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.analytics.PositionBucket
import com.gallr.shared.analytics.positionBucket
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.map.toExternalMapDestination
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.AuthAwareFollowedGalleryRepository
import com.gallr.shared.repository.AuthAwareVisitRepository
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.EditorRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.GalleryAlertRegistrationRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.MyGallrAccountNudgeRepository
import com.gallr.shared.repository.MyGallrAccountStore
import com.gallr.shared.repository.MyGallrAccountSyncCoordinator
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.NotificationPreferences
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.PromotionRepository
import com.gallr.shared.repository.SyncBookmarkRepository
import com.gallr.shared.repository.ThemeRepository
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.repository.VisitRepository
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_settings
import gallr.composeapp.generated.resources.logo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Clock

private const val MY_LIST_TAB_INDEX = 1
private const val PROFILE_TAB_INDEX = 3
private const val CATALOG_REFRESH_CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1_000L
private val appLog = AppLog.tagged("App")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    exhibitionRepository: ExhibitionRepository,
    eventRepository: EventRepository,
    editorRepository: EditorRepository,
    localBookmarkRepository: BookmarkRepositoryImpl,
    cloudBookmarkRepository: CloudBookmarkRepository,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    visitRepository: VisitRepository,
    followedGalleryRepository: FollowedGalleryRepository,
    myGallrAccountStore: MyGallrAccountStore,
    myGallrAccountSource: MyGallrAccountCommandSource,
    galleryAlertRegistrationRepository: GalleryAlertRegistrationRepository,
    remotePushAddressProvider: RemotePushAddressProvider,
    accountNudgeRepository: MyGallrAccountNudgeRepository,
    languageRepository: LanguageRepository,
    themeRepository: ThemeRepository,
    promotionRepository: PromotionRepository,
    splashController: SplashController,
    notificationScheduler: NotificationScheduler,
    notificationSyncService: NotificationSyncService,
    notificationPreferences: NotificationPreferences,
    externalMapLauncher: ExternalMapLauncher,
    mobileAnalyticsController: MobileAnalyticsController,
    mobileAnalyticsEventFactory: MobileAnalyticsEventFactory?,
) {
    // Auth state drives SyncBookmarkRepository delegation
    val authState by authRepository
        .observeAuthState()
        .collectAsState(initial = AuthState.Loading)

    val authStateFlow =
        remember {
            kotlinx.coroutines.flow.MutableStateFlow<AuthState>(AuthState.Loading)
        }
    val syncBookmarkRepository =
        remember {
            SyncBookmarkRepository(localBookmarkRepository, cloudBookmarkRepository, authStateFlow)
        }
    val myGallrSyncCoordinator =
        remember {
            MyGallrAccountSyncCoordinator(
                guestVisitRepository = visitRepository,
                guestFollowedGalleryRepository = followedGalleryRepository,
                accountStore = myGallrAccountStore,
                source = myGallrAccountSource,
            )
        }
    val syncedVisitRepository =
        remember { AuthAwareVisitRepository(visitRepository, myGallrSyncCoordinator) }
    val syncedFollowedGalleryRepository =
        remember { AuthAwareFollowedGalleryRepository(followedGalleryRepository, myGallrSyncCoordinator) }
    val myGallrSyncStatus by
        myGallrSyncCoordinator.observeStatus().collectAsState(initial = MyGallrSyncStatus.DEVICE_ONLY)

    var isAdmin by remember { mutableStateOf(false) }

    var bookmarkMutationCount by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        syncBookmarkRepository.setMutationListener {
            bookmarkMutationCount += 1
            notificationSyncService.sync(triggeredByMutation = true)
        }
    }

    // Keep the StateFlow in sync + migrate & refresh bookmarks on login
    androidx.compose.runtime.LaunchedEffect(authState) {
        authStateFlow.value = authState
        if (authState is AuthState.Authenticated) {
            val userId = (authState as AuthState.Authenticated).user.id
            try {
                myGallrSyncCoordinator.activateAccount(userId)
            } catch (error: Exception) {
                appLog.warn("my_gallr_account_sync", error)
            }
            try {
                syncBookmarkRepository.migrateLocalToCloud()
            } catch (error: Exception) {
                appLog.warn("bookmark_cloud_sync", error)
            }
            // Check admin status
            try {
                val profile = profileRepository.getProfile(userId)
                isAdmin = profile?.isAdmin == true
            } catch (error: Exception) {
                appLog.warn("admin_profile_check", error)
                isAdmin = false
            }
        } else {
            myGallrSyncCoordinator.deactivateAccount()
            isAdmin = false
        }
    }

    val viewModel: TabsViewModel =
        viewModel(
            factory =
                TabsViewModel.factory(
                    exhibitionRepository = exhibitionRepository,
                    bookmarkRepository = syncBookmarkRepository,
                    languageRepository = languageRepository,
                    themeRepository = themeRepository,
                    eventRepository = eventRepository,
                    authState = authStateFlow,
                    profileNudgeRepository = localBookmarkRepository,
                    promotionRepository = promotionRepository,
                ),
        )

    val personalMapViewModel: PersonalMapViewModel =
        viewModel(
            key = "personal-map",
            factory =
                PersonalMapViewModel.factory(
                    exhibitionsState = viewModel.allExhibitions,
                    bookmarkedIds = viewModel.bookmarkedIds,
                    language = viewModel.language,
                ),
        )

    val localDiscoveryViewModel: LocalDiscoveryViewModel =
        viewModel(
            key = "local-discovery",
            factory =
                LocalDiscoveryViewModel.factory(
                    exhibitionsState = viewModel.allExhibitions,
                    bookmarkedIds = viewModel.bookmarkedIds,
                    visitRepository = syncedVisitRepository,
                    followedGalleryRepository = syncedFollowedGalleryRepository,
                    language = viewModel.language,
                ),
        )

    val currentThemeMode by viewModel.themeMode.collectAsState()
    val analyticsEnabled by
        mobileAnalyticsController
            .observeUserEnabled()
            .collectAsState(initial = null)

    val lifecycleOwner = LocalLifecycleOwner.current
    val appCoroutineScope = rememberCoroutineScope()
    val mobileAnalyticsTracker =
        remember(mobileAnalyticsController, mobileAnalyticsEventFactory) {
            MobileAnalyticsTracker(mobileAnalyticsController, mobileAnalyticsEventFactory)
        }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshIfStale()
                    appCoroutineScope.launch { myGallrSyncCoordinator.refresh() }
                    appCoroutineScope.launch {
                        runCatching { mobileAnalyticsController.onResume() }
                            .onFailure { error -> appLog.warn("mobile_analytics_resume", error) }
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(mobileAnalyticsController) {
        runCatching { mobileAnalyticsController.initialize() }
            .onFailure { error -> appLog.warn("mobile_analytics_initialize", error) }
    }

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        while (isActive) {
            delay(CATALOG_REFRESH_CHECK_INTERVAL_MILLIS)
            viewModel.refreshIfStale()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // First emit from DataStore — by definition the saved value (or default)
        themeRepository.observeThemeMode().first()
        splashController.markThemeReady()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.featuredState
            .first { it !is ExhibitionListState.Loading }
        splashController.markDataReady()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.featuredState
            .first { it is ExhibitionListState.Success }
        notificationSyncService.sync(triggeredByMutation = false)
    }

    // null until DataStore yields its first value — prevents the rationale
    // dialog from flashing in the brief window before the saved flag arrives.
    val permissionPromptedState =
        notificationPreferences
            .observePermissionPrompted()
            .collectAsState(initial = null)
    val permissionPrompted = permissionPromptedState.value
    val notificationCoroutineScope = rememberCoroutineScope()

    GallrTheme(themeMode = currentThemeMode) {
        val lang by viewModel.language.collectAsState()
        val recommendationState by localDiscoveryViewModel.recommendationState.collectAsState()
        val routeState by localDiscoveryViewModel.routeState.collectAsState()

        androidx.compose.runtime.LaunchedEffect(lang, authState) {
            if (!notificationScheduler.hasPermission()) return@LaunchedEffect
            val enabledGalleries =
                syncedFollowedGalleryRepository
                    .observeFollowedGalleries()
                    .first()
                    .filter { it.newExhibitionAlertsEnabled && it.galleryId != null }
            if (enabledGalleries.isEmpty()) return@LaunchedEffect

            val address = remotePushAddressProvider.currentAddress() ?: return@LaunchedEffect
            val locale = if (lang == AppLanguage.KO) "ko-KR" else "en-US"
            enabledGalleries.forEach { gallery ->
                galleryAlertRegistrationRepository
                    .enableGallery(
                        galleryId = checkNotNull(gallery.galleryId),
                        address = address,
                        locale = locale,
                    ).onFailure { error -> appLog.warn("gallery_alert_registration_sync", error) }
            }
        }

        NotificationPermissionHandler(
            scheduler = notificationScheduler,
            syncService = notificationSyncService,
            bookmarkMutationCount = bookmarkMutationCount,
            permissionPrompted = permissionPrompted,
            onPrompted = { notificationCoroutineScope.launch { notificationPreferences.setPermissionPrompted() } },
            language = lang,
        )

        val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
        val showSignUpNudge by viewModel.showSignUpNudge.collectAsState()
        val navigation = rememberAppNavigationState()
        var exhibitionDetailEntryPoint by remember { mutableStateOf(AnalyticsEntryPoint.CARD) }
        val recordedRouteBuilds = remember { mutableSetOf<Long>() }
        var routeMapOpenError by remember { mutableStateOf<String?>(null) }

        fun recordIntent(
            exhibitionId: String,
            surface: AnalyticsSurface,
            action: AnalyticsIntentAction,
        ) {
            appCoroutineScope.launch {
                mobileAnalyticsTracker.exhibitionIntent(exhibitionId, surface, action)
            }
        }

        fun openExhibition(
            exhibition: Exhibition,
            attribution: DiscoveryAttribution,
            entryPoint: AnalyticsEntryPoint,
            impressionOnOpen: Boolean = false,
            analyticsSuppressed: Boolean = false,
            returnTo: AppDestination = AppDestination.Tabs,
        ) {
            exhibitionDetailEntryPoint = entryPoint
            if (!analyticsSuppressed) {
                appCoroutineScope.launch {
                    if (impressionOnOpen) {
                        mobileAnalyticsTracker.exhibitionImpression(exhibition.id, attribution)
                    }
                    mobileAnalyticsTracker.exhibitionOpened(exhibition.id, attribution)
                }
            }
            navigation.showExhibition(
                exhibition = exhibition,
                analyticsSuppressed = analyticsSuppressed,
                returnTo = returnTo,
            )
        }

        fun toggleBookmark(
            exhibitionId: String,
            surface: AnalyticsSurface,
        ) {
            viewModel.toggleBookmark(exhibitionId) { saved ->
                recordIntent(
                    exhibitionId = exhibitionId,
                    surface = surface,
                    action =
                        if (saved) {
                            AnalyticsIntentAction.BOOKMARK_ADD
                        } else {
                            AnalyticsIntentAction.BOOKMARK_REMOVE
                        },
                )
            }
        }

        androidx.compose.runtime.LaunchedEffect(
            mobileAnalyticsController,
            navigation.destination,
            navigation.selectedTab,
        ) {
            val currentDestination = navigation.destination
            val surfaceVisit =
                when (currentDestination) {
                    AppDestination.Tabs -> {
                        val surface =
                            when (navigation.selectedTab) {
                                0 -> AnalyticsSurface.FEATURED
                                1 -> AnalyticsSurface.LIST
                                2 -> AnalyticsSurface.MAP
                                else -> AnalyticsSurface.MY_GALLR
                            }
                        surface to AnalyticsEntryPoint.TAB
                    }

                    is AppDestination.ExhibitionDetail -> {
                        if (currentDestination.analyticsSuppressed) {
                            null
                        } else {
                            AnalyticsSurface.EXHIBITION_DETAIL to exhibitionDetailEntryPoint
                        }
                    }

                    is AppDestination.GalleryDetail -> {
                        if (currentDestination.analyticsSuppressed) {
                            null
                        } else {
                            AnalyticsSurface.GALLERY_DETAIL to AnalyticsEntryPoint.CARD
                        }
                    }

                    is AppDestination.EventDetail -> {
                        AnalyticsSurface.EVENT_DETAIL to AnalyticsEntryPoint.CARD
                    }

                    is AppDestination.EditorDetail -> {
                        AnalyticsSurface.EDITOR_DETAIL to AnalyticsEntryPoint.CARD
                    }

                    AppDestination.Settings -> {
                        AnalyticsSurface.SETTINGS to AnalyticsEntryPoint.CARD
                    }

                    AppDestination.Recommendations -> {
                        AnalyticsSurface.FEATURED to AnalyticsEntryPoint.RECOMMENDATION
                    }

                    is AppDestination.RoutePlanner -> {
                        AnalyticsSurface.MAP to AnalyticsEntryPoint.ROUTE
                    }

                    AppDestination.EditorSelector -> {
                        null
                    }
                }
            if (surfaceVisit != null) {
                runCatching {
                    mobileAnalyticsController.initialize()
                    mobileAnalyticsTracker.surfaceViewed(surfaceVisit.first, surfaceVisit.second)
                }.onFailure { error -> appLog.warn("mobile_analytics_surface", error) }
            }
        }

        val visitsState by
            syncedVisitRepository
                .observeVisits()
                .collectAsState(initial = null)
        val visits = visitsState.orEmpty()
        val followedGalleries by
            syncedFollowedGalleryRepository
                .observeFollowedGalleries()
                .collectAsState(initial = emptyList())
        androidx.compose.runtime.LaunchedEffect(Unit) {
            notificationScheduler.pendingDeepLink.collect { link ->
                when (val pendingLink = link ?: return@collect) {
                    is DeepLink.Exhibition -> {
                        val target =
                            viewModel.findExhibitionById(pendingLink.id)
                                ?: exhibitionRepository
                                    .getExhibitions()
                                    .getOrNull()
                                    ?.firstOrNull { it.id == pendingLink.id }
                        if (target != null) {
                            openExhibition(
                                exhibition = target,
                                attribution =
                                    DiscoveryAttribution(
                                        surface = AnalyticsSurface.MY_GALLR,
                                        kind = DiscoveryKind.NOTIFICATION,
                                        position = PositionBucket.UNRANKED,
                                    ),
                                entryPoint = AnalyticsEntryPoint.NOTIFICATION,
                            )
                        } else {
                            navigation.selectTab(MY_LIST_TAB_INDEX)
                        }
                        notificationScheduler.consumePendingDeepLink()
                    }

                    is DeepLink.MyList -> {
                        navigation.selectTab(MY_LIST_TAB_INDEX)
                        notificationScheduler.consumePendingDeepLink()
                    }
                }
            }
        }

        val cropOverlayState = remember { CropOverlayState() }
        val shareHandler = remember { createShareHandler() }

        CompositionLocalProvider(LocalCropOverlay provides cropOverlayState) {
            Box(modifier = Modifier.fillMaxSize()) {
                // ── Detail screen with back handler ──────────────────────────────
                AnimatedContent(
                    targetState = navigation.destination,
                    transitionSpec = {
                        fadeIn(
                            animationSpec =
                                androidx.compose.animation.core
                                    .tween(200),
                        ) togetherWith
                            fadeOut(
                                animationSpec =
                                    androidx.compose.animation.core
                                        .tween(200),
                            )
                    },
                    label = "detailTransition",
                ) { destination ->
                    when (destination) {
                        is AppDestination.ExhibitionDetail -> {
                            val exhibition = destination.exhibition
                            val mapDestination = exhibition.toExternalMapDestination(lang)
                            val analyticsSuppressed = destination.analyticsSuppressed

                            fun recordDetailIntent(action: AnalyticsIntentAction) {
                                if (!analyticsSuppressed) {
                                    recordIntent(
                                        exhibition.id,
                                        AnalyticsSurface.EXHIBITION_DETAIL,
                                        action,
                                    )
                                }
                            }
                            var isVisitSaving by remember(exhibition.id) { mutableStateOf(false) }
                            var visitSaveFailed by remember(exhibition.id) { mutableStateOf(false) }
                            PlatformBackHandler(navigation::returnFromExhibition)
                            ExhibitionDetailScreen(
                                exhibition = exhibition,
                                lang = lang,
                                isBookmarked = exhibition.id in bookmarkedIds,
                                onBookmarkToggle = {
                                    if (analyticsSuppressed) {
                                        viewModel.toggleBookmark(exhibition.id)
                                    } else {
                                        toggleBookmark(exhibition.id, AnalyticsSurface.EXHIBITION_DETAIL)
                                    }
                                },
                                onShare = {
                                    shareHandler
                                        .shareExhibition(exhibition, lang)
                                        .onSuccess {
                                            recordDetailIntent(AnalyticsIntentAction.SHARE_SHEET_OPENED)
                                        }
                                },
                                onGalleryTap = {
                                    recordDetailIntent(AnalyticsIntentAction.GALLERY_OPEN)
                                    navigation.showGallery(exhibition, analyticsSuppressed)
                                },
                                onOpenMap =
                                    if (mapDestination == null) {
                                        null
                                    } else {
                                        {
                                            openExhibitionInMap(
                                                exhibition = exhibition,
                                                language = lang,
                                                launcher = externalMapLauncher,
                                            )?.onSuccess {
                                                recordDetailIntent(AnalyticsIntentAction.OPEN_MAPS)
                                            }?.onFailure { error -> appLog.warn("open_exhibition_map", error) }
                                        }
                                    },
                                onContactOpened = {
                                    recordDetailIntent(AnalyticsIntentAction.CONTACT)
                                },
                                onTicketOpened = {
                                    recordDetailIntent(AnalyticsIntentAction.TICKET)
                                },
                                isVisited = visits.any { it.exhibitionId == exhibition.id },
                                isVisitSaving = isVisitSaving,
                                visitSaveFailed = visitSaveFailed,
                                onMarkVisited = {
                                    if (!isVisitSaving) {
                                        appCoroutineScope.launch {
                                            isVisitSaving = true
                                            visitSaveFailed = false
                                            val createdAt = Clock.System.now()
                                            runCatching {
                                                syncedVisitRepository.addVisits(
                                                    listOf(
                                                        visitFromExhibition(
                                                            exhibition = exhibition,
                                                            createdAt = createdAt,
                                                            clientRecordId =
                                                                "${exhibition.id}:${createdAt.toEpochMilliseconds()}",
                                                        ),
                                                    ),
                                                )
                                            }.onSuccess {
                                                recordDetailIntent(AnalyticsIntentAction.VISIT_RECORDED)
                                            }.onFailure { error ->
                                                appLog.warn("mark_exhibition_visited", error)
                                                visitSaveFailed = true
                                            }
                                            isVisitSaving = false
                                        }
                                    }
                                },
                                onBack = navigation::returnFromExhibition,
                                thoughtRepository = thoughtRepository,
                                authState = authState,
                                isAdmin = isAdmin,
                            )
                        }

                        is AppDestination.GalleryDetail -> {
                            val exhibition = destination.exhibition
                            val analyticsSuppressed = destination.analyticsSuppressed
                            PlatformBackHandler(navigation::returnFromGallery)
                            val galleryDetailViewModel: GalleryDetailViewModel =
                                viewModel(
                                    key = "gallery-${exhibition.galleryId ?: exhibition.venueNameKo}",
                                    factory =
                                        GalleryDetailViewModel.factory(
                                            representative = exhibition,
                                            exhibitionsState = viewModel.allExhibitions,
                                            followedGalleryRepository = syncedFollowedGalleryRepository,
                                            notificationScheduler = notificationScheduler,
                                            galleryAlertRegistrationRepository = galleryAlertRegistrationRepository,
                                            remotePushAddressProvider = remotePushAddressProvider,
                                            visitRepository = syncedVisitRepository,
                                            locale = if (lang == AppLanguage.KO) "ko-KR" else "en-US",
                                        ),
                                )
                            GalleryDetailScreen(
                                viewModel = galleryDetailViewModel,
                                lang = lang,
                                onBack = navigation::returnFromGallery,
                                onExhibitionTap = { candidate ->
                                    openExhibition(
                                        candidate,
                                        DiscoveryAttribution(
                                            AnalyticsSurface.GALLERY_DETAIL,
                                            DiscoveryKind.GALLERY,
                                            PositionBucket.UNRANKED,
                                        ),
                                        AnalyticsEntryPoint.CARD,
                                        analyticsSuppressed = analyticsSuppressed,
                                    )
                                },
                            )
                        }

                        is AppDestination.EventDetail -> {
                            val eventId = destination.eventId
                            PlatformBackHandler(navigation::showTabs)
                            val eventDetailVm: EventDetailViewModel =
                                viewModel(
                                    key = "event-$eventId",
                                    factory = EventDetailViewModel.factory(eventId, eventRepository),
                                )
                            EventDetailScreen(
                                viewModel = eventDetailVm,
                                lang = lang,
                                bookmarkedIds = bookmarkedIds,
                                onToggleBookmark = { id ->
                                    toggleBookmark(id, AnalyticsSurface.EVENT_DETAIL)
                                },
                                onBack = navigation::showTabs,
                                onExhibitionTap = { candidate ->
                                    openExhibition(
                                        candidate,
                                        DiscoveryAttribution(
                                            AnalyticsSurface.EVENT_DETAIL,
                                            DiscoveryKind.EVENT,
                                            PositionBucket.UNRANKED,
                                        ),
                                        AnalyticsEntryPoint.CARD,
                                    )
                                },
                            )
                        }

                        is AppDestination.EditorDetail -> {
                            val editorId = destination.editorId
                            PlatformBackHandler(navigation::showTabs)
                            val editorDetailVm: EditorDetailViewModel =
                                viewModel(
                                    key = "editor-$editorId",
                                    factory =
                                        EditorDetailViewModel.factory(
                                            editorId = editorId,
                                            editorRepository = editorRepository,
                                            tabsViewModel = viewModel,
                                        ),
                                )
                            EditorDetailScreen(
                                viewModel = editorDetailVm,
                                bookmarkedIds = bookmarkedIds,
                                onToggleBookmark = { id ->
                                    toggleBookmark(id, AnalyticsSurface.EDITOR_DETAIL)
                                },
                                onBack = navigation::showTabs,
                                onExhibitionTap = { candidate ->
                                    openExhibition(
                                        candidate,
                                        DiscoveryAttribution(
                                            AnalyticsSurface.EDITOR_DETAIL,
                                            DiscoveryKind.EDITOR,
                                            PositionBucket.UNRANKED,
                                        ),
                                        AnalyticsEntryPoint.CARD,
                                    )
                                },
                            )
                        }

                        AppDestination.Recommendations -> {
                            PlatformBackHandler(navigation::showTabs)
                            RecommendationsScreen(
                                state = recommendationState,
                                lang = lang,
                                bookmarkedIds = bookmarkedIds,
                                onRecommendationsShown = { _, resultCount ->
                                    appCoroutineScope.launch {
                                        mobileAnalyticsTracker.recommendationsShown(
                                            surface = AnalyticsSurface.FEATURED,
                                            resultCount = resultCount,
                                        )
                                    }
                                },
                                onBookmarkToggle = { exhibition ->
                                    toggleBookmark(exhibition.id, AnalyticsSurface.FEATURED)
                                },
                                onExhibitionTap = { exhibition, index ->
                                    openExhibition(
                                        exhibition = exhibition,
                                        attribution =
                                            DiscoveryAttribution(
                                                surface = AnalyticsSurface.FEATURED,
                                                kind = DiscoveryKind.RECOMMENDATION,
                                                position = positionBucket(index),
                                            ),
                                        entryPoint = AnalyticsEntryPoint.RECOMMENDATION,
                                        returnTo = AppDestination.Recommendations,
                                    )
                                },
                                onImpressions = { exposures ->
                                    appCoroutineScope.launch {
                                        exposures.forEach { exposure ->
                                            mobileAnalyticsTracker.exhibitionImpression(
                                                exposure.exhibitionId,
                                                DiscoveryAttribution(
                                                    surface = AnalyticsSurface.FEATURED,
                                                    kind = DiscoveryKind.RECOMMENDATION,
                                                    position = exposure.position,
                                                ),
                                            )
                                        }
                                    }
                                },
                                onBack = navigation::showTabs,
                                onRetry = {
                                    viewModel.refresh()
                                    localDiscoveryViewModel.retryRecommendations()
                                },
                            )
                        }

                        is AppDestination.RoutePlanner -> {
                            PlatformBackHandler(navigation::showTabs)
                            androidx.compose.runtime.LaunchedEffect(
                                destination.origin,
                                destination.initialMode,
                                destination.requestId,
                            ) {
                                routeMapOpenError = null
                                localDiscoveryViewModel.beginRouteIfNeeded(
                                    origin = destination.origin,
                                    initialMode = destination.initialMode,
                                    requestId = destination.requestId,
                                )
                            }

                            val ready = routeState as? RouteUiState.Ready
                            androidx.compose.runtime.LaunchedEffect(ready?.buildId) {
                                if (ready != null && recordedRouteBuilds.add(ready.buildId)) {
                                    val summary = ready.estimate.toAnalyticsSummary()
                                    mobileAnalyticsTracker.routeCreated(
                                        mode = summary.mode,
                                        stopCount = summary.stopCount,
                                        distanceBand = summary.distanceBand,
                                        durationBand = summary.durationBand,
                                    )
                                }
                            }

                            fun openRouteStop(
                                exhibition: Exhibition,
                                action: RouteMapHandoffAction,
                            ) {
                                val analyticsDecision = routeMapHandoffAnalyticsDecision(action)
                                val result =
                                    openExhibitionInMap(
                                        exhibition = exhibition,
                                        language = lang,
                                        launcher = externalMapLauncher,
                                    )
                                if (result == null) {
                                    routeMapOpenError = routeMapOpenErrorLabel(lang)
                                    return
                                }
                                result
                                    .onSuccess {
                                        routeMapOpenError = null
                                        val estimate =
                                            (localDiscoveryViewModel.routeState.value as? RouteUiState.Ready)
                                                ?.estimate
                                        if (
                                            analyticsDecision.recordsRouteStarted &&
                                            estimate != null &&
                                            localDiscoveryViewModel.markRouteStarted()
                                        ) {
                                            val summary = estimate.toAnalyticsSummary()
                                            appCoroutineScope.launch {
                                                mobileAnalyticsTracker.routeStarted(
                                                    mode = summary.mode,
                                                    stopCount = summary.stopCount,
                                                    distanceBand = summary.distanceBand,
                                                    durationBand = summary.durationBand,
                                                )
                                            }
                                        }
                                    }.onFailure { error ->
                                        appLog.warn("route_map_handoff", error)
                                        routeMapOpenError = routeMapOpenErrorLabel(lang)
                                    }
                            }

                            RoutePlannerScreen(
                                state = routeState,
                                lang = lang,
                                onModeChange = { mode ->
                                    routeMapOpenError = null
                                    localDiscoveryViewModel.setRouteMode(mode)
                                },
                                onStopCountChange = { count ->
                                    routeMapOpenError = null
                                    localDiscoveryViewModel.setStopCount(count)
                                },
                                onBuild = {
                                    routeMapOpenError = null
                                    localDiscoveryViewModel.buildRoute()
                                },
                                onReduceStops = {
                                    val insufficient = routeState as? RouteUiState.Insufficient
                                    if (insufficient != null && insufficient.request.stopCount > 2) {
                                        localDiscoveryViewModel.setStopCount(
                                            insufficient.request.stopCount - 1,
                                        )
                                        localDiscoveryViewModel.buildRoute()
                                    }
                                },
                                onStartRoute = { exhibition ->
                                    openRouteStop(exhibition, RouteMapHandoffAction.START_ROUTE)
                                },
                                onOpenStop = { exhibition ->
                                    openRouteStop(exhibition, RouteMapHandoffAction.OPEN_STOP)
                                },
                                onExhibitionTap = { exhibition, _ ->
                                    exhibitionDetailEntryPoint = AnalyticsEntryPoint.ROUTE
                                    navigation.showExhibition(
                                        exhibition = exhibition,
                                        analyticsSuppressed = ROUTE_DETAIL_ANALYTICS_SUPPRESSED,
                                        returnTo = destination,
                                    )
                                },
                                onBack = navigation::showTabs,
                                mapOpenError = routeMapOpenError,
                            )
                        }

                        AppDestination.EditorSelector -> {
                            PlatformBackHandler(navigation::showTabs)
                            val selectorVm: EditorSelectorViewModel =
                                viewModel(
                                    key = "editor-selector",
                                    factory =
                                        EditorSelectorViewModel.factory(
                                            editorRepository = editorRepository,
                                            tabsViewModel = viewModel,
                                        ),
                                )
                            EditorSelectorScreen(
                                viewModel = selectorVm,
                                onBack = navigation::showTabs,
                                onEditorTap = navigation::showEditor,
                            )
                        }

                        AppDestination.Settings -> {
                            SettingsScreen(
                                lang = lang,
                                themeMode = currentThemeMode,
                                analyticsEnabled = analyticsEnabled,
                                isAuthenticated = authState is AuthState.Authenticated,
                                onLanguageChange = viewModel::setLanguage,
                                onThemeChange = viewModel::setThemeMode,
                                hasNotificationPermission = notificationScheduler::hasPermission,
                                requestNotificationPermission = {
                                    notificationPreferences.setPermissionPrompted()
                                    val granted = notificationScheduler.requestPermission()
                                    if (granted) {
                                        notificationSyncService.sync(triggeredByMutation = false)
                                    }
                                    granted
                                },
                                onAnalyticsEnabledChange = mobileAnalyticsController::setUserEnabled,
                                onShareApp = shareHandler::shareApp,
                                onSignOut = {
                                    authRepository.signOut()
                                    navigation.showTabs()
                                },
                                onDeleteAccount = {
                                    authRepository.deleteAccount()
                                    navigation.showTabs()
                                },
                                onBack = navigation::showTabs,
                            )
                        }

                        AppDestination.Tabs -> {
                            Scaffold(
                                topBar = {
                                    val uriHandler = LocalUriHandler.current
                                    TopAppBar(
                                        title = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.logo),
                                                    contentDescription = "gallr logo",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = MaterialTheme.colorScheme.onBackground,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "gallr",
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                            }
                                        },
                                        actions = {
                                            var connectExpanded by remember { mutableStateOf(false) }
                                            ConnectMenu(
                                                expanded = connectExpanded,
                                                onToggle = { connectExpanded = !connectExpanded },
                                                onDismiss = { connectExpanded = false },
                                                lang = lang,
                                                uriHandler = uriHandler,
                                                shareHandler = shareHandler,
                                            )
                                            if (navigation.selectedTab == PROFILE_TAB_INDEX) {
                                                IconButton(
                                                    onClick = {
                                                        connectExpanded = false
                                                        navigation.showSettings()
                                                    },
                                                ) {
                                                    Image(
                                                        painter = painterResource(Res.drawable.ic_settings),
                                                        contentDescription =
                                                            if (lang == AppLanguage.KO) "설정" else "Settings",
                                                        modifier = Modifier.size(20.dp),
                                                        colorFilter =
                                                            ColorFilter.tint(
                                                                MaterialTheme.colorScheme.onBackground,
                                                            ),
                                                    )
                                                }
                                            }
                                        },
                                        colors =
                                            TopAppBarDefaults.topAppBarColors(
                                                containerColor = MaterialTheme.colorScheme.background,
                                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                                            ),
                                    )
                                },
                                bottomBar = {
                                    GallrNavigationBar(
                                        selectedTab = navigation.selectedTab,
                                        onTabSelected = navigation::selectTab,
                                        lang = lang,
                                    )
                                },
                            ) { innerPadding ->
                                // ── Tab content with fade transition ──
                                AnimatedContent(
                                    targetState = navigation.selectedTab,
                                    transitionSpec = {
                                        fadeIn(
                                            animationSpec =
                                                androidx.compose.animation.core
                                                    .tween(150),
                                        ) togetherWith
                                            fadeOut(
                                                animationSpec =
                                                    androidx.compose.animation.core
                                                        .tween(150),
                                            )
                                    },
                                    label = "tabTransition",
                                ) { tab ->
                                    when (tab) {
                                        0 -> {
                                            FeaturedScreen(
                                                viewModel = viewModel,
                                                onExhibitionTap = { exhibition ->
                                                    val index =
                                                        (viewModel.featuredState.value as? ExhibitionListState.Success)
                                                            ?.exhibitions
                                                            ?.indexOfFirst { it.id == exhibition.id }
                                                    openExhibition(
                                                        exhibition,
                                                        DiscoveryAttribution(
                                                            surface = AnalyticsSurface.FEATURED,
                                                            kind = DiscoveryKind.FEATURED,
                                                            position = positionBucket(index),
                                                        ),
                                                        AnalyticsEntryPoint.CARD,
                                                    )
                                                },
                                                onBookmarkToggle = { exhibition ->
                                                    toggleBookmark(exhibition.id, AnalyticsSurface.FEATURED)
                                                },
                                                onExhibitionImpressions = { exposures ->
                                                    appCoroutineScope.launch {
                                                        exposures.forEach { exposure ->
                                                            mobileAnalyticsTracker.exhibitionImpression(
                                                                exposure.exhibitionId,
                                                                DiscoveryAttribution(
                                                                    AnalyticsSurface.FEATURED,
                                                                    DiscoveryKind.FEATURED,
                                                                    exposure.position,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                },
                                                onEventTap = navigation::showEvent,
                                                onRecommendationsTap = navigation::showRecommendations,
                                                modifier = Modifier.padding(innerPadding),
                                            )
                                        }

                                        1 -> {
                                            ListScreen(
                                                viewModel = viewModel,
                                                onExhibitionTap = { exhibition ->
                                                    val listState =
                                                        viewModel.filteredExhibitions.value as?
                                                            ExhibitionListState.Success
                                                    val index =
                                                        listState
                                                            ?.exhibitions
                                                            ?.indexOfFirst { it.id == exhibition.id }
                                                    val discoveryKind =
                                                        when {
                                                            viewModel.showMyListOnly.value -> {
                                                                DiscoveryKind.SAVED
                                                            }

                                                            viewModel.searchQuery.value.isNotBlank() -> {
                                                                DiscoveryKind.SEARCH
                                                            }

                                                            viewModel.filterState.value.selectedEventId != null -> {
                                                                DiscoveryKind.EVENT
                                                            }

                                                            else -> {
                                                                DiscoveryKind.ORGANIC
                                                            }
                                                        }
                                                    openExhibition(
                                                        exhibition,
                                                        DiscoveryAttribution(
                                                            surface = AnalyticsSurface.LIST,
                                                            kind = discoveryKind,
                                                            position = positionBucket(index),
                                                        ),
                                                        AnalyticsEntryPoint.CARD,
                                                    )
                                                },
                                                onPromotedExhibitionTap = { exhibition ->
                                                    navigation.showExhibition(
                                                        exhibition,
                                                        analyticsSuppressed = true,
                                                    )
                                                },
                                                onBookmarkToggle = { exhibition ->
                                                    toggleBookmark(exhibition.id, AnalyticsSurface.LIST)
                                                },
                                                onExhibitionImpressions = { exposures ->
                                                    val discoveryKind =
                                                        when {
                                                            viewModel.showMyListOnly.value -> {
                                                                DiscoveryKind.SAVED
                                                            }

                                                            viewModel.searchQuery.value.isNotBlank() -> {
                                                                DiscoveryKind.SEARCH
                                                            }

                                                            viewModel.filterState.value.selectedEventId != null -> {
                                                                DiscoveryKind.EVENT
                                                            }

                                                            else -> {
                                                                DiscoveryKind.ORGANIC
                                                            }
                                                        }
                                                    appCoroutineScope.launch {
                                                        exposures.forEach { exposure ->
                                                            mobileAnalyticsTracker.exhibitionImpression(
                                                                exposure.exhibitionId,
                                                                DiscoveryAttribution(
                                                                    AnalyticsSurface.LIST,
                                                                    discoveryKind,
                                                                    exposure.position,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                },
                                                onEventTap = navigation::showEvent,
                                                onEditorsChipTap = navigation::showEditorSelector,
                                                visitedExhibitionIds =
                                                    visits.mapTo(mutableSetOf()) { it.exhibitionId },
                                                followedGalleryKeys =
                                                    followedGalleries.mapTo(mutableSetOf()) { it.galleryKey },
                                                followedGalleryIds =
                                                    followedGalleries.mapNotNullTo(mutableSetOf()) { it.galleryId },
                                                onGalleryTap = { candidate ->
                                                    candidate.exhibitions.firstOrNull()?.let { representative ->
                                                        recordIntent(
                                                            representative.id,
                                                            AnalyticsSurface.LIST,
                                                            AnalyticsIntentAction.GALLERY_OPEN,
                                                        )
                                                        navigation.showGallery(representative)
                                                    }
                                                },
                                                onFollowGallery = { candidate ->
                                                    appCoroutineScope.launch {
                                                        val followedAt = Clock.System.now()
                                                        runCatching {
                                                            syncedFollowedGalleryRepository.followGallery(
                                                                FollowedGallery(
                                                                    galleryKey = candidate.galleryKey,
                                                                    galleryId = candidate.galleryId,
                                                                    snapshot = candidate.snapshot,
                                                                    knownExhibitionIds =
                                                                        candidate.exhibitions
                                                                            .mapTo(mutableSetOf()) { it.id },
                                                                    followedAt = followedAt,
                                                                ),
                                                            )
                                                        }.onSuccess {
                                                            candidate.exhibitions.firstOrNull()?.let { representative ->
                                                                recordIntent(
                                                                    representative.id,
                                                                    AnalyticsSurface.LIST,
                                                                    AnalyticsIntentAction.FOLLOW_GALLERY,
                                                                )
                                                            }
                                                        }.onFailure { error ->
                                                            appLog.warn("follow_gallery_from_search", error)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.padding(innerPadding),
                                            )
                                        }

                                        2 -> {
                                            MapScreen(
                                                mapViewModel = personalMapViewModel,
                                                onExhibitionTap = { exhibition ->
                                                    openExhibition(
                                                        exhibition,
                                                        DiscoveryAttribution(
                                                            AnalyticsSurface.MAP,
                                                            DiscoveryKind.NEARBY,
                                                            PositionBucket.UNRANKED,
                                                        ),
                                                        AnalyticsEntryPoint.CARD,
                                                        impressionOnOpen = true,
                                                    )
                                                },
                                                onBuildRoute = { origin -> navigation.showRoute(origin) },
                                                modifier = Modifier.padding(innerPadding),
                                            )
                                        }

                                        3 -> {
                                            com.gallr.app.ui.profile.ProfileTab(
                                                authState = authState,
                                                authRepository = authRepository,
                                                profileRepository = profileRepository,
                                                thoughtRepository = thoughtRepository,
                                                visitRepository = syncedVisitRepository,
                                                followedGalleryRepository = syncedFollowedGalleryRepository,
                                                myGallrSyncStatus = myGallrSyncStatus,
                                                onRetryMyGallrSync = {
                                                    appCoroutineScope.launch { myGallrSyncCoordinator.refresh() }
                                                },
                                                accountNudgeRepository = accountNudgeRepository,
                                                tabsViewModel = viewModel,
                                                lang = lang,
                                                onExhibitionTap = { exhibition ->
                                                    openExhibition(
                                                        exhibition,
                                                        DiscoveryAttribution(
                                                            AnalyticsSurface.MY_GALLR,
                                                            DiscoveryKind.SAVED,
                                                            PositionBucket.UNRANKED,
                                                        ),
                                                        AnalyticsEntryPoint.CARD,
                                                    )
                                                },
                                                onGalleryTap = { exhibition ->
                                                    recordIntent(
                                                        exhibition.id,
                                                        AnalyticsSurface.MY_GALLR,
                                                        AnalyticsIntentAction.GALLERY_OPEN,
                                                    )
                                                    navigation.showGallery(exhibition)
                                                },
                                                addPastVisitsRequest = navigation.addPastVisitsRequest,
                                                modifier = Modifier.padding(innerPadding),
                                            )
                                        }
                                    }
                                }
                            }
                        } // AppDestination.Tabs
                    } // when
                }

                // Fullscreen crop overlay — zIndex above Scaffold bars (which use zIndex 1.0f)
                val cropBitmap = cropOverlayState.imageBitmap
                if (cropBitmap != null) {
                    Box(modifier = Modifier.fillMaxSize().zIndex(2f)) {
                        CropScreen(
                            imageBitmap = cropBitmap,
                            lang = lang,
                            onConfirm = { offset, size ->
                                cropOverlayState.onConfirm?.invoke(offset, size)
                            },
                            onCancel = {
                                cropOverlayState.onCancel?.invoke()
                            },
                        )
                    }
                }

                if (showSignUpNudge) {
                    SignUpNudgeSheet(
                        lang = lang,
                        onSignIn = {
                            // Don't burn the one-time flag here: the user only intends
                            // to sign in. If auth succeeds, the combine() suppresses the
                            // nudge via AuthState; if they bail, they can be nudged again.
                            viewModel.hideSignUpNudge()
                            navigation.selectTab(PROFILE_TAB_INDEX)
                        },
                        onDismiss = { viewModel.dismissSignUpNudge() },
                    )
                }

                SplashOverlay(controller = splashController)
            } // Box
        } // CompositionLocalProvider
    }
}
