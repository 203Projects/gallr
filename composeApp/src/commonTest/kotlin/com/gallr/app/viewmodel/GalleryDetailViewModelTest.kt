package com.gallr.app.viewmodel

import com.gallr.app.notifications.RemotePushAddressProvider
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.RemotePushAddress
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSpec
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.GalleryAlertRegistrationRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `following from gallery detail records the current gallery catalogue as baseline`() =
        runTest(dispatcher) {
            val repository = GalleryDetailFollowRepository()
            val viewModel = viewModel(repository = repository)

            viewModel.toggleFollow()
            advanceUntilIdle()

            val followed = repository.current.single()
            assertEquals(setOf("kukje-current", "kukje-next"), followed.knownExhibitionIds)
            assertFalse(followed.newExhibitionAlertsEnabled)
            assertTrue(viewModel.uiState.value.isFollowing)
        }

    @Test
    fun `opening alert rationale does not request system permission`() =
        runTest(dispatcher) {
            val scheduler = FakeScheduler()
            val viewModel = viewModel(scheduler = scheduler)
            viewModel.toggleFollow()
            advanceUntilIdle()

            viewModel.openAlertRationale()

            assertTrue(viewModel.uiState.value.showAlertRationale)
            assertEquals(0, scheduler.requestPermissionCalls)
        }

    @Test
    fun `denied permission preserves follow and leaves alerts disabled`() =
        runTest(dispatcher) {
            val repository = GalleryDetailFollowRepository()
            val scheduler = FakeScheduler(permissionResult = false)
            val viewModel = viewModel(repository = repository, scheduler = scheduler)
            viewModel.toggleFollow()
            advanceUntilIdle()
            viewModel.openAlertRationale()

            viewModel.enableAlerts()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isFollowing)
            assertFalse(viewModel.uiState.value.newExhibitionAlertsEnabled)
            assertTrue(viewModel.uiState.value.permissionDenied)
        }

    @Test
    fun `granted permission enables only this gallery and closes rationale`() =
        runTest(dispatcher) {
            val repository = GalleryDetailFollowRepository()
            val scheduler = FakeScheduler(permissionResult = true)
            val registration = FakeGalleryAlertRegistrationRepository()
            val viewModel =
                viewModel(
                    repository = repository,
                    scheduler = scheduler,
                    registration = registration,
                )
            viewModel.toggleFollow()
            advanceUntilIdle()
            viewModel.openAlertRationale()

            viewModel.enableAlerts()
            advanceUntilIdle()

            assertTrue(repository.current.single().newExhibitionAlertsEnabled)
            assertTrue(viewModel.uiState.value.newExhibitionAlertsEnabled)
            assertFalse(viewModel.uiState.value.showAlertRationale)
            assertEquals(1, scheduler.requestPermissionCalls)
            assertEquals(listOf("kukje-id"), registration.enabledGalleryIds)
        }

    @Test
    fun `remote registration failure keeps local alert state honest`() =
        runTest(dispatcher) {
            val repository = GalleryDetailFollowRepository()
            val viewModel =
                viewModel(
                    repository = repository,
                    scheduler = FakeScheduler(permissionResult = true),
                    registration = FakeGalleryAlertRegistrationRepository(enableFailure = true),
                )
            viewModel.toggleFollow()
            advanceUntilIdle()
            viewModel.openAlertRationale()

            viewModel.enableAlerts()
            advanceUntilIdle()

            assertFalse(repository.current.single().newExhibitionAlertsEnabled)
            assertTrue(viewModel.uiState.value.saveFailed)
            assertTrue(viewModel.uiState.value.showAlertRationale)
        }

    @Test
    fun `gallery detail includes archived visits even when exhibition left catalogue`() =
        runTest(dispatcher) {
            val visits =
                GalleryDetailVisitRepository(
                    listOf(
                        ExhibitionVisit(
                            clientRecordId = "visit-one",
                            exhibitionId = "historic-kukje",
                            snapshot =
                                ExhibitionVisitSnapshot(
                                    nameKo = "지난 전시",
                                    nameEn = "Past Exhibition",
                                    venueNameKo = "국제갤러리",
                                    venueNameEn = "Kukje Gallery",
                                    openingDate = LocalDate(2026, 1, 1),
                                    closingDate = LocalDate(2026, 2, 1),
                                    coverImageUrl = null,
                                ),
                            createdAt = Instant.parse("2026-02-02T00:00:00Z"),
                        ),
                    ),
                )

            val viewModel = viewModel(visits = visits)
            advanceUntilIdle()

            assertEquals(
                listOf("historic-kukje"),
                viewModel.uiState.value.visitedExhibitions
                    .map { it.exhibitionId },
            )
        }

    private fun viewModel(
        repository: GalleryDetailFollowRepository = GalleryDetailFollowRepository(),
        scheduler: FakeScheduler = FakeScheduler(),
        registration: FakeGalleryAlertRegistrationRepository =
            FakeGalleryAlertRegistrationRepository(),
        addressProvider: FakeRemotePushAddressProvider = FakeRemotePushAddressProvider(),
        visits: GalleryDetailVisitRepository = GalleryDetailVisitRepository(),
    ) = GalleryDetailViewModel(
        representative = exhibition("kukje-current", LocalDate(2026, 8, 30)),
        exhibitionsState =
            MutableStateFlow(
                ExhibitionListState.Success(
                    listOf(
                        exhibition("kukje-current", LocalDate(2026, 8, 30)),
                        exhibition("kukje-next", LocalDate(2026, 10, 1)),
                        exhibition("pkm", LocalDate(2026, 9, 1), galleryId = "pkm-id"),
                    ),
                ),
            ),
        followedGalleryRepository = repository,
        notificationScheduler = scheduler,
        galleryAlertRegistrationRepository = registration,
        remotePushAddressProvider = addressProvider,
        visitRepository = visits,
        locale = "ko-KR",
        clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-08-14T00:00:00Z")
            },
    )

    private fun exhibition(
        id: String,
        openingDate: LocalDate,
        galleryId: String = "kukje-id",
    ) = Exhibition(
        id = id,
        nameKo = "현재 전시",
        nameEn = "Current Exhibition",
        venueNameKo = if (galleryId == "kukje-id") "국제갤러리" else "PKM 갤러리",
        venueNameEn = if (galleryId == "kukje-id") "Kukje Gallery" else "PKM Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "삼청",
        regionEn = "Samcheong",
        openingDate = openingDate,
        closingDate = LocalDate(2026, 11, 15),
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = "https://example.com/$id.jpg",
        galleryId = galleryId,
    )
}

