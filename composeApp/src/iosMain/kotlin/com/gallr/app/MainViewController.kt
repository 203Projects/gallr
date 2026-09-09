package com.gallr.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import com.gallr.app.notifications.IosRemotePushAddressProvider
import com.gallr.app.splash.SplashController
import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.MobileAnalyticsController
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.analytics.PersistentAnalyticsRecorder
import com.gallr.shared.analytics.parseAppMajorVersion
import com.gallr.shared.data.network.AccountDeletionApiClient
import com.gallr.shared.data.network.EditorApiClient
import com.gallr.shared.data.network.EventApiClient
import com.gallr.shared.data.network.ExhibitionApiClient
import com.gallr.shared.data.network.ExhibitionCatalogSource
import com.gallr.shared.data.network.GalleryAlertApiClient
import com.gallr.shared.data.network.MyGallrAccountApiClient
import com.gallr.shared.data.network.PromotionApiClient
import com.gallr.shared.data.network.createGallrNetworkClients
import com.gallr.shared.data.network.createMobileAnalyticsApiClient
import com.gallr.shared.data.network.handleAuthDeeplink
import com.gallr.shared.notifications.IosNotificationScheduler
import com.gallr.shared.notifications.NotificationDelegate
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.platform.createAnalyticsQueueDataStore
import com.gallr.shared.platform.createDataStore
import com.gallr.shared.platform.createExhibitionCacheDataStore
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
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController
import platform.UserNotifications.UNUserNotificationCenter

// Module-level reference for deeplink handling from Swift
private var retainedSupabaseClient: SupabaseClient? = null
private val scope = MainScope()

// Strong reference to the notification delegate to prevent it from being
// garbage-collected while UNUserNotificationCenter holds a weak reference.
private var retainedNotificationDelegate: NotificationDelegate? = null
private var retainedRemotePushAddressProvider: IosRemotePushAddressProvider? = null

@Suppress("FunctionName", "unused")
fun handleRemotePushToken(
    token: String,
    environment: String,
) {
    retainedRemotePushAddressProvider?.acceptToken(token, environment)
}

@Suppress("FunctionName", "unused")
fun handleRemotePushRegistrationFailure() {
    retainedRemotePushAddressProvider?.registrationFailed()
}

@Suppress("FunctionName", "unused")
fun handleDeeplinkUrl(url: String) {
    val client = retainedSupabaseClient ?: return
    scope.launch { handleAuthDeeplink(client, url) }
}

@Suppress("FunctionName", "unused") // Called from Swift ContentView.swift
fun MainViewController(
    supabaseUrl: String,
    supabaseApiKey: String,
) = createMainViewController(
    supabaseUrl = supabaseUrl,
    supabaseApiKey = supabaseApiKey,
    exhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
    promotionEnabled = false,
    mobileAnalyticsReleaseEnabled = false,
)

@Suppress("FunctionName", "unused") // Called from Swift ContentView.swift
fun MainViewControllerWithCatalogSource(
    supabaseUrl: String,
    supabaseApiKey: String,
    exhibitionCatalogSource: String,
) = createMainViewController(
    supabaseUrl = supabaseUrl,
    supabaseApiKey = supabaseApiKey,
    exhibitionCatalogSource = ExhibitionCatalogSource.fromConfig(exhibitionCatalogSource),
    promotionEnabled = false,
    mobileAnalyticsReleaseEnabled = false,
)

@Suppress("FunctionName", "unused") // Called from Swift ContentView.swift
fun MainViewControllerWithCatalogSourceAndPromotion(
    supabaseUrl: String,
    supabaseApiKey: String,
    exhibitionCatalogSource: String,
    promotionEnabled: Boolean,
    mobileAnalyticsReleaseEnabled: Boolean,
) = createMainViewController(
    supabaseUrl = supabaseUrl,
    supabaseApiKey = supabaseApiKey,
    exhibitionCatalogSource = ExhibitionCatalogSource.fromConfig(exhibitionCatalogSource),
    promotionEnabled = promotionEnabled,
    mobileAnalyticsReleaseEnabled = mobileAnalyticsReleaseEnabled,
)

