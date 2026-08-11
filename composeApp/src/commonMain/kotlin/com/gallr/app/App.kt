package com.gallr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.ui.components.GallrNavigationBar
import com.gallr.app.ui.detail.ExhibitionDetailScreen
import com.gallr.app.ui.editor.EditorDetailScreen
import com.gallr.app.ui.editor.EditorSelectorScreen
import com.gallr.app.ui.event.EventDetailScreen
import com.gallr.app.ui.settings.SettingsScreen
import com.gallr.app.ui.tabs.featured.FeaturedScreen
import com.gallr.app.ui.tabs.list.ListScreen
import com.gallr.app.ui.tabs.map.MapScreen
import com.gallr.app.ui.theme.GallrTheme
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.app.viewmodel.EditorSelectorViewModel
import com.gallr.app.viewmodel.EventDetailViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.EditorRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.PromotionRepository
import com.gallr.shared.repository.SyncBookmarkRepository
import com.gallr.shared.repository.ThemeRepository
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.app.splash.SplashController
import com.gallr.app.splash.SplashOverlay
import com.gallr.app.notifications.NotificationPermissionHandler
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.repository.NotificationPreferences
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.zIndex
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gallr.app.ui.profile.CropOverlayState
import com.gallr.app.ui.profile.CropScreen
import com.gallr.app.ui.profile.LocalCropOverlay
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_settings
import gallr.composeapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val MY_LIST_TAB_INDEX = 1
private const val PROFILE_TAB_INDEX = 3
private const val CATALOG_REFRESH_CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1_000L

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
    languageRepository: LanguageRepository,
    themeRepository: ThemeRepository,
    promotionRepository: PromotionRepository,
    supabaseClient: SupabaseClient,
    splashController: SplashController,
    notificationScheduler: NotificationScheduler,
    notificationSyncService: NotificationSyncService,
    notificationPreferences: NotificationPreferences,
) {
    // Auth state drives SyncBookmarkRepository delegation
    val authState by authRepository.observeAuthState()
        .collectAsState(initial = AuthState.Loading)

    val authStateFlow = remember {
        kotlinx.coroutines.flow.MutableStateFlow<AuthState>(AuthState.Loading)
    }
    val syncBookmarkRepository = remember {
        SyncBookmarkRepository(localBookmarkRepository, cloudBookmarkRepository, authStateFlow)
    }

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
            try {
                syncBookmarkRepository.migrateLocalToCloud()
            } catch (e: Exception) {
                println("WARN [App] Bookmark cloud sync failed: ${e.message}")
            }
            // Check admin status
            try {
                val userId = (authState as AuthState.Authenticated).user.id
                val profile = profileRepository.getProfile(userId)
                isAdmin = profile?.isAdmin == true
            } catch (_: Exception) {
                isAdmin = false
            }
        } else {
            isAdmin = false
        }
    }

    val viewModel: TabsViewModel = viewModel(
        factory = TabsViewModel.factory(
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

    val currentThemeMode by viewModel.themeMode.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshIfStale()
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
            .first { it !is com.gallr.app.viewmodel.ExhibitionListState.Loading }
        splashController.markDataReady()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.featuredState
            .first { it is com.gallr.app.viewmodel.ExhibitionListState.Success }
        notificationSyncService.sync(triggeredByMutation = false)
    }

    // null until DataStore yields its first value — prevents the rationale
    // dialog from flashing in the brief window before the saved flag arrives.
    val permissionPromptedState = notificationPreferences.observePermissionPrompted()
        .collectAsState(initial = null)
    val permissionPrompted = permissionPromptedState.value
    val notificationCoroutineScope = rememberCoroutineScope()

    GallrTheme(themeMode = currentThemeMode) {
        val lang by viewModel.language.collectAsState()

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
        var selectedTab by remember { mutableIntStateOf(0) }
        var selectedExhibition by remember { mutableStateOf<Exhibition?>(null) }
        var selectedEventId by remember { mutableStateOf<String?>(null) }
        var editorSelectorOpen by remember { mutableStateOf(false) }
        var selectedEditorId by remember { mutableStateOf<String?>(null) }
        var settingsOpen by remember { mutableStateOf(false) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            notificationScheduler.pendingDeepLink.collect { link ->
                when (link) {
                    is DeepLink.Exhibition -> {
                        val all = (viewModel.featuredState.value as? com.gallr.app.viewmodel.ExhibitionListState.Success)?.exhibitions
                            ?: emptyList()
                        val target = all.firstOrNull { it.id == link.id }
                        if (target != null) {
                            selectedExhibition = target
                        } else {
                            selectedTab = MY_LIST_TAB_INDEX
                        }
                        notificationScheduler.consumePendingDeepLink()
                    }
                    is DeepLink.MyList -> {
                        selectedTab = MY_LIST_TAB_INDEX
                        notificationScheduler.consumePendingDeepLink()
                    }
                    null -> Unit
                }
            }
        }

        val cropOverlayState = remember { CropOverlayState() }
        val shareHandler = remember { createShareHandler() }

        CompositionLocalProvider(LocalCropOverlay provides cropOverlayState) {
        Box(modifier = Modifier.fillMaxSize()) {
        // ── Detail screen with back handler ──────────────────────────────
        AnimatedContent(
            targetState = listOf(selectedExhibition, selectedEventId, selectedEditorId, editorSelectorOpen, settingsOpen),
            transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) },
            label = "detailTransition",
        ) { state ->
            val exhibition = state[0] as Exhibition?
            val eventId = state[1] as String?
            val editorId = state[2] as String?
            val selectorOpen = state[3] as Boolean
            val settings = state[4] as Boolean

            when {
                settings -> {
                    SettingsScreen(
                        lang = lang,
                        themeMode = currentThemeMode,
                        isAuthenticated = authState is AuthState.Authenticated,
                        onLanguageChange = { viewModel.setLanguage(it) },
                        onThemeChange = { viewModel.setThemeMode(it) },
                        hasNotificationPermission = { notificationScheduler.hasPermission() },
                        requestNotificationPermission = {
                            notificationPreferences.setPermissionPrompted()
                            val granted = notificationScheduler.requestPermission()
                            if (granted) notificationSyncService.sync(triggeredByMutation = false)
                            granted
                        },
                        onShareApp = { shareHandler.shareApp() },
                        onSignOut = {
                            authRepository.signOut()
                            settingsOpen = false
                        },
                        onDeleteAccount = {
                            authRepository.deleteAccount()
                            settingsOpen = false
                        },
                        onBack = { settingsOpen = false },
                    )
                }
                exhibition != null -> {
                    PlatformBackHandler { selectedExhibition = null }
                    ExhibitionDetailScreen(
                        exhibition = exhibition,
                        lang = lang,
                        isBookmarked = exhibition.id in bookmarkedIds,
                        onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                        onShare = { shareHandler.shareExhibition(exhibition, lang) },
                        onBack = { selectedExhibition = null },
                        thoughtRepository = thoughtRepository,
                        authState = authState,
                        isAdmin = isAdmin,
                        supabaseClient = supabaseClient,
                    )
                }
                eventId != null -> {
                    PlatformBackHandler { selectedEventId = null }
                    val eventDetailVm: EventDetailViewModel = viewModel(
                        key = "event-$eventId",
                        factory = EventDetailViewModel.factory(eventId, eventRepository),
                    )
                    EventDetailScreen(
                        viewModel = eventDetailVm,
                        lang = lang,
                        bookmarkedIds = bookmarkedIds,
                        onToggleBookmark = { viewModel.toggleBookmark(it) },
                        onBack = { selectedEventId = null },
                        onExhibitionTap = { selectedExhibition = it },
                    )
                }
                editorId != null -> {
                    PlatformBackHandler { selectedEditorId = null }
                    val editorDetailVm: EditorDetailViewModel = viewModel(
                        key = "editor-$editorId",
                        factory = EditorDetailViewModel.factory(
                            editorId = editorId,
                            editorRepository = editorRepository,
                            tabsViewModel = viewModel,
                        ),
                    )
                    EditorDetailScreen(
                        viewModel = editorDetailVm,
                        bookmarkedIds = bookmarkedIds,
                        onToggleBookmark = { viewModel.toggleBookmark(it) },
                        onBack = { selectedEditorId = null },
                        onExhibitionTap = { selectedExhibition = it },
                    )
                }
                selectorOpen -> {
                    PlatformBackHandler { editorSelectorOpen = false }
                    val selectorVm: EditorSelectorViewModel = viewModel(
                        key = "editor-selector",
                        factory = EditorSelectorViewModel.factory(
                            editorRepository = editorRepository,
                            tabsViewModel = viewModel,
                        ),
                    )
                    EditorSelectorScreen(
                        viewModel = selectorVm,
                        onBack = { editorSelectorOpen = false },
                        onEditorTap = { selectedEditorId = it },
                    )
                }
                else -> {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(Res.drawable.logo),
                                        contentDescription = "gallr logo",
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "gallr",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            },
                            actions = {
                                if (selectedTab == PROFILE_TAB_INDEX) {
                                    IconButton(onClick = { settingsOpen = true }) {
                                        Image(
                                            painter = painterResource(Res.drawable.ic_settings),
                                            contentDescription = if (lang == AppLanguage.KO) "설정" else "Settings",
                                            modifier = Modifier.size(20.dp),
                                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        )
                    },
                    bottomBar = {
                        GallrNavigationBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            lang = lang,
                        )
                    },
                ) { innerPadding ->
                    // ── Tab content with fade transition ──
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(150)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) },
                        label = "tabTransition",
                    ) { tab ->
                        when (tab) {
                            0 -> FeaturedScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { id -> selectedEventId = id },
                                modifier = Modifier.padding(innerPadding),
                            )
                            1 -> ListScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { id -> selectedEventId = id },
                                onEditorsChipTap = { editorSelectorOpen = true },
                                modifier = Modifier.padding(innerPadding),
                            )
                            2 -> MapScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { id -> selectedEventId = id },
                                modifier = Modifier.padding(innerPadding),
                            )
                            3 -> com.gallr.app.ui.profile.ProfileTab(
                                authState = authState,
                                authRepository = authRepository,
                                profileRepository = profileRepository,
                                thoughtRepository = thoughtRepository,
                                supabaseClient = supabaseClient,
                                viewModel = viewModel,
                                lang = lang,
                                onExhibitionTap = { selectedExhibition = it },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                    }
                }
                } // else ->
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
                    selectedTab = PROFILE_TAB_INDEX
                },
                onDismiss = { viewModel.dismissSignUpNudge() },
            )
        }

        SplashOverlay(controller = splashController)
        } // Box
        } // CompositionLocalProvider
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignUpNudgeSheet(
    lang: AppLanguage,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "전시 5개를 저장했어요."
                    AppLanguage.EN -> "You've saved 5 exhibitions."
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "재설치하면 목록이 사라질 수 있어요. 로그인하면 안전하게 보관됩니다."
                    AppLanguage.EN -> "Sign in so your list doesn't disappear if you reinstall."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onSignIn,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "gallr 로그인"
                        AppLanguage.EN -> "Sign in to gallr"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = onDismiss,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "나중에"
                        AppLanguage.EN -> "Not now"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
