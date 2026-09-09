package com.gallr.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.gallr.app.notifications.ActivityNotificationPermissionRequester
import com.gallr.app.notifications.AndroidNotificationScheduler
import com.gallr.app.splash.SplashController
import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.MobileAnalyticsController
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.analytics.PersistentAnalyticsRecorder
import com.gallr.shared.analytics.parseAppMajorVersion
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.network.AccountDeletionApiClient
import com.gallr.shared.data.network.EditorApiClient
import com.gallr.shared.data.network.EventApiClient
import com.gallr.shared.data.network.ExhibitionApiClient
import com.gallr.shared.data.network.ExhibitionCatalogSource
import com.gallr.shared.data.network.GalleryAlertApiClient
import com.gallr.shared.data.network.GallrNetworkClients
import com.gallr.shared.data.network.MyGallrAccountApiClient
import com.gallr.shared.data.network.PromotionApiClient
import com.gallr.shared.data.network.createGallrNetworkClients
import com.gallr.shared.data.network.createMobileAnalyticsApiClient
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationConstants
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.notifications.ScheduledIdIndex
import com.gallr.shared.platform.createAnalyticsQueueDataStore
import com.gallr.shared.platform.createDataStore
import com.gallr.shared.platform.createExhibitionCacheDataStore
import com.gallr.shared.platform.initDataStore
import com.gallr.shared.repository.AuthRepositoryImpl
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CachedExhibitionRepository
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.DataStoreAnalyticsPreferenceRepository
import com.gallr.shared.repository.DataStoreAnalyticsQueue
import com.gallr.shared.repository.DataStoreExhibitionCache
import com.gallr.shared.repository.DataStoreFollowedGalleryRepository
import com.gallr.shared.repository.DataStoreGalleryAlertInstallationStateStore
import com.gallr.shared.repository.DataStoreMyGallrAccountNudgeRepository
import com.gallr.shared.repository.DataStoreMyGallrAccountStore
import com.gallr.shared.repository.DataStorePromotionInstallationKeyStore
import com.gallr.shared.repository.DataStoreVisitRepository
import com.gallr.shared.repository.EditorRepository
import com.gallr.shared.repository.EditorRepositoryImpl
import com.gallr.shared.repository.EventRepositoryImpl
import com.gallr.shared.repository.ExhibitionRepositoryImpl
import com.gallr.shared.repository.GalleryAlertRegistrationRepositoryImpl
import com.gallr.shared.repository.LanguageRepositoryImpl
import com.gallr.shared.repository.NotificationPreferences
import com.gallr.shared.repository.ProfileRepositoryImpl
import com.gallr.shared.repository.ThemeRepositoryImpl
import com.gallr.shared.repository.ThoughtRepositoryImpl
import com.gallr.shared.repository.createPromotionRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private lateinit var networkClients: GallrNetworkClients
    private lateinit var notificationScheduler: AndroidNotificationScheduler

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle OAuth deeplink callback
        intent.data?.let { uri ->
            lifecycleScope.launch {
                com.gallr.shared.data.network
                    .handleAuthDeeplink(networkClients.supabaseClient, uri.toString())
            }
        }
        extractDeepLink(intent)?.let { notificationScheduler.setPendingDeepLink(it) }
    }

    private fun extractDeepLink(intent: Intent): DeepLink? {
        val type = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE)
        return when (type) {
            NotificationConstants.DEEPLINK_EXHIBITION -> {
                val id = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID)
                if (id != null) DeepLink.Exhibition(id) else DeepLink.MyList
            }

            NotificationConstants.DEEPLINK_MYLIST -> {
                DeepLink.MyList
            }

            else -> {
                null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize DataStore with application context before any repository uses it.
        initDataStore(applicationContext)
        initShareHandler(applicationContext)

        val dataStore = createDataStore()
        val exhibitionCacheDataStore = createExhibitionCacheDataStore()
        val analyticsQueueDataStore = createAnalyticsQueueDataStore()
        networkClients =
            createGallrNetworkClients(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_PUBLIC_API_KEY,
            )
        val supabaseClient = networkClients.supabaseClient
        val restClient = networkClients.restClient
        val analyticsAppMajor = parseAppMajorVersion(BuildConfig.VERSION_NAME)
        val mobileAnalyticsController =
            MobileAnalyticsController(
                delegate =
                    PersistentAnalyticsRecorder(
                        queue = DataStoreAnalyticsQueue(analyticsQueueDataStore),
                        sink = createMobileAnalyticsApiClient(networkClients, BuildConfig.SUPABASE_URL),
                    ),
                preferences = DataStoreAnalyticsPreferenceRepository(dataStore),
                releaseEnabled = BuildConfig.MOBILE_ANALYTICS_RELEASE_ENABLED && analyticsAppMajor != null,
            )
        val mobileAnalyticsEventFactory =
            analyticsAppMajor?.let { appMajor ->
                MobileAnalyticsEventFactory(
                    platform = AnalyticsPlatform.ANDROID,
                    appMajor = appMajor,
                )
            }
        val exhibitionCatalogSource =
            ExhibitionCatalogSource.fromConfig(
                BuildConfig.EXHIBITION_CATALOG_SOURCE,
            )
        val exhibitionRepository =
            CachedExhibitionRepository(
                remote =
                    ExhibitionRepositoryImpl(
                        ExhibitionApiClient(
                            client = restClient,
                            supabaseUrl = BuildConfig.SUPABASE_URL,
                            catalogSource = exhibitionCatalogSource,
                        ),
                    ),
                cache = DataStoreExhibitionCache(exhibitionCacheDataStore, exhibitionCatalogSource),
            )
        val eventRepository =
            EventRepositoryImpl(
                EventApiClient(
                    client = restClient,
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                    exhibitionCatalogSource = exhibitionCatalogSource,
                ),
            )
        val editorRepository: EditorRepository =
            EditorRepositoryImpl(
                EditorApiClient(
                    client = restClient,
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                ),
            )
        val localBookmarkRepository = BookmarkRepositoryImpl(dataStore)
        val visitRepository = DataStoreVisitRepository(dataStore)
        val followedGalleryRepository = DataStoreFollowedGalleryRepository(dataStore)
        val remotePushAddressProvider =
            AndroidRemotePushAddressProvider(
                context = applicationContext,
                configuration =
                    AndroidFirebaseConfiguration(
                        projectId = BuildConfig.FIREBASE_PROJECT_ID,
                        applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
                        apiKey = BuildConfig.FIREBASE_API_KEY,
                        senderId = BuildConfig.FIREBASE_SENDER_ID,
                    ),
            )
        val galleryAlertRegistrationRepository =
            GalleryAlertRegistrationRepositoryImpl(
                source =
                    GalleryAlertApiClient(
                        client = restClient,
                        supabaseUrl = BuildConfig.SUPABASE_URL,
                        accessTokenProvider = { supabaseClient.auth.currentAccessTokenOrNull() },
                    ),
                stateStore = DataStoreGalleryAlertInstallationStateStore(dataStore),
            )
        val accountNudgeRepository = DataStoreMyGallrAccountNudgeRepository(dataStore)
        val myGallrAccountStore = DataStoreMyGallrAccountStore(dataStore)
        val myGallrAccountSource =
            MyGallrAccountApiClient(
                client = restClient,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                accessTokenProvider = { supabaseClient.auth.currentAccessTokenOrNull() },
            )
        val cloudBookmarkRepository = CloudBookmarkRepository(supabaseClient)
        val authRepository =
            AuthRepositoryImpl(
                supabaseClient = supabaseClient,
                accountDeletionSource =
                    AccountDeletionApiClient(
                        client = restClient,
                        supabaseUrl = BuildConfig.SUPABASE_URL,
                    ),
            )
        val profileRepository = ProfileRepositoryImpl(supabaseClient)
        val thoughtRepository = ThoughtRepositoryImpl(supabaseClient)
        val languageRepository = LanguageRepositoryImpl(dataStore)
        val themeRepository = ThemeRepositoryImpl(dataStore)
        val promotionRepository =
            createPromotionRepository(
                enabled = BuildConfig.PROMOTION_ENABLED,
                source = {
                    PromotionApiClient(
                        client = restClient,
                        supabaseUrl = BuildConfig.SUPABASE_URL,
                    )
                },
                keyStore = { DataStorePromotionInstallationKeyStore(dataStore) },
            )
        val splashController = SplashController(scope = lifecycleScope)
        splash.setKeepOnScreenCondition { !splashController.themeReadyValue() }
        splashController.start()
        if (savedInstanceState != null) splashController.skipSplash()

        val scheduledIdIndex = ScheduledIdIndex(dataStore)
        val permissionRequester = ActivityNotificationPermissionRequester(this)
        notificationScheduler =
            AndroidNotificationScheduler(
                context = applicationContext,
                index = scheduledIdIndex,
                permissionRequester = permissionRequester,
            )
        val notificationSyncService =
            NotificationSyncService(
                scheduler = notificationScheduler,
                exhibitionRepo = exhibitionRepository,
                bookmarkRepo = localBookmarkRepository,
                languageRepo = languageRepository,
            )
        val notificationPreferences = NotificationPreferences(dataStore)
        extractDeepLink(intent)?.let { notificationScheduler.setPendingDeepLink(it) }

        // Handle deeplink from initial launch (cold start from OAuth redirect)
        intent.data?.let { uri ->
            lifecycleScope.launch {
                com.gallr.shared.data.network
                    .handleAuthDeeplink(supabaseClient, uri.toString())
            }
        }

        setContent {
            val currentThemeMode by themeRepository.observeThemeMode().collectAsState(initial = ThemeMode.SYSTEM)
            val isDarkTheme =
                when (currentThemeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                }

            DisposableEffect(isDarkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDarkTheme
                    isAppearanceLightNavigationBars = !isDarkTheme
                }
                onDispose {}
            }

            App(
                exhibitionRepository = exhibitionRepository,
                eventRepository = eventRepository,
                editorRepository = editorRepository,
                localBookmarkRepository = localBookmarkRepository,
                cloudBookmarkRepository = cloudBookmarkRepository,
                authRepository = authRepository,
                profileRepository = profileRepository,
                thoughtRepository = thoughtRepository,
                visitRepository = visitRepository,
                followedGalleryRepository = followedGalleryRepository,
                myGallrAccountStore = myGallrAccountStore,
                myGallrAccountSource = myGallrAccountSource,
                galleryAlertRegistrationRepository = galleryAlertRegistrationRepository,
                remotePushAddressProvider = remotePushAddressProvider,
                accountNudgeRepository = accountNudgeRepository,
                languageRepository = languageRepository,
                themeRepository = themeRepository,
                promotionRepository = promotionRepository,
                splashController = splashController,
                notificationScheduler = notificationScheduler,
                notificationSyncService = notificationSyncService,
                notificationPreferences = notificationPreferences,
                externalMapLauncher = AndroidExternalMapLauncher(applicationContext),
                mobileAnalyticsController = mobileAnalyticsController,
                mobileAnalyticsEventFactory = mobileAnalyticsEventFactory,
            )
        }
    }

    override fun onDestroy() {
        if (::networkClients.isInitialized) {
            runBlocking { networkClients.close() }
        }
        super.onDestroy()
    }
}