private fun createMainViewController(
    supabaseUrl: String,
    supabaseApiKey: String,
    exhibitionCatalogSource: ExhibitionCatalogSource,
    promotionEnabled: Boolean,
    mobileAnalyticsReleaseEnabled: Boolean,
): UIViewController {
    val dataStore = createDataStore()
    val exhibitionCacheDataStore = createExhibitionCacheDataStore()
    val analyticsQueueDataStore = createAnalyticsQueueDataStore()
    val networkClients =
        createGallrNetworkClients(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseApiKey,
        )
    val supabaseClient = networkClients.supabaseClient
    retainedSupabaseClient = supabaseClient
    val restClient = networkClients.restClient
    val analyticsAppMajor =
        parseAppMajorVersion(
            NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "",
        )
    val mobileAnalyticsController =
        MobileAnalyticsController(
            delegate =
                PersistentAnalyticsRecorder(
                    queue = DataStoreAnalyticsQueue(analyticsQueueDataStore),
                    sink = createMobileAnalyticsApiClient(networkClients, supabaseUrl),
                ),
            preferences = DataStoreAnalyticsPreferenceRepository(dataStore),
            releaseEnabled = mobileAnalyticsReleaseEnabled && analyticsAppMajor != null,
        )
    val mobileAnalyticsEventFactory =
        analyticsAppMajor?.let { appMajor ->
            MobileAnalyticsEventFactory(
                platform = AnalyticsPlatform.IOS,
                appMajor = appMajor,
            )
        }
    val exhibitionRepository =
        CachedExhibitionRepository(
            remote =
                ExhibitionRepositoryImpl(
                    ExhibitionApiClient(
                        client = restClient,
                        supabaseUrl = supabaseUrl,
                        catalogSource = exhibitionCatalogSource,
                    ),
                ),
            cache = DataStoreExhibitionCache(exhibitionCacheDataStore, exhibitionCatalogSource),
        )
    val eventRepository =
        EventRepositoryImpl(
            EventApiClient(
                client = restClient,
                supabaseUrl = supabaseUrl,
                exhibitionCatalogSource = exhibitionCatalogSource,
            ),
        )
    val editorRepository =
        EditorRepositoryImpl(
            EditorApiClient(client = restClient, supabaseUrl = supabaseUrl),
        )
    val localBookmarkRepository = BookmarkRepositoryImpl(dataStore)
    val visitRepository = DataStoreVisitRepository(dataStore)
    val followedGalleryRepository = DataStoreFollowedGalleryRepository(dataStore)
    val accountNudgeRepository = DataStoreMyGallrAccountNudgeRepository(dataStore)
    val myGallrAccountStore = DataStoreMyGallrAccountStore(dataStore)
    val myGallrAccountSource =
        MyGallrAccountApiClient(
            client = restClient,
            supabaseUrl = supabaseUrl,
            accessTokenProvider = { supabaseClient.auth.currentAccessTokenOrNull() },
        )
    val remotePushAddressProvider = IosRemotePushAddressProvider()
    retainedRemotePushAddressProvider = remotePushAddressProvider
    val galleryAlertRegistrationRepository =
        GalleryAlertRegistrationRepositoryImpl(
            source =
                GalleryAlertApiClient(
                    client = restClient,
                    supabaseUrl = supabaseUrl,
                    accessTokenProvider = { supabaseClient.auth.currentAccessTokenOrNull() },
                ),
            stateStore = DataStoreGalleryAlertInstallationStateStore(dataStore),
        )
    val cloudBookmarkRepository = CloudBookmarkRepository(supabaseClient)
    val authRepository =
        AuthRepositoryImpl(
            supabaseClient = supabaseClient,
            accountDeletionSource =
                AccountDeletionApiClient(
                    client = restClient,
                    supabaseUrl = supabaseUrl,
                ),
        )
    val profileRepository = ProfileRepositoryImpl(supabaseClient)
    val thoughtRepository = ThoughtRepositoryImpl(supabaseClient)
    val languageRepository = LanguageRepositoryImpl(dataStore)
    val themeRepository = ThemeRepositoryImpl(dataStore)
    val promotionRepository =
        createPromotionRepository(
            enabled = promotionEnabled,
            source = { PromotionApiClient(client = restClient, supabaseUrl = supabaseUrl) },
            keyStore = { DataStorePromotionInstallationKeyStore(dataStore) },
        )
    val splashController = SplashController(scope = MainScope()).also { it.start() }

    val notificationScheduler = IosNotificationScheduler()
    val notificationDelegate = NotificationDelegate(notificationScheduler)
    retainedNotificationDelegate = notificationDelegate
    UNUserNotificationCenter.currentNotificationCenter().setDelegate(notificationDelegate)
    val notificationSyncService =
        NotificationSyncService(
            scheduler = notificationScheduler,
            exhibitionRepo = exhibitionRepository,
            bookmarkRepo = localBookmarkRepository,
            languageRepo = languageRepository,
        )
    val notificationPreferences = NotificationPreferences(dataStore)

    return ComposeUIViewController {
        DisposableEffect(networkClients) {
            onDispose {
                if (retainedSupabaseClient === supabaseClient) retainedSupabaseClient = null
                scope.launch { networkClients.close() }
            }
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
            externalMapLauncher = IosExternalMapLauncher(),
            mobileAnalyticsController = mobileAnalyticsController,
            mobileAnalyticsEventFactory = mobileAnalyticsEventFactory,
        )
    }
}
