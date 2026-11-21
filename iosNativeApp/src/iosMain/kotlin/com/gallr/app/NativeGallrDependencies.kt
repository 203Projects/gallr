package com.gallr.app

import com.gallr.app.notifications.RemotePushAddressProvider
import com.gallr.shared.analytics.MobileAnalyticsController
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.data.network.GallrNetworkClients
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.repository.AppUpdateRepository
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.CuratorNoteRepository
import com.gallr.shared.repository.DiaryDeviceStateRepository
import com.gallr.shared.repository.EditorRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.GalleryAlertRegistrationRepository
import com.gallr.shared.repository.GalleryProfileImageRepository
import com.gallr.shared.repository.HiddenThoughtAuthorRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.MostViewedExhibitionRepository
import com.gallr.shared.repository.MyGallrAccountNudgeRepository
import com.gallr.shared.repository.MyGallrAccountStore
import com.gallr.shared.repository.NotificationPreferences
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.PromotionRepository
import com.gallr.shared.repository.RouteVisitTimeRepository
import com.gallr.shared.repository.TastePreferenceRepository
import com.gallr.shared.repository.ThemeRepository
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.repository.VisitRepository
import com.gallr.shared.repository.WalkingRouteRepository

internal class NativeGallrDependencies(
    val exhibitionRepository: ExhibitionRepository,
    val eventRepository: EventRepository,
    val editorRepository: EditorRepository,
    val galleryProfileImageRepository: GalleryProfileImageRepository,
    val localBookmarkRepository: BookmarkRepositoryImpl,
    val cloudBookmarkRepository: CloudBookmarkRepository,
    val authRepository: AuthRepository,
    val profileRepository: ProfileRepository,
    val thoughtRepository: ThoughtRepository,
    val visitRepository: VisitRepository,
    val followedGalleryRepository: FollowedGalleryRepository,
    val myGallrAccountStore: MyGallrAccountStore,
    val myGallrAccountSource: MyGallrAccountCommandSource,
    val galleryAlertRegistrationRepository: GalleryAlertRegistrationRepository,
    val remotePushAddressProvider: RemotePushAddressProvider,
    val accountNudgeRepository: MyGallrAccountNudgeRepository,
    val languageRepository: LanguageRepository,
    val themeRepository: ThemeRepository,
    val promotionRepository: PromotionRepository,
    val notificationScheduler: NotificationScheduler,
    val notificationSyncService: NotificationSyncService,
    val notificationPreferences: NotificationPreferences,
    val mobileAnalyticsController: MobileAnalyticsController,
    val mobileAnalyticsEventFactory: MobileAnalyticsEventFactory?,
    val personalRouteDraftRepository: PersonalRouteDraftRepository,
    val routeVisitTimeRepository: RouteVisitTimeRepository,
    val tastePreferenceRepository: TastePreferenceRepository,
    val diaryDeviceStateRepository: DiaryDeviceStateRepository,
    val hiddenThoughtAuthorRepository: HiddenThoughtAuthorRepository,
    val personalRouteRepository: PersonalRouteRepository,
    val curatorNoteRepository: CuratorNoteRepository,
    val mostViewedExhibitionRepository: MostViewedExhibitionRepository,
    val walkingRouteRepository: WalkingRouteRepository,
    val appUpdateRepository: AppUpdateRepository,
    val networkClients: GallrNetworkClients,
)
