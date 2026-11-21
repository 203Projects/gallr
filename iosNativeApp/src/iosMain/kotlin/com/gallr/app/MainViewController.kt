package com.gallr.app

import com.gallr.app.notifications.IosRemotePushAddressProvider
import com.gallr.app.platform.appPlatform
import com.gallr.app.platform.installedAppVersionName
import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.MobileAnalyticsController
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.analytics.PersistentAnalyticsRecorder
import com.gallr.shared.analytics.parseAppMajorVersion
import com.gallr.shared.app.AppVersion
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.AccountDeletionApiClient
import com.gallr.shared.data.network.AppVersionPolicyApiClient
import com.gallr.shared.data.network.CuratorNoteApiClient
import com.gallr.shared.data.network.EditorApiClient
import com.gallr.shared.data.network.EventApiClient
import com.gallr.shared.data.network.ExhibitionApiClient
import com.gallr.shared.data.network.ExhibitionCatalogSource
import com.gallr.shared.data.network.GalleryAlertApiClient
import com.gallr.shared.data.network.GalleryProfileImageApiClient
import com.gallr.shared.data.network.MostViewedExhibitionApiClient
import com.gallr.shared.data.network.MyGallrAccountApiClient
import com.gallr.shared.data.network.PersonalRouteApiClient
import com.gallr.shared.data.network.PromotionApiClient
import com.gallr.shared.data.network.WalkingRouteApiClient
import com.gallr.shared.data.network.createGallrNetworkClients
import com.gallr.shared.data.network.createMobileAnalyticsApiClient
import com.gallr.shared.notifications.IosNotificationScheduler
import com.gallr.shared.notifications.NotificationDelegate
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.observability.AppLog
import com.gallr.shared.platform.createAnalyticsQueueDataStore
import com.gallr.shared.platform.createDataStore
import com.gallr.shared.platform.createExhibitionCacheDataStore
import com.gallr.shared.repository.AppUpdateRepositoryImpl
import com.gallr.shared.repository.AuthRepositoryImpl
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CachedExhibitionRepository
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.CuratorNoteRepositoryImpl
import com.gallr.shared.repository.DataStoreAnalyticsPreferenceRepository
import com.gallr.shared.repository.DataStoreAnalyticsQueue
import com.gallr.shared.repository.DataStoreDiaryDeviceStateRepository
import com.gallr.shared.repository.DataStoreExhibitionCache
import com.gallr.shared.repository.DataStoreFollowedGalleryRepository
import com.gallr.shared.repository.DataStoreGalleryAlertInstallationStateStore
import com.gallr.shared.repository.DataStoreHiddenThoughtAuthorRepository
import com.gallr.shared.repository.DataStoreMyGallrAccountNudgeRepository
import com.gallr.shared.repository.DataStoreMyGallrAccountStore
import com.gallr.shared.repository.DataStorePersonalRouteDraftRepository
import com.gallr.shared.repository.DataStorePromotionInstallationKeyStore
import com.gallr.shared.repository.DataStoreRouteVisitTimeRepository
import com.gallr.shared.repository.DataStoreTastePreferenceRepository
import com.gallr.shared.repository.DataStoreVisitRepository
import com.gallr.shared.repository.EditorRepositoryImpl
import com.gallr.shared.repository.EventRepositoryImpl
import com.gallr.shared.repository.ExhibitionRepositoryImpl
import com.gallr.shared.repository.GalleryAlertRegistrationRepositoryImpl
import com.gallr.shared.repository.GalleryProfileImageRepositoryImpl
import com.gallr.shared.repository.LanguageRepositoryImpl
import com.gallr.shared.repository.MostViewedExhibitionRepositoryImpl
import com.gallr.shared.repository.NotificationPreferences
import com.gallr.shared.repository.PersonalRouteRepositoryImpl
import com.gallr.shared.repository.ProfileRepositoryImpl
import com.gallr.shared.repository.ThemeRepositoryImpl
import com.gallr.shared.repository.ThoughtRepositoryImpl
import com.gallr.shared.repository.WalkingRouteRepositoryImpl
import com.gallr.shared.repository.createPromotionRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UserNotifications.UNUserNotificationCenter

private val cleanupScope = MainScope()
private val assemblyLog = AppLog.tagged("NativeGallrAssembly")

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