private class GalleryDetailVisitRepository(
    initial: List<ExhibitionVisit> = emptyList(),
) : VisitRepository {
    private val visits = MutableStateFlow(initial)

    override fun observeVisits(): Flow<List<ExhibitionVisit>> = visits

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        this.visits.value += visits
    }

    override suspend fun removeVisit(exhibitionId: String) {
        visits.value = visits.value.filterNot { it.exhibitionId == exhibitionId }
    }
}

private class FakeGalleryAlertRegistrationRepository(
    private val enableFailure: Boolean = false,
) : GalleryAlertRegistrationRepository {
    val enabledGalleryIds = mutableListOf<String>()

    override suspend fun enableGallery(
        galleryId: String,
        address: RemotePushAddress,
        locale: String,
    ): Result<Unit> {
        enabledGalleryIds += galleryId
        return if (enableFailure) Result.failure(IllegalStateException("unavailable")) else Result.success(Unit)
    }

    override suspend fun disableGallery(
        galleryId: String,
        platform: String,
        locale: String,
    ): Result<Unit> = Result.success(Unit)
}

private class FakeRemotePushAddressProvider : RemotePushAddressProvider {
    override val platform: String = "ios"

    override suspend fun currentAddress(): RemotePushAddress =
        RemotePushAddress(
            platform = platform,
            provider = "apns",
            token = "a".repeat(64),
            environment = "sandbox",
        )
}

private class GalleryDetailFollowRepository : FollowedGalleryRepository {
    private val galleries = MutableStateFlow<List<FollowedGallery>>(emptyList())
    val current: List<FollowedGallery>
        get() = galleries.value

    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = galleries

    override suspend fun followGalleries(galleries: List<FollowedGallery>) {
        this.galleries.value = this.galleries.value + galleries
    }

    override suspend fun unfollowGallery(galleryKey: String) {
        galleries.value = galleries.value.filterNot { it.galleryKey == galleryKey }
    }

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) = Unit

    override suspend fun setNewExhibitionAlertsEnabled(
        galleryKey: String,
        enabled: Boolean,
    ) {
        galleries.value =
            galleries.value.map { gallery ->
                if (gallery.galleryKey == galleryKey) {
                    gallery.copy(newExhibitionAlertsEnabled = enabled)
                } else {
                    gallery
                }
            }
    }
}

private class FakeScheduler(
    private val permissionResult: Boolean = true,
) : NotificationScheduler {
    var requestPermissionCalls = 0
        private set

    override suspend fun hasPermission(): Boolean = permissionResult

    override suspend fun requestPermission(): Boolean {
        requestPermissionCalls += 1
        return permissionResult
    }

    override suspend fun schedule(spec: NotificationSpec) = Unit

    override suspend fun cancel(id: String) = Unit

    override suspend fun cancelAll() = Unit

    override suspend fun scheduledIds(): Set<String> = emptySet()

    private val pending = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = pending

    override fun setPendingDeepLink(link: DeepLink) {
        pending.value = link
    }

    override fun consumePendingDeepLink() {
        pending.value = null
    }
}
