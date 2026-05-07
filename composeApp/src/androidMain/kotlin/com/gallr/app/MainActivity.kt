package com.gallr.app

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gallr.app.splash.SplashController
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.network.EventApiClient
import com.gallr.shared.data.network.ExhibitionApiClient
import com.gallr.shared.data.network.createGallrSupabaseClient
import com.gallr.shared.platform.createDataStore
import com.gallr.shared.platform.initDataStore
import com.gallr.shared.repository.AuthRepositoryImpl
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.EventRepositoryImpl
import com.gallr.shared.repository.ExhibitionRepositoryImpl
import com.gallr.shared.repository.LanguageRepositoryImpl
import com.gallr.shared.repository.ProfileRepositoryImpl
import com.gallr.shared.repository.NotificationPreferences
import com.gallr.shared.repository.ThemeRepositoryImpl
import com.gallr.shared.repository.ThoughtRepositoryImpl
import com.gallr.app.notifications.ActivityNotificationPermissionRequester
import com.gallr.app.notifications.AndroidNotificationScheduler
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationConstants
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.notifications.ScheduledIdIndex

class MainActivity : ComponentActivity() {
    private lateinit var supabaseClient: io.github.jan.supabase.SupabaseClient
    private lateinit var notificationScheduler: AndroidNotificationScheduler

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle OAuth deeplink callback
        intent.data?.let { uri ->
            kotlinx.coroutines.MainScope().launch {
                com.gallr.shared.data.network.handleAuthDeeplink(supabaseClient, uri.toString())
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
            NotificationConstants.DEEPLINK_MYLIST -> DeepLink.MyList
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize DataStore with application context before any repository uses it.
        initDataStore(applicationContext)
        initShareHandler(applicationContext)

        val dataStore = createDataStore()
        supabaseClient = createGallrSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        )
        val exhibitionRepository = ExhibitionRepositoryImpl(
            ExhibitionApiClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        )
        val eventRepository = EventRepositoryImpl(
            EventApiClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        )
        val localBookmarkRepository = BookmarkRepositoryImpl(dataStore)
        val cloudBookmarkRepository = CloudBookmarkRepository(supabaseClient)
        val authRepository = AuthRepositoryImpl(supabaseClient)
        val profileRepository = ProfileRepositoryImpl(supabaseClient)
        val thoughtRepository = ThoughtRepositoryImpl(supabaseClient)
        val languageRepository = LanguageRepositoryImpl(dataStore)
        val themeRepository = ThemeRepositoryImpl(dataStore)
        val splashController = SplashController(scope = lifecycleScope)
        splash.setKeepOnScreenCondition { !splashController.themeReadyValue() }
        splashController.start()
        if (savedInstanceState != null) splashController.skipSplash()

        val scheduledIdIndex = ScheduledIdIndex(dataStore)
        val permissionRequester = ActivityNotificationPermissionRequester(this)
        notificationScheduler = AndroidNotificationScheduler(
            context = applicationContext,
            index = scheduledIdIndex,
            permissionRequester = permissionRequester,
        )
        val notificationSyncService = NotificationSyncService(
            scheduler = notificationScheduler,
            exhibitionRepo = exhibitionRepository,
            bookmarkRepo = localBookmarkRepository,
            languageRepo = languageRepository,
        )
        val notificationPreferences = NotificationPreferences(dataStore)
        extractDeepLink(intent)?.let { notificationScheduler.setPendingDeepLink(it) }

        // Handle deeplink from initial launch (cold start from OAuth redirect)
        intent.data?.let { uri ->
            kotlinx.coroutines.MainScope().launch {
                com.gallr.shared.data.network.handleAuthDeeplink(supabaseClient, uri.toString())
            }
        }

        setContent {
            val currentThemeMode by themeRepository.observeThemeMode().collectAsState(initial = ThemeMode.SYSTEM)
            val isDarkTheme = when (currentThemeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            DisposableEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { isDarkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { isDarkTheme },
                )
                onDispose {}
            }

            App(
                exhibitionRepository = exhibitionRepository,
                eventRepository = eventRepository,
                localBookmarkRepository = localBookmarkRepository,
                cloudBookmarkRepository = cloudBookmarkRepository,
                authRepository = authRepository,
                profileRepository = profileRepository,
                thoughtRepository = thoughtRepository,
                languageRepository = languageRepository,
                themeRepository = themeRepository,
                supabaseClient = supabaseClient,
                splashController = splashController,
                notificationScheduler = notificationScheduler,
                notificationSyncService = notificationSyncService,
                notificationPreferences = notificationPreferences,
            )
        }
    }
}