internal fun createNativeGallrDependencies(
    supabaseUrl: String,
    supabaseApiKey: String,
    exhibitionCatalogSource: ExhibitionCatalogSource,
    promotionEnabled: Boolean,
    mobileAnalyticsReleaseEnabled: Boolean,
    fixtureCatalogue: List<Exhibition>? = null,
    fixtureSignedIn: Boolean = false,
): NativeGallrDependencies {
    val dataStore = createDataStore()
    val exhibitionCacheDataStore = createExhibitionCacheDataStore()
    val analyticsQueueDataStore = createAnalyticsQueueDataStore()
    val networkClients =
        createGallrNetworkClients(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseApiKey,
        )
    val supabaseClient = networkClients.supabaseClient
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
        fixtureCatalogue?.let(::NativeFixtureExhibitionRepository) ?: CachedExhibitionRepository(
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
    val galleryProfileImageRepository =
        GalleryProfileImageRepositoryImpl(
            GalleryProfileImageApiClient(client = restClient, supabaseUrl = supabaseUrl),
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
    val personalRouteDraftRepository = DataStorePersonalRouteDraftRepository(dataStore)
    val routeVisitTimeRepository = DataStoreRouteVisitTimeRepository(dataStore)
    val tastePreferenceRepository = DataStoreTastePreferenceRepository(dataStore)
    val diaryDeviceStateRepository = DataStoreDiaryDeviceStateRepository(dataStore)
    val hiddenThoughtAuthorRepository = DataStoreHiddenThoughtAuthorRepository(dataStore)
    val appUpdateRepository =
        AppUpdateRepositoryImpl(
            installed = installedAppVersionName()?.let(AppVersion::parse),
            platform = appPlatform,
            api = AppVersionPolicyApiClient(client = restClient, supabaseUrl = supabaseUrl),
            dataStore = dataStore,
        )
    val curatorNoteRepository =
        CuratorNoteRepositoryImpl(CuratorNoteApiClient(client = restClient, supabaseUrl = supabaseUrl))
    val mostViewedExhibitionRepository =
        MostViewedExhibitionRepositoryImpl(
            MostViewedExhibitionApiClient(client = restClient, supabaseUrl = supabaseUrl),
        )

    // Walking paths between route stops come from one shared function and are kept for the session (spec 093).
    val walkingRouteRepository =
        WalkingRouteRepositoryImpl(WalkingRouteApiClient(client = restClient, supabaseUrl = supabaseUrl))
    val personalRouteRepository =
        PersonalRouteRepositoryImpl(
            PersonalRouteApiClient(
                client = restClient,
                supabaseUrl = supabaseUrl,
                accessTokenProvider = { supabaseClient.auth.currentAccessTokenOrNull() },
            ),
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
    val fixtureAccount = if (fixtureSignedIn) NativeFixtureAccount(authRepository, profileRepository) else null
    val thoughtRepository = ThoughtRepositoryImpl(supabaseClient)
    val languageRepository = LanguageRepositoryImpl(dataStore)
    val themeRepository = ThemeRepositoryImpl(dataStore)
    val promotionRepository =
        createPromotionRepository(
            enabled = promotionEnabled,
            source = { PromotionApiClient(client = restClient, supabaseUrl = supabaseUrl) },
            keyStore = { DataStorePromotionInstallationKeyStore(dataStore) },
        )
    val notificationScheduler = IosNotificationScheduler()
    val notificationDelegate = NotificationDelegate(notificationScheduler)
    retainedNotificationDelegate = notificationDelegate
    UNUserNotificationCenter.currentNotificationCenter().setDelegate(notificationDelegate)
    val notificationSyncService =
        NotificationSyncService(
            scheduler = notificationScheduler,
            exhibitionRepo = exhibitionRepository,
            languageRepo = languageRepository,
        )
    val notificationPreferences = NotificationPreferences(dataStore)

    return NativeGallrDependencies(
        exhibitionRepository = exhibitionRepository,
        eventRepository = eventRepository,
        editorRepository = editorRepository,
        galleryProfileImageRepository = galleryProfileImageRepository,
        localBookmarkRepository = localBookmarkRepository,
        cloudBookmarkRepository = cloudBookmarkRepository,
        authRepository = fixtureAccount ?: authRepository,
        profileRepository = fixtureAccount ?: profileRepository,
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
        notificationScheduler = notificationScheduler,
        notificationSyncService = notificationSyncService,
        notificationPreferences = notificationPreferences,
        mobileAnalyticsController = mobileAnalyticsController,
        mobileAnalyticsEventFactory = mobileAnalyticsEventFactory,
        personalRouteDraftRepository = personalRouteDraftRepository,
        routeVisitTimeRepository = routeVisitTimeRepository,
        tastePreferenceRepository = tastePreferenceRepository,
        diaryDeviceStateRepository = diaryDeviceStateRepository,
        hiddenThoughtAuthorRepository = hiddenThoughtAuthorRepository,
        personalRouteRepository = personalRouteRepository,
        curatorNoteRepository = curatorNoteRepository,
        mostViewedExhibitionRepository = mostViewedExhibitionRepository,
        walkingRouteRepository = walkingRouteRepository,
        appUpdateRepository = appUpdateRepository,
        networkClients = networkClients,
    )
}

internal fun releaseNativeGallrDependencies(dependencies: NativeGallrDependencies) {
    if (retainedRemotePushAddressProvider === dependencies.remotePushAddressProvider) {
        retainedRemotePushAddressProvider = null
        retainedNotificationDelegate = null
        UNUserNotificationCenter.currentNotificationCenter().setDelegate(null)
    }
    cleanupScope.launch {
        runCatching { dependencies.networkClients.close() }
            .onFailure { assemblyLog.warn("close_network_clients", it) }
    }
}
