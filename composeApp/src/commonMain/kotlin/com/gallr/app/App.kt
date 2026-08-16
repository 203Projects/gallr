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
import com.gallr.app.notifications.NotificationPermissionHandler
import com.gallr.app.notifications.RemotePushAddressProvider
import com.gallr.app.splash.SplashController
import com.gallr.app.splash.SplashOverlay
import com.gallr.app.ui.components.GallrNavigationBar
import com.gallr.app.ui.detail.ExhibitionDetailScreen
import com.gallr.app.ui.editor.EditorDetailScreen
import com.gallr.app.ui.editor.EditorSelectorScreen
import com.gallr.app.ui.event.EventDetailScreen
import com.gallr.app.ui.gallery.GalleryDetailScreen
import com.gallr.app.ui.profile.CropOverlayState
import com.gallr.app.ui.profile.CropScreen
import com.gallr.app.ui.profile.LocalCropOverlay
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
import com.gallr.app.viewmodel.PersonalMapViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.visitFromExhibition
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.network.MyGallrAccountCommandSource
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

    val currentThemeMode by viewModel.themeMode.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val appCoroutineScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshIfStale()
                    appCoroutineScope.launch { myGallrSyncCoordinator.refresh() }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                            navigation.showExhibition(target)
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
                            var isVisitSaving by remember(exhibition.id) { mutableStateOf(false) }
                            var visitSaveFailed by remember(exhibition.id) { mutableStateOf(false) }
                            PlatformBackHandler(navigation::showTabs)
                            ExhibitionDetailScreen(
                                exhibition = exhibition,
                                lang = lang,
                                isBookmarked = exhibition.id in bookmarkedIds,
                                onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                                onShare = { shareHandler.shareExhibition(exhibition, lang) },
                                onGalleryTap = { navigation.showGallery(exhibition) },
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
                                            }.onFailure { error ->
                                                appLog.warn("mark_exhibition_visited", error)
                                                visitSaveFailed = true
                                            }
                                            isVisitSaving = false
                                        }
                                    }
                                },
                                onBack = navigation::showTabs,
                                thoughtRepository = thoughtRepository,
                                authState = authState,
                                isAdmin = isAdmin,
                            )
                        }

                        is AppDestination.GalleryDetail -> {
                            val exhibition = destination.exhibition
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
                                onExhibitionTap = navigation::showExhibition,
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
                                onToggleBookmark = { viewModel.toggleBookmark(it) },
                                onBack = navigation::showTabs,
                                onExhibitionTap = navigation::showExhibition,
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
                                onToggleBookmark = { viewModel.toggleBookmark(it) },
                                onBack = navigation::showTabs,
                                onExhibitionTap = navigation::showExhibition,
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
                                                onExhibitionTap = navigation::showExhibition,
                                                onEventTap = navigation::showEvent,
                                                modifier = Modifier.padding(innerPadding),
                                            )
                                        }

                                        1 -> {
                                            ListScreen(
                                                viewModel = viewModel,
                                                onExhibitionTap = navigation::showExhibition,
                                                onEventTap = navigation::showEvent,
                                                onEditorsChipTap = navigation::showEditorSelector,
                                                visitedExhibitionIds =
                                                    visits.mapTo(mutableSetOf()) { it.exhibitionId },
                                                followedGalleryKeys =
                                                    followedGalleries.mapTo(mutableSetOf()) { it.galleryKey },
                                                followedGalleryIds =
                                                    followedGalleries.mapNotNullTo(mutableSetOf()) { it.galleryId },
                                                onGalleryTap = { candidate ->
                                                    candidate.exhibitions.firstOrNull()?.let(navigation::showGallery)
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
                                                onExhibitionTap = navigation::showExhibition,
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
                                                onExhibitionTap = navigation::showExhibition,
                                                onGalleryTap = navigation::showGallery,
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
